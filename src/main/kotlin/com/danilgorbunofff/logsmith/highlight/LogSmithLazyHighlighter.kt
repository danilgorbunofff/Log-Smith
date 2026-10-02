package com.danilgorbunofff.logsmith.highlight

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import java.awt.Font

/**
 * Visible-range highlighting (charter §5.3 R5, §8 Day 7), which replaces the
 * whole-document [com.intellij.openapi.editor.ex.util.LexerEditorHighlighter] and its
 * size cap. Nothing here ever lexes the file: [createIterator] builds **windows** of at
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
 * JSON blob pasted into a log cannot hang a paint. The same rule drops an over-long line
 * from the tail of an otherwise normal window.
 *
 * Threading: [createIterator] and [documentChanged] run on the EDT (the platform's paint
 * and document paths), which is what makes the plain [HashMap] attribute memo and the
 * window cache safe; the cache itself is guarded by its own lock so a stray off-EDT read
 * cannot corrupt the LRU order.
 */
class LogSmithLazyHighlighter(
    private val syntaxHighlighter: SyntaxHighlighter,
    scheme: EditorColorsScheme,
) : EditorHighlighter {

    private var client: HighlighterClient? = null
    private var document: Document? = null
    private var text: CharSequence = ""
    private var colorScheme: EditorColorsScheme = scheme
    private var plainAttributes: TextAttributes = plainAttributes(scheme)
    private val attributesByType = HashMap<IElementType, TextAttributes>()
    private val windows = LinkedHashMap<Int, WindowTokens>(MAX_CACHED_WINDOWS, 0.75f, true)

    override fun setText(text: CharSequence) {
        this.text = text
        invalidateAll()
    }

    override fun setEditor(client: HighlighterClient) {
        this.client = client
        document = client.document
        text = document?.immutableCharSequence ?: text
        invalidateAll()
    }

    override fun setColorScheme(scheme: EditorColorsScheme) {
        colorScheme = scheme
        plainAttributes = plainAttributes(scheme)
        attributesByType.clear()
        invalidateAll()
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
        var endLine = minOf(doc.lineCount, startLine + WINDOW_LINES)
        while (endLine > startLine + 1) {
            val end = lineStartOrLength(doc, endLine)
            if (end - start <= WINDOW_MAX_CHARS && !lineTooLong(doc, endLine - 1)) break
            endLine--
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
        return WindowTokens(start, end, accumulator.ends(), accumulator.types())
    }

    private fun plainWindow(start: Int, end: Int): WindowTokens =
        WindowTokens(start, end, intArrayOf(end), arrayOf(LogSmithTokenTypes.GENERIC))

    private fun lineStartOrLength(doc: Document, line: Int): Int =
        if (line >= doc.lineCount) doc.textLength else doc.getLineStartOffset(line)

    private fun lineTooLong(doc: Document, line: Int): Boolean =
        doc.getLineEndOffset(line) - doc.getLineStartOffset(line) > MAX_SEGMENTED_LINE_CHARS

    private class WindowTokens(
        val start: Int,
        val end: Int,
        private val ends: IntArray,
        private val types: Array<IElementType?>,
    ) {
        val size: Int get() = ends.size

        fun startAt(index: Int): Int = if (index == 0) start else ends[index - 1]

        fun endAt(index: Int): Int = ends[index]

        fun typeAt(index: Int): IElementType? = types[index]

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

        init {
            val length = doc.textLength
            if (length > 0) {
                val clamped = offset.coerceIn(0, length)
                val window = windowForLine(doc.getLineNumber(clamped))
                chain.add(window)
                tokenIndex = window.indexOfToken(clamped)
            } else {
                pastEnd = true
            }
        }

        override fun getTextAttributes(): TextAttributes {
            if (atEnd()) return plainAttributes
            val current = chain[windowIndex]
            return attributesFor(current.typeAt(tokenIndex))
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

        override fun atEnd(): Boolean = pastEnd || chain.isEmpty()

        override fun getDocument(): Document = doc

        override fun advance() {
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

        override fun retreat() {
            if (chain.isEmpty()) return
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
            if (windowIndex == 0) return
            windowIndex--
            tokenIndex = chain[windowIndex].size - 1
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
    }
}
