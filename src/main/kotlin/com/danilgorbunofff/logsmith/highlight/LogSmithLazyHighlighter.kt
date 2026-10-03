package com.danilgorbunofff.logsmith.highlight

import com.danilgorbunofff.logsmith.ansi.AnsiStyle
import com.danilgorbunofff.logsmith.ansi.AnsiText
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.ex.PrioritizedDocumentListener
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.impl.EditorDocumentPriorities
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import java.awt.Font

/**
 * Visible-range highlighting (charter §5.3 R5, §8 Day 7), which replaces the
 * whole-document [com.intellij.openapi.editor.ex.util.LexerEditorHighlighter] and its
 * size cap. LogSmith's own lexer never lexes the file: [createIterator] builds **windows** of at
 * most [WINDOW_LINES] lines or [WINDOW_MAX_CHARS] characters — whichever comes first —
 * lazily, on demand, and keeps the most recent [MAX_CACHED_WINDOWS] of them. Total memory
 * is therefore bounded by the window cache, never by file size, and the platform's own
 * `Document` still owns the text, so scrollbars, search and editing are untouched.
 *
 * A window is a half-open *line* range `[startLine, endLine)` translated to offsets with
 * `Document.getLineStartOffset`, so each window tiles the document exactly: window *n*
 * ends where window *n + 1* begins. Tokens inside a window come from one lexer pass over a
 * flat copy of that region ([SyntaxHighlighter.getHighlightingLexer] is called per window,
 * never shared), and every token offset is validated against the window bounds; a lexer that
 * overshoots, stalls or stops early makes the whole window one plain `GENERIC` token rather
 * than a corrupted paint.
 *
 * A single line longer than [MAX_SEGMENTED_LINE_CHARS] is never segmented: it becomes the
 * one-token window it deserves, at O(1) cost and without reading the text, so a minified
 * JSON blob pasted into a log cannot hang a paint. A window always ends before an over-long
 * line, wherever it sits, so the rule holds for every line, not just a window's first.
 *
 * Windows tile fixed blocks of [WINDOW_LINES] lines (see [windowContaining]), so scrolling
 * inside a block is served from the cache rather than building a window per scroll step.
 *
 * Never worse than the platform (charter §5.4): the highlighter the IDE itself would use for the
 * file — for `.log` that is the bundled TextMate log grammar, which already colours strings,
 * numbers, URLs and exception names — is kept as [base] and kept in sync. LogSmith only paints
 * what it has an opinion about (timestamps, threads, the level ramp, ANSI runs and escapes);
 * every other character keeps exactly the attributes [base] gives it, split at [base]'s own
 * token boundaries. Without a [base] those characters get the editor's plain text. [base] is
 * the platform's highlighter doing exactly the work it does without LogSmith installed (for a
 * lexer-based one, keeping the whole document lexed), so it costs nothing LogSmith added.
 *
 * ANSI (charter §5.6 R9): a window that contains an escape sequence gets one [AnsiText.runs]
 * pass over its flat copy, and each token inside a styled run paints with that style instead
 * of the level ramp — the ramp is untouched outside runs. A window without escapes allocates
 * nothing for this. The escapes themselves are tokens too, and paint no ink at all, because
 * the raw bytes have to stay in the document (see [AnsiAttributes.escape]).
 *
 * Threading: [createIterator] and [documentChanged] run on the EDT (the platform's paint
 * and document paths), which is what makes the plain [HashMap] attribute memo and the
 * window cache safe; the cache itself is guarded by its own lock so a stray off-EDT read
 * cannot corrupt the LRU order.
 */
