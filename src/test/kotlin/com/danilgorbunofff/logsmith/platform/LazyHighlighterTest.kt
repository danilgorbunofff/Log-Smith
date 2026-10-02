package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithLazyHighlighter
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.highlight.LogSmithTokenTypes
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

/**
 * §5.3 (R5): visible-range highlighting. Nothing may lex more than a window of lines,
 * windows must tile the document exactly (no gaps, no overlaps, no overshoot past the
 * document end), and an edit must not re-lex what the user cannot see.
 */
class LazyHighlighterTest : BasePlatformTestCase() {

    private class CountingSniffer(private val inner: LogFormatSniffer) : LogFormatSniffer by inner {
        var calls = 0
        override fun matches(line: String): Boolean {
            calls++
            return inner.matches(line)
        }
    }

    private val sniffers = HashMap<String, CountingSniffer>()

    private companion object {
        /** Every level the charter colours, so a window walk really exercises each one. */
        val LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG")
    }

    private fun countingSniffer(name: String = "Logback / Log4j 2"): CountingSniffer =
        sniffers.getOrPut(name) { CountingSniffer(BuiltinSniffers.byName.getValue(name)) }

    private fun highlighterFor(
        document: Document,
        sniffer: CountingSniffer = countingSniffer(),
    ): LogSmithLazyHighlighter {
        val highlighter = LogSmithLazyHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            EditorColorsManager.getInstance().globalScheme,
        )
        highlighter.setEditor(object : HighlighterClient {
            override fun getProject() = this@LazyHighlighterTest.project
            override fun repaint(start: Int, end: Int) {}
            override fun getDocument() = document
        })
        highlighter.setText(document.immutableCharSequence)
        document.addDocumentListener(highlighter)
        return highlighter
    }

    private fun document(text: String): Document = EditorFactory.getInstance().createDocument(text)

    private fun logText(lines: Int): String = (0 until lines).joinToString("\n") { i ->
        val level = LEVELS[i % LEVELS.size]
        "2026-10-01 09:00:00.%03d [main] %-5s c.e.App - line $i".format(i % 1000, level)
    } + "\n"

    /** One token of a walk: where it starts, its type, and the attributes the editor would paint it with. */
    private class Token(val type: IElementType?, val start: Int, val attributes: TextAttributes)

    /** Walks from [offset] to the end, asserting contiguity, and returns the tokens. */
    private fun walkTyped(iterator: HighlighterIterator, length: Int): List<Token> {
        val tokens = ArrayList<Token>()
        var expected = iterator.getStart()
        while (!iterator.atEnd()) {
            val start = iterator.getStart()
            val end = iterator.getEnd()
            assertEquals("token must start where the previous ended", expected, start)
            assertTrue("token must advance", end >= start)
            assertTrue("token must stay inside the document", end <= length)
            tokens.add(Token(iterator.getTokenType(), start, iterator.getTextAttributes()))
            if (start == length && end == length) break // the empty last line at EOF
            expected = end
            iterator.advance()
        }
        assertEquals("the walk must end at the end of the document", length, expected)
        return tokens
    }

    private fun walk(iterator: HighlighterIterator, length: Int): List<TextAttributes> =
        walkTyped(iterator, length).map { it.attributes }

    fun `test a walk over the document is contiguous and coloured`() {
        val document = document(logText(2_000))
        val highlighter = highlighterFor(document)
        val tokens = walkTyped(highlighter.createIterator(0), document.textLength)
        assertTrue("a 2,000-line log must produce many tokens", tokens.size > 2_000)

        // §5.1: the parts the charter colours must really carry a colour, per token type.
        val byType = tokens.groupBy({ it.type }, { it.attributes })
        for (type in listOf(
            LogSmithTokenTypes.TIMESTAMP,
            LogSmithTokenTypes.THREAD,
            LogSmithTokenTypes.LEVEL_ERROR,
            LogSmithTokenTypes.LEVEL_WARN,
            LogSmithTokenTypes.LEVEL_DEBUG,
        )) {
            val painted = byType[type] ?: error("no $type token in 2,000 log lines")
            assertTrue("$type must be coloured", painted.all { it.foregroundColor != null })
        }
        // INFO, the logger and the message keep the editor's own look: a strict improvement,
        // never a downgrade, so they must not impose a colour of their own — which is exactly
        // what the platform resolves a key with no scheme entry to (see the agreement test).
        for (type in listOf(LogSmithTokenTypes.LEVEL_INFO, LogSmithTokenTypes.LOGGER, LogSmithTokenTypes.MESSAGE)) {
            val unpainted = byType[type] ?: error("no $type token in 2,000 log lines")
            assertTrue(
                "$type must keep the editor's default look",
                unpainted.all { it.foregroundColor == null && it.backgroundColor == null },
            )
        }
    }

    /**
     * The lazy highlighter stands in for the platform's own [LexerEditorHighlighter], so it must
     * resolve the very same tokens to the very same attributes — no colour added, none lost.
     */
    fun `test attributes agree with the platform highlighter`() {
        val text = logText(50)
        val document = document(text)
        val syntaxHighlighter = LogSmithSyntaxHighlighter(LineSegmenter(countingSniffer()))
        val mine = walkTyped(highlighterFor(document).createIterator(0), document.textLength)

        val platform = LexerEditorHighlighter(syntaxHighlighter, EditorColorsManager.getInstance().globalScheme)
        platform.setText(text)
        val theirs = LinkedHashMap<Int, TextAttributes>()
        val iterator = platform.createIterator(0)
        while (!iterator.atEnd()) {
            theirs[iterator.start] = iterator.textAttributes
            iterator.advance()
        }

        assertEquals(
            "both highlighters must emit the same token starts",
            theirs.keys.toList(),
            mine.map { it.start },
        )
        for (token in mine) {
            val expected = theirs.getValue(token.start)
            val actual = token.attributes
            assertEquals("foreground of ${token.type} at ${token.start}", expected.foregroundColor, actual.foregroundColor)
            assertEquals("background of ${token.type} at ${token.start}", expected.backgroundColor, actual.backgroundColor)
            assertEquals("font style of ${token.type} at ${token.start}", expected.fontType, actual.fontType)
        }
    }

    fun `test tokens beyond the first window still tile the document`() {
        val document = document(logText(1_500))
        val sniffer = countingSniffer()
        val highlighter = highlighterFor(document, sniffer)
        // Start past the first window: the chain must extend forward window by window.
        val offset = document.getLineStartOffset(900)
        val iterator = highlighter.createIterator(offset)
        assertEquals("a token must contain the requested offset", offset, iterator.getStart())
        sniffer.calls = 0
        val attributes = walk(iterator, document.textLength)
        assertTrue("deep walk must colour tokens", attributes.isNotEmpty())
        assertTrue("the walk may only lex from the offset onwards", sniffer.calls < 900)
        assertTrue(
            "and no more than the lines it walked, plus one window",
            sniffer.calls <= 600 + LogSmithLazyHighlighter.WINDOW_LINES,
        )
    }

    fun `test windows never touch more lines than the window size`() {
        val document = document(logText(5_000))
        val sniffer = countingSniffer()
        val highlighter = highlighterFor(document, sniffer)

        sniffer.calls = 0
        highlighter.createIterator(0).let { iterator -> while (!iterator.atEnd()) iterator.advance() }

        // Walking the whole document lexes the whole document — once, window by window.
        assertTrue("one pass may not re-lex lines", sniffer.calls <= 5_000 + 4 * LogSmithLazyHighlighter.WINDOW_LINES)
        assertTrue("the window cache must stay bounded",
            highlighter.cachedWindowCount <= LogSmithLazyHighlighter.MAX_CACHED_WINDOWS)
    }

    fun `test a keystroke does not re-lex the document`() {
        val lines = 20_000
        val document = document(logText(lines))
        val sniffer = countingSniffer()
        val highlighter = highlighterFor(document, sniffer)

        sniffer.calls = 0
        val offset = document.getLineStartOffset(lines / 2) + 30
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, "x") }
        assertTrue("an edit alone must not lex anything", sniffer.calls == 0)

        // What the user then sees is coloured from the window around the cursor onwards.
        val iterator = highlighter.createIterator(offset)
        var expected = iterator.getStart()
        var guard = 64
        var coloured = 0
        while (!iterator.atEnd() && guard-- > 0) {
            if (iterator.getTextAttributes().foregroundColor != null) coloured++
            assertEquals(expected, iterator.getStart())
            expected = iterator.getEnd()
            iterator.advance()
        }
        assertTrue("the screen around the cursor must still be coloured", coloured > 0)
        assertTrue("one screen of tokens must cost one window, not a document", sniffer.calls <= 2 * LogSmithLazyHighlighter.WINDOW_LINES)
        assertTrue("a 20,000-line document must never be re-lexed for a keystroke", sniffer.calls < lines / 4)
    }

    fun `test an edit near the top leaves the rest of the document correct`() {
        val document = document(logText(1_000))
        val highlighter = highlighterFor(document)
        val before = document.getLineStartOffset(600)
        walk(highlighter.createIterator(before), document.textLength)

        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "junk line\n") }
        assertEquals("the document must have grown", before + "junk line\n".length, document.getLineStartOffset(601))

        // The cached tail was built before the edit and is dropped, so the walk is rebuilt
        // from the current document and still tiles it exactly.
        walk(highlighter.createIterator(document.getLineStartOffset(600)), document.textLength)
    }

    fun `test line starts match the document line starts`() {
        for (text in listOf("", "a", "a\n", "a\nb", "a\n\n", logText(50))) {
            val document = document(text)
            val highlighter = highlighterFor(document)
            for (line in 0 until document.lineCount) {
                val start = document.getLineStartOffset(line)
                val iterator = highlighter.createIterator(start)
                if (document.textLength == 0) {
                    assertTrue("an empty document has no tokens", iterator.atEnd())
                    continue
                }
                assertEquals("iterator at line $line must start on the line", start, iterator.getStart())
            }
        }
    }

    fun `test an empty document yields no tokens`() {
        val document = document("")
        val sniffer = countingSniffer()
        val highlighter = highlighterFor(document, sniffer)
        val iterator = highlighter.createIterator(0)
        assertTrue(iterator.atEnd())
        assertEquals(0, iterator.getStart())
        assertEquals(0, iterator.getEnd())
        assertNull(iterator.getTokenType())
        assertTrue("an empty document must not be lexed", sniffer.calls == 0)
    }

    fun `test one enormous line is never segmented`() {
        val huge = "x".repeat(200_000)
        val document = document("$huge\nshort line\n")
        val sniffer = countingSniffer()
        val highlighter = highlighterFor(document, sniffer)

        sniffer.calls = 0
        val iterator = highlighter.createIterator(0)
        assertEquals(0, iterator.getStart())
        assertEquals("the window stops at the line after the long line", document.getLineStartOffset(1), iterator.getEnd())
        assertEquals(LogSmithTokenTypes.GENERIC, iterator.getTokenType())
        assertTrue("a line over the segmentation limit must not be read into the lexer", sniffer.calls == 0)
    }

    fun `test a long line inside a window does not break the rest`() {
        val lines = logText(400).lines().toMutableList().also { it.add(0, "y".repeat(100_000)) }
        val document = document(lines.joinToString("\n"))
        val highlighter = highlighterFor(document)
        val attributes = walk(highlighter.createIterator(0), document.textLength)
        assertTrue("the normal lines after a long line are still coloured", attributes.size > 400)
    }

    fun `test the stream stays correct after the document shrinks`() {
        val document = document(logText(600))
        val highlighter = highlighterFor(document)
        walk(highlighter.createIterator(0), document.textLength)

        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(0, document.getLineStartOffset(500)) }
        walk(highlighter.createIterator(0), document.textLength)
    }

    fun `test ids and attributes survive a colour scheme change`() {
        val document = document(logText(20))
        val highlighter = highlighterFor(document)
        highlighter.setColorScheme(EditorColorsManager.getInstance().globalScheme)
        assertTrue(walk(highlighter.createIterator(0), document.textLength).isNotEmpty())
    }

    private fun esc(vararg codes: Int) = codes.joinToString("", "\u001B[", "m")

    /** A record line the ramp colours, wrapped in the SGR sequences a terminal would send. */
    private fun colouredLine(colour: Int, level: String = "ERROR"): String =
        "${esc(colour)}2026-10-01 09:00:00.000 [main] $level c.e.App - boom${esc(0)}"

    fun `test ansi colour overrides the ramp and the escapes show no ink`() {
        val text = logText(3) + colouredLine(32) + "\n" + logText(3)
        val document = document(text)
        val highlighter = highlighterFor(document)
        val tokens = walkTyped(highlighter.createIterator(0), document.textLength)
        val scheme = EditorColorsManager.getInstance().globalScheme

        val escapes = tokens.filter { it.type == LogSmithTokenTypes.ANSI_ESCAPE }
        assertEquals("both sequences must be tokens of their own", 2, escapes.size)
        for (token in escapes) {
            assertEquals(
                "an escape sequence must paint no ink of its own",
                scheme.defaultBackground,
                token.attributes.foregroundColor,
            )
        }
        val error = tokens.first {
            it.type == LogSmithTokenTypes.LEVEL_ERROR && it.start > escapes.first().start
        }
        assertEquals("the log's own colour wins over the ramp", Color(0x00CD00), error.attributes.foregroundColor)
        // The uncoloured lines around it keep the plain ramp.
        assertTrue(
            "the lines around a coloured one must stay coloured",
            tokens.count { it.type == LogSmithTokenTypes.LEVEL_ERROR } > 1,
        )
    }

    fun `test ansi colour is applied inside a window that does not start at zero`() {
        val document = document(logText(300) + colouredLine(32) + "\n")
        val highlighter = highlighterFor(document)
        val line = document.lineCount - 2
        assertTrue(
            "the coloured line must sit past the first window",
            line >= LogSmithLazyHighlighter.WINDOW_LINES,
        )
        val tokens = walkTyped(highlighter.createIterator(document.getLineStartOffset(line)), document.textLength)

        assertEquals("the sequences must still be recognised", 2, tokens.count { it.type == LogSmithTokenTypes.ANSI_ESCAPE })
        val error = tokens.first { it.type == LogSmithTokenTypes.LEVEL_ERROR }
        assertEquals("the log's own colour wins over the ramp", Color(0x00CD00), error.attributes.foregroundColor)
    }
}
