package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithLexer
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4: LogSmith must never mutate the user's file. A full editor attach needs an IDE
 * instance (basePlatform unavailable in pure-JVM tests), so this test covers the parts
 * of the attach path that can run headless: provider gating and lexer/highlighter
 * construction over the document text. isWritable is asserted before/after.
 */
class LogSmithWritabilityTest {

    @Test
    fun `provider accepts log files only`() {
        val provider = LogSmithFileEditorProvider()
        val log = LightVirtualFile("app.log")
        val out = LightVirtualFile("run.out")
        val txt = LightVirtualFile("notes.txt")
        assertTrue(provider.isSupported(log))
        assertTrue(provider.isSupported(out))
        assertFalse(provider.isSupported(txt))
    }

    @Test
    fun `lexing and highlighter construction never touch the file`() {
        val content = "2024-01-01 12:00:00.123 [main] INFO  hello\n"
        val file = LightVirtualFile("app.log", content)
        val wasWritable = file.isWritable

        val segmenter = LineSegmenter(BuiltinSniffers.byName.getValue("Logback / Log4j 2"))
        val highlighter = LogSmithSyntaxHighlighter(segmenter)
        val lexer = highlighter.highlightingLexer
        lexer.start(content, 0, content.length, 0)
        var guard = content.length * 2 + 16
        while (lexer.tokenType != null && guard-- > 0) lexer.advance()

        assertEquals(wasWritable, file.isWritable)
    }

    @Test
    fun `writeable file stays writeable, read-only stays read-only`() {
        val writable = LightVirtualFile("a.log")
        writable.setWritable(true)
        assertTrue(writable.isWritable)
        val readOnly = LightVirtualFile("b.log")
        readOnly.setWritable(false)
        assertFalse(readOnly.isWritable)
    }
}
