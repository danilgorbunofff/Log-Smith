package com.danilgorbunofff.logsmith.highlight

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Line-oriented lexer over the raw log document. Each line is classified by
 * [LineSegmenter]; the newline region (\r + \n) is always covered by a WS
 * token so the whole buffer is accounted for, as [LexerEditorHighlighter]
 * requires. Lexing restarts re-segment only from a known line start, and
 * deep starts are reached by a cheap per-line fast-forward — never recursion.
 *
 * State contract (what lets [com.intellij.openapi.editor.ex.util.LexerEditorHighlighter]
 * re-lex incrementally): the first token of every line reports [LINE_START_STATE] (0, the
 * initial state), every other token reports [MID_LINE_STATE]. The highlighter therefore
 * restarts at the edited line's start and stops at the first unchanged line after it.
 * Lines carry no state into each other, so the state value itself is never needed on restart.
 */
class LogSmithLexer(private val segmenter: LineSegmenter) : LexerBase() {

    private var buffer: CharSequence = ""
    private var endOffset: Int = 0

    private var lineStart: Int = 0
    private var contentEnd: Int = 0
    private var wsEnd: Int = -1
    private var nextLineStart: Int = -1

    private var spans: List<Span> = emptyList()
    private var spanIndex: Int = 0
    private var wsPending: Boolean = false
    private var done: Boolean = true

    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset.coerceAtMost(buffer.length)
        done = false
        beginAt(lineStartOf(startOffset.coerceIn(0, buffer.length)), skipThrough = startOffset)
        emitNext()
    }

    override fun getState(): Int =
        if (done || tokenStart == lineStart) LINE_START_STATE else MID_LINE_STATE

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        if (done) return
        emitNext()
    }

    override fun getBufferEnd(): Int = buffer.length

    override fun getBufferSequence(): CharSequence = buffer

    /** true when [startOffset] contains no more tokens, i.e. advance() produced null. */
    fun isExhausted(): Boolean = done

    private fun beginAt(lineStart: Int, skipThrough: Int) {
        var ls = lineStart
        var clipped = skipThrough > lineStart
        while (true) {
            if (ls >= endOffset || ls > buffer.length) {
                markDone()
                return
            }
            val lineEnd = lineEndOf(ls)
            val noCr = lineEnd > ls && buffer[lineEnd - 1] == '\r'
            this.contentEnd = if (noCr) lineEnd - 1 else lineEnd
            this.wsEnd = if (lineEnd < buffer.length) lineEnd + 1 else -1
            this.lineStart = ls
            this.nextLineStart = if (lineEnd < buffer.length) lineEnd + 1 else Int.MAX_VALUE
            if (wsEnd > 0) this.wsPending = true else this.wsPending = false

            if (clipped && contentEnd <= skipThrough) {
                // Whole content lies before the restart point — skip the line
                // without segmenting it; the trailing newline is invisible anyway.
                ls = nextLineStart
                continue
            }
            val line = buffer.subSequence(ls, contentEnd)
            val raw = segmenter.spans(line)
            spans = if (clipped) {
                val relSkip = skipThrough - ls
                var i = 0
                while (i < raw.size && raw[i].end <= relSkip) i++
                val shifted = raw.drop(i).map {
                    if (it.start < relSkip) Span(it.type, relSkip, it.end) else it
                }
                // Tokens that end exactly at the clip point were dropped; a span
                // fully inside the skipped region cannot occur because end > skip.
                shifted.filter { it.end > it.start }
            } else {
                raw
            }
            spanIndex = 0
            if (spans.isNotEmpty() || wsPending) return
            // Degenerate: no tokens and no newline (end of buffer) — done.
            markDone()
            return
        }
    }

    /**
     * Pops the next token out of the prepared queue, walking forward through
     * pending newlines and further lines. Bounded: each iteration either emits
     * a token, marks done, or advances to the next line — never re-enters itself.
     */
    private fun emitNext() {
        while (true) {
            if (done) {
                tokenType = null
                return
            }
            if (spanIndex < spans.size) {
                emit(spans[spanIndex++])
                return
            }
            if (wsPending) {
                wsPending = false
                set(wsEnd - 1 >= 0 && buffer[wsEnd - 1] == '\n', LogSmithTokenTypes.WS, contentEnd, wsEnd)
                if (tokenType == null) {
                    done = true
                } else {
                    return
                }
                continue
            }
            if (nextLineStart >= endOffset) {
                done = true
                tokenType = null
                return
            }
            beginAt(nextLineStart, skipThrough = -1)
        }
    }

    private fun emit(span: Span) {
        // Spans are relative to the segmenter's line; translate to buffer offsets.
        set(true, span.type, lineStart + span.start, lineStart + span.end)
    }

    private fun set(fire: Boolean, type: IElementType, start: Int, end: Int) {
        if (!fire) {
            tokenType = null
            return
        }
        tokenType = type
        tokenStart = start
        tokenEnd = end
    }

    private fun markDone() {
        done = true
        spans = emptyList()
        spanIndex = 0
        wsPending = false
        tokenType = null
    }

    private fun lineEndOf(from: Int): Int {
        var i = from
        while (i < buffer.length && buffer[i] != '\n') i++
        return i
    }

    private fun lineStartOf(offset: Int): Int {
        var i = offset
        while (i > 0 && buffer[i - 1] != '\n') i--
        return i
    }

    companion object {
        const val LINE_START_STATE = 0
        const val MID_LINE_STATE = 1
    }
}
