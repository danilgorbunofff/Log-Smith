package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.danilgorbunofff.logsmith.sniff.FormatStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every status-line state says something true and visible (§5.1). */
class StatusTextTest {

    private val logback = FormatStats("Logback / Log4j 2", 1204, 1208)

    @Test
    fun `pending state says detection is running`() {
        val s = StatusText.of(null, disabled = false, lineIndex = null)
        assertEquals("Format: <unknown>", s.text)
        assertTrue(s.tooltip.contains("scanning"))
        assertFalse(s.warning)
    }

    @Test
    fun `matched state shows ratio`() {
        val s = StatusText.of(DetectionResult.Matched(logback), disabled = false, lineIndex = null)
        assertEquals("Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%)", s.text)
        assertFalse(s.warning)
    }

    @Test
    fun `no match is a visible warning that names the closest candidate`() {
        val s = StatusText.of(
            DetectionResult.NoMatch(FormatStats("Logback / Log4j 2", 3, 25), 25),
            disabled = false,
            lineIndex = null,
        )
        assertEquals(
            "Format: no format matched 25 lines — showing plain text (closest: Logback / Log4j 2, 12.0%)",
            s.text,
        )
        assertTrue(s.warning)
    }

    @Test
    fun `read failure is a visible warning`() {
        val s = StatusText.of(DetectionResult.Failed("Permission denied"), disabled = false, lineIndex = null)
        assertEquals("LogSmith could not read this file (Permission denied) — showing plain text", s.text)
        assertTrue(s.warning)
    }

    @Test
    fun `disabled state survives a late detection result`() {
        val s = StatusText.of(DetectionResult.Matched(logback), disabled = true, lineIndex = null)
        assertTrue(s.text.startsWith("Format: Logback / Log4j 2"))
        assertTrue(s.text.endsWith("— highlighting disabled for this file"))
    }

    @Test
    fun `indexed file states its line count`() {
        val s = StatusText.of(
            DetectionResult.Matched(logback),
            disabled = false,
            lineIndex = StatusText.indexOf("a\nb\nc\n"),
        )
        assertEquals("Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%) — 4 lines", s.text)
        assertFalse("a line count is not a warning", s.warning)
    }

    @Test
    fun `capped index states the cap instead of a wrong count`() {
        val capped = LogSmithLineIndexService.Outcome.Indexed(
            LineOffsetIndex(maxLines = 2).apply { accept("a\nb\nc\n") },
        )
        val s = StatusText.of(DetectionResult.Matched(logback), disabled = false, lineIndex = capped)
        assertTrue(s.text.endsWith("— 2+ lines (line index capped)"))
    }

    @Test
    fun `unindexed file says why`() {
        val over = StatusText.of(DetectionResult.Matched(logback), disabled = false, lineIndex = LogSmithLineIndexService.Outcome.TooLarge(2L shl 30))
        assertTrue(over.text.endsWith("— not indexed: file is over 1 GB"))

        val failed = StatusText.of(
            DetectionResult.Matched(logback),
            disabled = false,
            lineIndex = LogSmithLineIndexService.Outcome.Failed("Permission denied"),
        )
        assertTrue(failed.text.endsWith("— line count unavailable: Permission denied"))
    }

    @Test
    fun `disabled note and line count both appear`() {
        val s = StatusText.of(
            DetectionResult.Matched(logback),
            disabled = true,
            lineIndex = StatusText.indexOf("a\nb\n"),
        )
        assertTrue(s.text.endsWith("— highlighting disabled for this file — 3 lines"))
    }

    @Test
    fun `a paused tail is stated next to the count it stopped moving`() {
        val withTail = StatusText.of(
            DetectionResult.Matched(logback),
            disabled = false,
            lineIndex = StatusText.indexOf("a\nb\n"),
            filterNote = "filter hides 1 of 3 lines",
            tailNote = "live tail paused: unsaved changes",
        )
        assertEquals(
            "Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%) — 3 lines" +
                " — live tail paused: unsaved changes — filter hides 1 of 3 lines",
            withTail.text,
        )
        assertFalse("a paused tail is not a warning", withTail.warning)
    }

    @Test
    fun `a running tail adds no note`() {
        val quiet = StatusText.of(
            DetectionResult.Matched(logback),
            disabled = false,
            lineIndex = StatusText.indexOf("a\nb\n"),
        )
        assertEquals("Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%) — 3 lines", quiet.text)
    }

    @Test
    fun `an uncapped index can be followed`() {
        assertTrue(StatusText.tailFollows(StatusText.indexOf("a\nb\n")))
    }

    @Test
    fun `an over-cap file cannot be followed`() {
        val capped = LogSmithLineIndexService.Outcome.Indexed(
            LineOffsetIndex(maxLines = 2).apply { accept("a\nb\nc\n") },
        )
        assertFalse("a capped index stops growing", StatusText.tailFollows(capped))
        assertFalse(
            "a file over the byte cap has no index at all",
            StatusText.tailFollows(LogSmithLineIndexService.Outcome.TooLarge(2L shl 30)),
        )
        assertFalse(
            "an unreadable file has no index at all",
            StatusText.tailFollows(LogSmithLineIndexService.Outcome.Failed("Permission denied")),
        )
    }

    @Test
    fun `an unfollowable file states the limit instead of a stalled count`() {
        val capped = LogSmithLineIndexService.Outcome.Indexed(
            LineOffsetIndex(maxLines = 2).apply { accept("a\nb\nc\n") },
        )
        val s = StatusText.of(
            DetectionResult.Matched(logback),
            disabled = false,
            lineIndex = capped,
            tailNote = StatusText.TAIL_UNAVAILABLE_NOTE,
        )
        assertEquals(
            "Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%)" +
                " — 2+ lines (line index capped) — live tail unavailable — reopen to refresh",
            s.text,
        )
        assertFalse("a stated limit is not a warning", s.warning)
    }
}
