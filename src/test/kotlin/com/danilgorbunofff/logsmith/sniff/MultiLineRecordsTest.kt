package com.danilgorbunofff.logsmith.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-shaped multi-line records: a correctly formatted file must score at or near
 * 100%, and continuation lines alone must never claim a format.
 */
class MultiLineRecordsTest {

    private fun result(vararg lines: String): DetectionResult =
        FormatScorer(BuiltinSniffers.all).apply { lines.forEach { onLine(it) } }.result()

    private fun matched(vararg lines: String): FormatStats =
        (result(*lines) as? DetectionResult.Matched)?.stats ?: throw AssertionError("not matched: ${result(*lines)}")

    @Test
    fun `dotnet console formatter two-line records`() {
        val stats = matched(
            "info: Microsoft.Hosting.Lifetime[14]",
            "      Now listening on: http://localhost:5000",
            "info: Microsoft.Hosting.Lifetime[0]",
            "      Application started. Press Ctrl+C to shut down.",
            "fail: Microsoft.AspNetCore.Server.Kestrel[13]",
            "      Connection id \"0HMV\", Request id \"0HMV:00000001\": An unhandled exception was thrown by the application.",
            "      System.InvalidOperationException: boom",
            "         at MyApp.Controllers.HomeController.Index() in /src/HomeController.cs:line 21",
        )
        assertEquals(".NET (Microsoft.Extensions.Logging)", stats.formatName)
        assertEquals(stats.scanned, stats.matched)
    }

    @Test
    fun `python traceback body is explained`() {
        val stats = matched(
            "2026-10-01 09:14:00,123 - myapp.worker - ERROR - job failed",
            "Traceback (most recent call last):",
            "  File \"/app/worker.py\", line 42, in run",
            "    result = compute(payload)",
            "  File \"/app/compute.py\", line 7, in compute",
            "    raise ValueError(\"bad payload\")",
            "ValueError: bad payload",
            "2026-10-01 09:14:01,002 - myapp.worker - INFO - retrying",
        )
        assertEquals("Python logging", stats.formatName)
        assertEquals(stats.scanned, stats.matched)
    }

    @Test
    fun `python default basicConfig form is a record format`() {
        val stats = matched(
            "WARNING:root:disk almost full",
            "INFO:myapp.db:connected",
            "ERROR:myapp.db:query failed",
        )
        assertEquals("Python logging", stats.formatName)
        assertEquals(3, stats.matched)
    }

    @Test
    fun `jul with stack trace is explained`() {
        val stats = matched(
            "Oct 01, 2026 3:30:44 PM com.example.Server start",
            "SEVERE: startup failed",
            "java.lang.IllegalStateException: port in use",
            "\tat com.example.Server.start(Server.java:42)",
            "\tat com.example.Main.main(Main.java:9)",
            "Caused by: java.net.BindException: Address already in use",
            "\t... 2 more",
        )
        assertEquals("java.util.logging", stats.formatName)
        assertEquals(stats.scanned, stats.matched)
    }

    @Test
    fun `continuation lines alone never claim a format`() {
        val outcome = result(
            "    key: value",
            "    other: thing",
            "      nested: true",
            "\tat looks.Like.a(Frame.java:1)",
        )
        assertTrue("got $outcome", outcome is DetectionResult.NoMatch)
        assertEquals(null, (outcome as DetectionResult.NoMatch).closest)
    }

    @Test
    fun `below-threshold scan reports its closest candidate`() {
        val outcome = result(
            "2026-10-01 09:00:00.001 [main] INFO c.e.App - one real record",
            "free text",
            "more free text",
            "even more free text",
        )
        assertTrue(outcome is DetectionResult.NoMatch)
        val closest = (outcome as DetectionResult.NoMatch).closest
        assertEquals("Logback / Log4j 2", closest?.formatName)
        assertEquals("25.0%", closest?.percentText)
    }
}
