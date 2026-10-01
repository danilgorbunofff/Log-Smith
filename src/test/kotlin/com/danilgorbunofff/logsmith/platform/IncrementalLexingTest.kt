package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * §5.3: typing in a log must not re-lex the whole document. LexerEditorHighlighter
 * restarts at the nearest token whose lexer state is the initial one and stops once
 * the new token stream converges with the old one; both need a line-start state of 0.
 */
class IncrementalLexingTest : BasePlatformTestCase() {

    private class CountingSniffer(private val inner: LogFormatSniffer) : LogFormatSniffer by inner {
        var calls = 0
        override fun matches(line: String): Boolean {
            calls++
            return inner.matches(line)
        }
    }

    fun `test one keystroke re-segments only the edited neighbourhood`() {
        val lines = 2_000
        val text = (0 until lines).joinToString("\n") { i ->
            "2026-10-01 09:00:00.%03d [main] INFO  c.e.App - line $i".format(i % 1000)
        } + "\n"
        val sniffer = CountingSniffer(BuiltinSniffers.byName.getValue("Logback / Log4j 2"))
        val document = EditorFactory.getInstance().createDocument(text)
        val highlighter = LexerEditorHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            EditorColorsManager.getInstance().globalScheme,
        )
        highlighter.setEditor(object : HighlighterClient {
            override fun getProject() = this@IncrementalLexingTest.project
            override fun repaint(start: Int, end: Int) {}
            override fun getDocument() = document
        })
        highlighter.setText(document.immutableCharSequence)
        document.addDocumentListener(highlighter)

        sniffer.calls = 0
        val offset = document.getLineStartOffset(lines / 2) + 30
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, "x") }

        assertTrue("re-segmented ${sniffer.calls} lines for one keystroke", sniffer.calls <= 5)
    }
}
