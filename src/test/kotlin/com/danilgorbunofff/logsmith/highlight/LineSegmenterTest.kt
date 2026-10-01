package com.danilgorbunofff.logsmith.highlight

import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSegmenterTest {

    private fun seg(name: String) = LineSegmenter(BuiltinSniffers.byName.getValue(name))

    /** Type of the span covering [fragment], or null if no single span covers it exactly. */
    private fun typeOf(line: String, segmenter: LineSegmenter, fragment: String): IElementType? {
        val at = line.indexOf(fragment)
        if (at < 0) throw AssertionError("fragment not in line: $fragment")
        return segmenter.spans(line).firstOrNull { it.start == at && it.end == at + fragment.length }?.type
    }

    @Test
    fun `logback record - ts, thread, level, logger, message`() {
        val s = seg("Logback / Log4j 2")
        val line = "2024-01-01 12:00:00.123 [main] INFO  com.example.App - all good"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "2024-01-01 12:00:00.123"))
        assertEquals(LogSmithTokenTypes.THREAD, typeOf(line, s, "[main]"))
        assertEquals(LogSmithTokenTypes.LEVEL_INFO, typeOf(line, s, "INFO"))
        assertEquals(LogSmithTokenTypes.LOGGER, typeOf(line, s, "com.example.App"))
        assertEquals(LogSmithTokenTypes.MESSAGE, typeOf(line, s, "all"))
    }

    @Test
    fun `logback - level-like thread name stays THREAD and real level still colours`() {
        val s = seg("Logback / Log4j 2")
        val line = "2024-01-01 12:00:00.000 [http-error-retry-1] ERROR Boom"
        assertEquals(LogSmithTokenTypes.THREAD, typeOf(line, s, "[http-error-retry-1]"))
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, typeOf(line, s, "ERROR"))
    }

    @Test
    fun `monolog - bracketed ts, dotted level word splits`() {
        val s = seg("PHP / Laravel (Monolog)")
        val line = "[2024-01-01 12:00:00] production.ERROR: Something failed"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "[2024-01-01 12:00:00]"))
        assertEquals(LogSmithTokenTypes.LOGGER, typeOf(line, s, "production."))
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, typeOf(line, s, "ERROR"))
        assertEquals(LogSmithTokenTypes.MESSAGE, typeOf(line, s, "Something"))
    }

    @Test
    fun `dotnet console - INF bracket is LEVEL_INFO`() {
        val s = seg(".NET (Microsoft.Extensions.Logging)")
        val line = "2024-01-01 12:00:00.123456 +02:00 [INF] Application started"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "2024-01-01 12:00:00.123456 +02:00"))
        assertEquals(LogSmithTokenTypes.LEVEL_INFO, typeOf(line, s, "[INF]"))
    }

    @Test
    fun `dotnet serilog - fail prefix is LEVEL_ERROR`() {
        val s = seg(".NET (Microsoft.Extensions.Logging)")
        val line = "fail: MyApplication[0] Format the message"
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, typeOf(line, s, "fail"))
        assertEquals(LogSmithTokenTypes.MESSAGE, typeOf(line, s, "Format"))
    }

    @Test
    fun `gunicorn - three brackets`() {
        val s = seg("Django / gunicorn")
        val line = "[2024-01-01 12:00:00 +0000] [1234] [INFO] Sending signal"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "[2024-01-01 12:00:00 +0000]"))
        assertEquals(LogSmithTokenTypes.THREAD, typeOf(line, s, "[1234]"))
        assertEquals(LogSmithTokenTypes.LEVEL_INFO, typeOf(line, s, "[INFO]"))
    }

    @Test
    fun `jul simple formatter - ts then words`() {
        val s = seg("java.util.logging")
        val line = "Jan 01, 2024 12:00:00 PM com.foo.Bar doStuff"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "Jan 01, 2024 12:00:00 PM"))
        assertEquals(LogSmithTokenTypes.LOGGER, typeOf(line, s, "com.foo.Bar"))
    }

    @Test
    fun `juli - level, thread, message`() {
        val s = seg("java.util.logging")
        val line = "01-Jan-2024 12:00:00.123 INFO [main] Message"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "01-Jan-2024 12:00:00.123"))
        assertEquals(LogSmithTokenTypes.LEVEL_INFO, typeOf(line, s, "INFO"))
        assertEquals(LogSmithTokenTypes.THREAD, typeOf(line, s, "[main]"))
        // First word after the thread bracket carries the default LOGGER look.
        assertEquals(LogSmithTokenTypes.LOGGER, typeOf(line, s, "Message"))
    }

    @Test
    fun `nginx access - bracketed ts only, request as message`() {
        val s = seg("nginx / Apache access")
        val line = "127.0.0.1 - frank [10/Oct/2024:13:55:36 -0700] \"GET /apache_pb.gif HTTP/1.0\" 200 2326"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "[10/Oct/2024:13:55:36 -0700]"))
        assertEquals(LogSmithTokenTypes.MESSAGE, typeOf(line, s, "\"GET /apache_pb.gif HTTP/1.0\""))
    }

    @Test
    fun `nginx error - error ramp`() {
        val s = seg("nginx error")
        val line = "2024/01/01 12:00:00 [error] 123#456: upstring failed"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "2024/01/01 12:00:00"))
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, typeOf(line, s, "[error]"))
    }

    @Test
    fun `syslog - PRI and ts`() {
        val s = seg("syslog")
        val line = "<34>Jan 05 12:00:00 myhost myapp[123]: did a thing"
        assertEquals(LogSmithTokenTypes.TIMESTAMP, typeOf(line, s, "<34>Jan 05 12:00:00"))
    }

    @Test
    fun `jul continuation - SEVERE prefixes error`() {
        val s = seg("java.util.logging")
        val spans = s.spans("SEVERE: Something bad")
        assertEquals(2, spans.size)
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, spans[0].type)
        assertEquals("SEVERE", spans[0].text("SEVERE: Something bad").toString())
        assertEquals(LogSmithTokenTypes.MESSAGE, spans[1].type)
    }

    @Test
    fun `python continuation - default form prefixes warn`() {
        val s = seg("Python logging")
        val spans = s.spans("WARNING:root: watch out")
        assertEquals(LogSmithTokenTypes.LEVEL_WARN, spans[0].type)
        assertEquals("WARNING", spans[0].text("WARNING:root: watch out").toString())
    }

    @Test
    fun `logback continuation - stack frame stays generic`() {
        val s = seg("Logback / Log4j 2")
        val spans = s.spans("\tat com.example.Foo.bar(Foo.java:10)")
        assertEquals(1, spans.size)
        assertEquals(LogSmithTokenTypes.GENERIC, spans[0].type)
    }

    @Test
    fun `ramp - fatal and severe are error, debug and trace are debug, notice is warn`() {
        val s = seg("Plain timestamp")
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, typeOf("2024-01-01 12:00:00 FATAL x", s, "FATAL"))
        assertEquals(LogSmithTokenTypes.LEVEL_ERROR, typeOf("2024-01-01 12:00:00 SEVERE x", s, "SEVERE"))
        assertEquals(LogSmithTokenTypes.LEVEL_DEBUG, typeOf("2024-01-01 12:00:00 DEBUG x", s, "DEBUG"))
        assertEquals(LogSmithTokenTypes.LEVEL_DEBUG, typeOf("2024-01-01 12:00:00 TRACE x", s, "TRACE"))
        assertEquals(LogSmithTokenTypes.LEVEL_WARN, typeOf("2024-01-01 12:00:00 NOTICE x", s, "NOTICE"))
        assertEquals(LogSmithTokenTypes.LEVEL_WARN, typeOf("2024-01-01 12:00:00 WARN x", s, "WARN"))
    }

    @Test
    fun `unknown sniffer - whole line is generic`() {
        val s = LineSegmenter(null)
        val spans = s.spans("anything at all")
        assertEquals(1, spans.size)
        assertEquals(LogSmithTokenTypes.GENERIC, spans[0].type)
        assertEquals(0, spans[0].start)
        assertEquals("anything at all".length, spans[0].end)
    }

    @Test
    fun `no span is empty and spans cover the line`() {
        val s = seg("Logback / Log4j 2")
        val line = "2024-01-01 12:00:00.123 [main] INFO  com.example.App - all good"
        val spans = s.spans(line)
        var covered = 0
        for (span in spans) {
            assertTrue(span.end > span.start)
            assertEquals(covered, span.start)
            covered = span.end
        }
        assertEquals(line.length, covered)
    }
}
