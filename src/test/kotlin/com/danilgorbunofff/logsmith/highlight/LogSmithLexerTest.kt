package com.danilgorbunofff.logsmith.highlight

import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM lexer checks: buffer coverage and restart correctness (no IDE needed). */
class LogSmithLexerTest {

    private fun lexer() = LogSmithLexer(LineSegmenter(BuiltinSniffers.byName.getValue("Logback / Log4j 2")))

    private data class Token(val type: String, val start: Int, val end: Int) {
        override fun toString() = "$type[$start,$end)"
    }

    private fun tokenize(text: String): List<Token> {
        val lexer = lexer()
        lexer.start(text, 0, text.length, 0)
        val result = ArrayList<Token>()
        var guard = text.length * 2 + 16
        while (lexer.tokenType != null && guard-- > 0) {
            val name = when (lexer.tokenType) {
                LogSmithTokenTypes.WS -> "WS"
                LogSmithTokenTypes.TIMESTAMP -> "TS"
                LogSmithTokenTypes.LEVEL_INFO -> "INFO"
                LogSmithTokenTypes.LEVEL_ERROR -> "ERROR"
                LogSmithTokenTypes.THREAD -> "THREAD"
                LogSmithTokenTypes.LOGGER -> "LOGGER"
                LogSmithTokenTypes.MESSAGE -> "MSG"
                LogSmithTokenTypes.GENERIC -> "GEN"
                else -> "OTHER"
            }
            result.add(Token(name, lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        assertTrue("lexer stopped early but not done", lexer.isExhausted())
        return result
    }

    private fun coverage(text: String) {
        val tokens = tokenize(text)
        var covered = 0
        for (token in tokens) {
            assertTrue("token ${token.type} out of order at ${token.start}", token.start >= covered)
            assertEquals("gap before token $token", covered, token.start)
            assertTrue("empty token $token", token.end > token.start)
            covered = token.end
        }
        assertEquals("coverage", text.length, covered)
    }

    @Test
    fun `every line and newline is covered`() {
        coverage("2024-01-01 12:00:00.123 [main] INFO  com.example.App - all good\n")
        coverage("2024-01-01 12:00:00.123 [main] INFO  x\r\n2024-01-01 12:00:00.124 [main] INFO  y\r\n")
        coverage("\n\n\n")
        coverage("one line, no newline")
        coverage("")
        coverage("2024-01-01 12:00:00.123 [main] INFO  a\n2024-01-01 12:00:00.124 [main] INFO  b")
    }

    @Test
    fun `stack frames and blanks are generic`() {
        val tokens = tokenize(
            "2024-01-01 12:00:00.123 [main] INFO  x\n" +
                "\tat com.example.Foo.bar(Foo.java:10)\n"
        )
        assertTrue(tokens.any { it.type == "GEN" && it.start > 34 })
    }

    @Test
    fun `restart mid buffer resumes without gaps`() {
        val text = "2024-01-01 12:00:00.123 [main] INFO  first line\n" +
            "2024-01-01 12:00:00.124 [main] INFO  second line\n" +
            "2024-01-01 12:00:00.125 [main] ERROR third line\n"
        val whole = tokenize(text)
        val secondLineStart = text.indexOf("2024-01-01 12:00:00.124")
        val lexer = lexer()
        lexer.start(text, secondLineStart, text.length, LogSmithLexer.LINE_START_STATE)
        val resumed = ArrayList<Token>()
        var guard = text.length * 2 + 16
        while (lexer.tokenType != null && guard-- > 0) {
            resumed.add(Token("ANY", lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        // The resumed run must re-emit the same token layout for lines 2-3.
        val expected = whole.filter { it.start >= secondLineStart }
        assertEquals(expected.size, resumed.size)
        for (i in expected.indices) {
            assertEquals(expected[i].start, resumed[i].start)
            assertEquals(expected[i].end, resumed[i].end)
        }
    }

    @Test
    fun `only the first token of a line reports the initial state`() {
        val text = "2024-01-01 12:00:00.123 [main] INFO  first\n\n2024-01-01 12:00:00.124 [main] INFO  second\n"
        val lexer = lexer()
        lexer.start(text, 0, text.length, 0)
        var guard = text.length * 2 + 16
        while (lexer.tokenType != null && guard-- > 0) {
            val atLineStart = lexer.tokenStart == 0 || text[lexer.tokenStart - 1] == '\n'
            val expected = if (atLineStart) LogSmithLexer.LINE_START_STATE else LogSmithLexer.MID_LINE_STATE
            assertEquals("state at ${lexer.tokenStart}", expected, lexer.state)
            lexer.advance()
        }
        assertEquals(LogSmithLexer.LINE_START_STATE, lexer.state)
    }

    @Test
    fun `restart mid line resumes without gaps`() {
        val text = "2024-01-01 12:00:00.123 [main] INFO  first\n"
        val lexer2 = lexer()
        lexer2.start(text, 30, text.length, LogSmithLexer.MID_LINE_STATE)
        var guard = 64
        var covered = 30
        while (lexer2.tokenType != null && guard-- > 0) {
            assertEquals(covered, lexer2.tokenStart)
            covered = lexer2.tokenEnd
            lexer2.advance()
        }
        assertEquals(text.length, covered)
    }

    @Test
    fun `empty range is exhausted`() {
        val lexer = lexer()
        lexer.start("abc", 0, 0, 0)
        assertEquals(null, lexer.tokenType)
        assertTrue(lexer.isExhausted())
    }
}