class LogSmithLazyHighlighter(
    private val syntaxHighlighter: SyntaxHighlighter,
    scheme: EditorColorsScheme,
    private val base: EditorHighlighter? = null,
) : EditorHighlighter, PrioritizedDocumentListener {

    private var client: HighlighterClient? = null
    private var document: Document? = null
    private var text: CharSequence = ""
    private var colorScheme: EditorColorsScheme = scheme
    private var plainAttributes: TextAttributes = plainAttributes(scheme)
    private var escapeAttributes: TextAttributes = escapeAttributes(scheme)
    private val attributesByType = HashMap<IElementType, TextAttributes>()
    private val ownsColourByType = HashMap<IElementType, Boolean>()
    private val ansiByStyle = HashMap<IElementType?, HashMap<AnsiStyle, TextAttributes>>()
    private val windows = LinkedHashMap<Int, WindowTokens>(MAX_CACHED_WINDOWS, 0.75f, true)

    override fun setText(text: CharSequence) {
        this.text = text
        base?.setText(text)
        invalidateAll()
    }

    override fun setEditor(client: HighlighterClient) {
        this.client = client
        document = client.document
        text = document?.immutableCharSequence ?: text
        base?.setEditor(client)
        invalidateAll()
    }

    override fun setColorScheme(scheme: EditorColorsScheme) {
        colorScheme = scheme
        plainAttributes = plainAttributes(scheme)
        escapeAttributes = escapeAttributes(scheme)
        attributesByType.clear()
        ownsColourByType.clear()
        ansiByStyle.clear()
        base?.setColorScheme(scheme)
        invalidateAll()
    }

    /** The same slot a platform lexer highlighter takes, so neither layer is read stale mid-change. */
    override fun getPriority(): Int = EditorDocumentPriorities.LEXER_EDITOR

    override fun beforeDocumentChange(event: DocumentEvent) {
        base?.beforeDocumentChange(event)
    }

    override fun createIterator(offset: Int): HighlighterIterator {
        val doc = document
            ?: throw IllegalStateException("LogSmith highlighting needs an editor document")
        return Iter(doc, offset)
    }

    /**
     * A change only shifts offsets at or after [DocumentEvent.getOffset]; windows that end
     * before it keep their offsets *and* their line numbers, so they stay valid and stay
     * cached. Windows at or after it are dropped and rebuilt from the current line starts.
     */
    override fun documentChanged(event: DocumentEvent) {
        base?.documentChanged(event)
        val offset = event.offset
        synchronized(windows) {
            val entries = windows.entries.iterator()
            while (entries.hasNext()) {
                if (entries.next().value.end >= offset) entries.remove()
            }
        }
        client?.repaint(offset, offset + event.newLength)
    }

    private fun invalidateAll() {
        synchronized(windows) { windows.clear() }
    }

    /** Test hook: how many windows are currently cached. */
    internal val cachedWindowCount: Int get() = synchronized(windows) { windows.size }

    /** Test hook: drop every cached window, as an edit would, without touching the text. */
    internal fun clearWindowsForTests() = invalidateAll()

    private fun attributesFor(type: IElementType?): TextAttributes {
        if (type == null) return plainAttributes
        attributesByType[type]?.let { return it }
        val key = syntaxHighlighter.getTokenHighlights(type).firstOrNull()
        val attributes = if (key == null) plainAttributes else colorScheme.getAttributes(key) ?: plainAttributes
        attributesByType[type] = attributes
        return attributes
    }

    /**
     * True when LogSmith paints [type] itself — its colour key resolves to real attributes.
     * Tokens it has no opinion about (logger, message, INFO, generic text) show [base] instead.
     */
    private fun ownsColour(type: IElementType): Boolean = ownsColourByType.getOrPut(type) {
        if (type == LogSmithTokenTypes.ANSI_ESCAPE) return@getOrPut true
        val key = syntaxHighlighter.getTokenHighlights(type).firstOrNull() ?: return@getOrPut false
        colorScheme.getAttributes(key)?.isEmpty == false
    }

    /**
     * ANSI wins inside its run and the level ramp applies unchanged outside one (charter Day 9
     * step 2). Both dimensions are small — a handful of token types, a handful of styles per log
     * — so merged attributes are memoised rather than rebuilt for every painted token.
     */
    private fun ansiAttributes(type: IElementType?, base: TextAttributes, style: AnsiStyle): TextAttributes =
        ansiByStyle.getOrPut(type) { HashMap() }.getOrPut(style) {
            AnsiAttributes.of(style, colorScheme, base)
        }

    /**
     * The window holding [offset]. Windows tile fixed blocks of [WINDOW_LINES] lines: the first
     * window of a block starts at the block's first line and each next one where the previous
     * ended, so every lookup inside a block — a scroll by one line included — finds the same
     * cached windows instead of building a new one from its own first line.
     */
    private fun windowContaining(offset: Int): WindowTokens {
        val doc = document
            ?: throw IllegalStateException("LogSmith highlighting needs an editor document")
        val line = doc.getLineNumber(offset.coerceIn(0, doc.textLength))
        // The empty line after a final newline has no characters of its own: it is its own
        // (empty) window, as the platform reports a token starting there.
        if (offset >= doc.textLength && doc.getLineStartOffset(line) == doc.textLength) return windowForLine(line)
        var window = windowForLine(line - line % WINDOW_LINES)
        while (window.end <= offset && window.end < doc.textLength) {
            window = windowForLine(doc.getLineNumber(window.end))
        }
        return window
    }

    private fun windowForLine(line: Int): WindowTokens {
        val doc = document
            ?: throw IllegalStateException("LogSmith highlighting needs an editor document")
        val key = line.coerceIn(0, doc.lineCount - 1)
        synchronized(windows) { windows[key]?.let { return it } }
        val built = buildWindow(doc, key)
        synchronized(windows) {
            windows[key] = built
            while (windows.size > MAX_CACHED_WINDOWS) {
                val leastRecentlyUsed = windows.keys.firstOrNull() ?: break
                windows.remove(leastRecentlyUsed)
            }
        }
        return built
    }

    private fun buildWindow(doc: Document, startLine: Int): WindowTokens {
        val length = doc.textLength
        val start = doc.getLineStartOffset(startLine)
        val nextLineStart = if (startLine + 1 < doc.lineCount) doc.getLineStartOffset(startLine + 1) else length
        if (nextLineStart - start > MAX_SEGMENTED_LINE_CHARS) {
            return plainWindow(start, nextLineStart)
        }
        // Grow line by line up to the block end and the character budget, and stop before any
        // over-long line: it becomes a window of its own (above), wherever in a block it sits.
        val blockEnd = minOf(doc.lineCount, startLine - startLine % WINDOW_LINES + WINDOW_LINES)
        var endLine = startLine + 1
        while (endLine < blockEnd) {
            if (lineTooLong(doc, endLine)) break
            if (lineStartOrLength(doc, endLine + 1) - start > WINDOW_MAX_CHARS) break
            endLine++
        }
        val end = lineStartOrLength(doc, endLine)
        return segment(doc.getText(TextRange(start, end)), start, end)
    }

    /**
     * One lexer pass over the window's text, validated for contiguous and complete coverage.
     *
     * The lexer runs on a flat [String] copy of `[start, end)` rather than on the document's
     * rope: the lexer and [LineSegmenter] slice the buffer per line, and every rope slice
     * walks chunks, which measured ~5x slower (35 ms vs 7 ms for a 256-line window) while the
     * bulk copy itself costs ~1.5 ms. Tokens come back relative to the copy and are rebased.
     */
    private fun segment(windowText: String, start: Int, end: Int): WindowTokens {
        val accumulator = TokenAccumulator()
        var expected = 0
        var broken = false
        val lexer = syntaxHighlighter.highlightingLexer
        lexer.start(windowText, 0, windowText.length, LogSmithLexer.LINE_START_STATE)
        while (true) {
            val type = lexer.tokenType ?: break
            val tokenStart = lexer.tokenStart
            val tokenEnd = lexer.tokenEnd
            if (tokenStart != expected || tokenEnd <= tokenStart || tokenEnd > windowText.length) {
                broken = true
                break
            }
            accumulator.add(tokenEnd + start, type)
            expected = tokenEnd
            lexer.advance()
        }
        if (broken || accumulator.size == 0 || expected != windowText.length) return plainWindow(start, end)
        val ends = accumulator.ends()
        return compose(start, end, ends, accumulator.types(), ansiStyles(windowText, ends, start))
    }

    /**
     * Lays [base]'s attributes under the LogSmith tokens LogSmith does not paint itself. Such a
     * token is cut at every [base] token boundary inside it, and each piece carries [base]'s
     * attributes; tokens LogSmith paints — and ANSI-styled ones — stay whole and keep LogSmith's.
     */
    private fun compose(
        start: Int,
        end: Int,
        ends: IntArray,
        types: Array<IElementType?>,
        styles: Array<AnsiStyle?>?,
    ): WindowTokens {
        val under = baseTokens(start, end) ?: return WindowTokens(start, end, ends, types, styles, null)
        val out = TokenAccumulator()
        val outStyles = ArrayList<AnsiStyle?>(ends.size)
        val outBase = ArrayList<TextAttributes?>(ends.size)
        var b = 0
        var tokenStart = start
        for (index in ends.indices) {
            val tokenEnd = ends[index]
            val type = types[index] ?: LogSmithTokenTypes.GENERIC
            val style = styles?.get(index)
            if (style != null || ownsColour(type) || tokenEnd == tokenStart) {
                // Owned tokens keep LogSmith's paint; an empty token (the empty last line) stays
                // as it is, since there is nothing under it to split.
                out.add(tokenEnd, type)
                outStyles += style
                outBase += null
            } else {
                var pos = tokenStart
                while (pos < tokenEnd) {
                    while (b < under.size && under.ends[b] <= pos) b++
                    val covering = b < under.size && under.starts[b] <= pos
                    val pieceEnd = when {
                        b >= under.size -> tokenEnd
                        covering -> minOf(under.ends[b], tokenEnd)
                        else -> minOf(under.starts[b], tokenEnd)
                    }
                    out.add(pieceEnd, type)
                    outStyles += null
                    outBase += if (covering) under.attributes[b] else null
                    pos = pieceEnd
                }
            }
            tokenStart = tokenEnd
        }
        return WindowTokens(
            start, end, out.ends(), out.types(),
            if (styles == null) null else outStyles.toTypedArray(),
            outBase.toTypedArray(),
        )
    }

    /** [base]'s tokens over `[start, end)`, clipped to it; null without a usable [base]. */
    private fun baseTokens(start: Int, end: Int): BaseTokens? {
        val base = base ?: return null
        return try {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val attributes = ArrayList<TextAttributes>()
            val iterator = base.createIterator(start)
            var guard = end - start + 1
            while (!iterator.atEnd() && iterator.start < end && guard-- > 0) {
                val tokenStart = maxOf(iterator.start, start)
                val tokenEnd = minOf(iterator.end, end)
                if (tokenEnd > tokenStart && (ends.isEmpty() || tokenStart >= ends.last())) {
                    starts += tokenStart
                    ends += tokenEnd
                    attributes += iterator.textAttributes
                }
                iterator.advance()
            }
            BaseTokens(starts.toIntArray(), ends.toIntArray(), attributes.toTypedArray())
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: RuntimeException) {
            // A platform highlighter out of step with the document must not break the paint:
            // this window falls back to LogSmith's own attributes over plain text.
            null
        }
    }

    private class BaseTokens(val starts: IntArray, val ends: IntArray, val attributes: Array<TextAttributes>) {
        val size: Int get() = ends.size
    }

    /**
     * ANSI style per token, or null when the window holds no escape sequence at all — the
     * overwhelmingly common case, and the one that must cost nothing (charter §5.6 R9). Runs
     * and tokens both ascend, so one merge pass over each decides every token; a token that
     * falls inside no run keeps the ramp. [ends] are document offsets while [AnsiText.runs]
     * counts from the start of the window, so the runs are rebased by [start].
     */
    private fun ansiStyles(windowText: String, ends: IntArray, start: Int): Array<AnsiStyle?>? {
        if (!AnsiText.containsEscape(windowText)) return null
        val runs = AnsiText.runs(windowText)
        if (runs.isEmpty()) return null
        val styles = arrayOfNulls<AnsiStyle>(ends.size)
        var runIndex = 0
        var tokenStart = 0
        for (index in ends.indices) {
            val tokenEnd = ends[index]
            while (runIndex < runs.size && runs[runIndex].end + start <= tokenStart) runIndex++
            val run = runs.getOrNull(runIndex)
            if (run != null && run.start + start < tokenEnd && run.end + start > tokenStart) {
                styles[index] = run.style
            }
            tokenStart = tokenEnd
        }
        return styles
    }

    private fun plainWindow(start: Int, end: Int): WindowTokens =
        compose(start, end, intArrayOf(end), arrayOf(LogSmithTokenTypes.GENERIC), null)

    private fun lineStartOrLength(doc: Document, line: Int): Int =
        if (line >= doc.lineCount) doc.textLength else doc.getLineStartOffset(line)

    private fun lineTooLong(doc: Document, line: Int): Boolean =
        doc.getLineEndOffset(line) - doc.getLineStartOffset(line) > MAX_SEGMENTED_LINE_CHARS

    private class WindowTokens(
        val start: Int,
        val end: Int,
        private val ends: IntArray,
        private val types: Array<IElementType?>,
        private val styles: Array<AnsiStyle?>?,
        private val baseAttributes: Array<TextAttributes?>?,
    ) {
        val size: Int get() = ends.size

        /** The platform highlighter's attributes for this token, when LogSmith leaves it to them. */
        fun baseAt(index: Int): TextAttributes? = baseAttributes?.get(index)

        fun startAt(index: Int): Int = if (index == 0) start else ends[index - 1]

        fun endAt(index: Int): Int = ends[index]

        fun typeAt(index: Int): IElementType? = types[index]

        /** The ANSI style covering this token, or null when there is none to apply. */
        fun styleAt(index: Int): AnsiStyle? = styles?.get(index)

        /** Index of the token containing [offset]; the last token when [offset] is past the window. */
        fun indexOfToken(offset: Int): Int {
            for (index in ends.indices) {
                if (ends[index] > offset) return index
            }
            return ends.size - 1
        }
    }

    private class TokenAccumulator {
        private var ends = IntArray(INITIAL_TOKENS)
        private var types = arrayOfNulls<IElementType>(INITIAL_TOKENS)
        var size = 0
            private set

        fun add(end: Int, type: IElementType) {
            if (size == ends.size) {
                ends = ends.copyOf(size * 2)
                types = types.copyOf(size * 2)
            }
            ends[size] = end
            types[size] = type
            size++
        }

        fun ends(): IntArray = ends.copyOf(size)

        fun types(): Array<IElementType?> = types.copyOf(size)
    }

    /**
     * Iterator over a chain of windows. It starts inside the window holding the requested
     * offset and extends forward one window at a time as the painter walks off the end, so
     * a paint sequence over a screen costs one or two window builds. The chain is trimmed
     * to [MAX_CHAIN_WINDOWS] from the front once the cursor is safely past them.
     */
    private inner class Iter(private val doc: Document, offset: Int) : HighlighterIterator {

        private val chain = ArrayList<WindowTokens>(2)
        private var windowIndex = 0
        private var tokenIndex = 0
        private var pastEnd = false

        /** Retreated past the first token of the document: [atEnd], as the platform contract has it. */
        private var beforeStart = false

        init {
            val length = doc.textLength
            if (length > 0) {
                val clamped = offset.coerceIn(0, length)
                val window = windowContaining(clamped)
                chain.add(window)
                tokenIndex = window.indexOfToken(clamped)
            } else {
                pastEnd = true
            }
        }

        override fun getTextAttributes(): TextAttributes {
            if (atEnd()) return plainAttributes
            val current = chain[windowIndex]
            val type = current.typeAt(tokenIndex)
            if (type == LogSmithTokenTypes.ANSI_ESCAPE) return escapeAttributes
            current.baseAt(tokenIndex)?.let { return it }
            val base = attributesFor(type)
            val style = current.styleAt(tokenIndex) ?: return base
            return ansiAttributes(type, base, style)
        }

        override fun getStart(): Int {
            if (atEnd()) return doc.textLength
            val current = chain[windowIndex]
            return current.startAt(tokenIndex).coerceIn(0, doc.textLength)
        }

        override fun getEnd(): Int {
            if (atEnd()) return doc.textLength
            val current = chain[windowIndex]
            val start = current.startAt(tokenIndex).coerceIn(0, doc.textLength)
            return current.endAt(tokenIndex).coerceIn(start, doc.textLength)
        }

        override fun getTokenType(): IElementType? {
            if (atEnd()) return null
            return chain[windowIndex].typeAt(tokenIndex)
        }

        override fun atEnd(): Boolean = pastEnd || beforeStart || chain.isEmpty()

        override fun getDocument(): Document = doc

        override fun advance() {
            if (beforeStart) {
                // Back onto the first token, as LexerEditorHighlighter does from index -1.
                beforeStart = false
                return
            }
            if (atEnd()) return
            val current = chain[windowIndex]
            if (tokenIndex + 1 < current.size) {
                tokenIndex++
                return
            }
            val nextLine = doc.getLineNumber(current.end.coerceIn(0, doc.textLength))
            val next = windowForLine(nextLine)
            // The document may have shifted under a stale window; never emit overlapping
            // or non-advancing tokens — stop instead, and the next paint re-creates us.
            if (next.start != current.end || next.end <= current.end) {
                pastEnd = true
                tokenIndex = 0
                return
            }
            chain.add(next)
            windowIndex = chain.size - 1
            tokenIndex = 0
            while (chain.size > MAX_CHAIN_WINDOWS && windowIndex > 2) {
                chain.removeAt(0)
                windowIndex--
            }
        }

        /**
         * Steps back one token, across window boundaries, and reports [atEnd] once it has stepped
         * past the document's first token — the platform's contract, which backward scans rely on
         * to stop. The window before the chain is looked up through the same block tiling, so the
         * tokens met walking backwards are exactly those met walking forwards.
         */
        override fun retreat() {
            if (chain.isEmpty() || beforeStart) return
            if (pastEnd) {
                pastEnd = false
                windowIndex = chain.size - 1
                tokenIndex = chain[windowIndex].size - 1
                return
            }
            if (tokenIndex > 0) {
                tokenIndex--
                return
            }
            if (windowIndex > 0) {
                windowIndex--
                tokenIndex = chain[windowIndex].size - 1
                return
            }
            val first = chain[0].start
            if (first <= 0) {
                beforeStart = true
                return
            }
            val previous = windowContaining(first - 1)
            if (previous.end != first) {
                // The document moved under a stale chain; stop rather than emit overlapping tokens.
                beforeStart = true
                return
            }
            chain.add(0, previous)
            while (chain.size > MAX_CHAIN_WINDOWS) chain.removeAt(chain.size - 1)
            windowIndex = 0
            tokenIndex = previous.size - 1
        }
    }

    companion object {
        /** Lines per window: one screen plus a scroll, so a page-down rarely rebuilds. */
        internal const val WINDOW_LINES = 256

        /** Character budget per window, whichever limit bites first. */
        internal const val WINDOW_MAX_CHARS = 512 * 1024

        /** A line longer than this is never segmented (matches [LineSegmenter]'s assumptions). */
        internal const val MAX_SEGMENTED_LINE_CHARS = 64 * 1024

        /** Windows kept alive per editor; a window is at most [WINDOW_MAX_CHARS] of tokens. */
        internal const val MAX_CACHED_WINDOWS = 12

        private const val MAX_CHAIN_WINDOWS = 8
        private const val INITIAL_TOKENS = 64

        /** What the platform paints a token with when no colour key applies: the editor's own text. */
        private fun plainAttributes(scheme: EditorColorsScheme): TextAttributes =
            scheme.getAttributes(HighlighterColors.TEXT)
                ?: TextAttributes(scheme.defaultForeground, null, null, null, Font.PLAIN)

        /** The escape sequences keep their columns but must show no ink: [AnsiAttributes.escape]. */
        private fun escapeAttributes(scheme: EditorColorsScheme): TextAttributes = AnsiAttributes.escape(scheme)
    }
}
