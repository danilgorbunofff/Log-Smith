package com.danilgorbunofff.logsmith.live

import com.danilgorbunofff.logsmith.ansi.AnsiText
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.danilgorbunofff.logsmith.sniff.FormatStats
import com.danilgorbunofff.logsmith.sniff.LogScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The pure half of the live tail: growth classification, appended-line counting, count merging. */
class LiveTailTest {

    private val logback = BuiltinSniffers.byName.getValue("Logback / Log4j 2")
    private val record = "2026-10-01 09:00:00.001 [main] INFO  c.e.App - ok"

    /** `sgr("32m")` → `ESC[32m`; the caller supplies the final byte. */
    private fun sgr(params: String) = "${AnsiText.ESC}[$params"

    private fun tail(): TailClassifier = TailClassifier(logback)

    // ------------------------------------------------------------ growth classification

    @Test
    fun `an insert exactly at the consumed prefix is an append`() {
        val growth = TailGrowth.classify(covered = 18, offset = 18, oldLength = 0, newLength = 11)
        assertEquals(TailGrowth.Appended(18, 29), growth)
    }

    @Test
    fun `an insert before the consumed prefix is untrusted`() {
        assertEquals(
            TailGrowth.Untrusted,
            TailGrowth.classify(covered = 18, offset = 4, oldLength = 0, newLength = 3)
        )
    }

    @Test
    fun `a replacement at the consumed prefix is untrusted`() {
        // Same-length rewrite: the prefix the index describes is gone even though the length
        // did not change, so the growth must not be mistaken for an append.
        assertEquals(
            TailGrowth.Untrusted,
            TailGrowth.classify(covered = 14, offset = 14, oldLength = 14, newLength = 4)
        )
    }

    @Test
    fun `an append while the prefix is untrusted stays untrusted`() {
        assertEquals(
            TailGrowth.Untrusted,
            TailGrowth.classify(covered = -1, offset = 29, oldLength = 0, newLength = 4)
        )
    }

    @Test
    fun `an empty insert is classified, not filtered`() {
        // The session skips empty ranges; classify reports what happened and never special-cases it.
        assertEquals(TailGrowth.Appended(7, 7), TailGrowth.classify(covered = 7, offset = 7, oldLength = 0, newLength = 0))
    }

    // ------------------------------------------------------------ appended-line counting

    @Test
    fun `nothing is counted before a line is complete`() {
        val tail = tail()
        tail.accept("$record\n")
        assertEquals(1, tail.stats()!!.scanned)

        tail.accept("2026-10-01 09:00:00.002 [main] INFO  c.e.App - partial")
        assertEquals(1, tail.stats()!!.scanned)
    }

    @Test
    fun `a line split across two feeds is joined and counted once`() {
        val tail = tail()
        tail.accept("2026-10-01 09:00:00.001 INF")
        assertNull("no terminator yet, so nothing to count", tail.stats())

        tail.accept("O  c.e.App - ok\n")
        val stats = tail.stats()!!
        assertEquals(1, stats.matched)
        assertEquals(1, stats.scanned)
    }

    @Test
    fun `appended records extend the counts`() {
        val tail = tail()
        tail.accept("$record\n$record\n")
        val stats = tail.stats()!!
        assertEquals("Logback / Log4j 2", stats.formatName)
        assertEquals(2, stats.matched)
        assertEquals(2, stats.scanned)
    }

    @Test
    fun `blank appended lines are never counted`() {
        val tail = tail()
        tail.accept("\n\n   \n")
        assertNull(tail.stats())

        tail.accept("$record\n")
        assertEquals(1, tail.stats()!!.scanned)
    }

    @Test
    fun `a CRLF-terminated record counts once`() {
        val tail = tail()
        tail.accept("$record\r\n")
        val stats = tail.stats()!!
        assertEquals(1, stats.matched)
        assertEquals(1, stats.scanned)
    }

    @Test
    fun `a coloured appended record is counted as the plain text would be`() {
        val tail = tail()
        tail.accept("2026-10-01 09:00:00.001 ${sgr("32m")}INFO${sgr("0m")}  c.e.App - ok\n")
        val stats = tail.stats()!!
        assertEquals(1, stats.matched)
        assertEquals(1, stats.scanned)
    }

    @Test
    fun `appended noise counts as a scanned line without claiming it`() {
        // The format is not re-derived: a tail that stops being explained shows a falling
        // percentage instead of a quiet re-sniff.
        val tail = tail()
        tail.accept("$record\n")
        tail.accept("no format explains this line\n")
        val stats = tail.stats()!!
        assertEquals(1, stats.matched)
        assertEquals(2, stats.scanned)
        assertEquals("50.0%", stats.percentText)
    }

    @Test
    fun `a line longer than the per-line cap is counted once`() {
        // Mirrors LogScanner.LineSource: an over-long line is truncated to the cap and still
        // counted as one line, however many feeds delivered it.
        val tail = tail()
        tail.accept("z".repeat(LogScanner.MAX_LINE_CHARS + 1))
        tail.accept("z".repeat(5_000))
        tail.accept("z\n")
        val stats = tail.stats()!!
        assertEquals(0, stats.matched)
        assertEquals(1, stats.scanned)
    }

    @Test
    fun `an over-long record keeps the prefix that makes it a record`() {
        val tail = tail()
        tail.accept("2026-10-01 09:00:00.001 INFO  ")
        tail.accept("payload ".repeat(200_000))
        tail.accept("tail\n")
        val stats = tail.stats()!!
        assertEquals(1, stats.matched)
        assertEquals(1, stats.scanned)
    }

    // ------------------------------------------------------------ merging with the base scan

    private val base = FormatStats("Logback / Log4j 2", matched = 10, scanned = 10, note = "capped")

    @Test
    fun `merged counts add the tail to the base`() {
        val merged = mergeStats(base, FormatStats("Logback / Log4j 2", 3, 4))
        assertEquals(13, merged.matched)
        assertEquals(14, merged.scanned)
        assertEquals("Logback / Log4j 2", merged.formatName)
        assertEquals("capped", merged.note)
    }

    @Test
    fun `an absent tail leaves the base untouched`() {
        assertSame(base, mergeStats(base, null))
    }

    @Test
    fun `a matched result grows with the tail`() {
        val merged = mergeResult(DetectionResult.Matched(base), FormatStats("Logback / Log4j 2", 1, 1))
        assertEquals(DetectionResult.Matched(FormatStats("Logback / Log4j 2", 11, 11, "capped")), merged)
    }

    @Test
    fun `an absent tail leaves the result as it was`() {
        val result = DetectionResult.Matched(base)
        assertSame(result, mergeResult(result, null))
    }

    @Test
    fun `an unclaimed file keeps its verdict`() {
        // A tail exists only where a format was claimed, so a NoMatch file never grows counts:
        // its "matched N lines" is a statement of the first scan, while the line count still moves.
        val noMatch = DetectionResult.NoMatch(null, 7, null)
        assertSame(noMatch, mergeResult(noMatch, FormatStats("Logback / Log4j 2", 1, 1)))

        val failed = DetectionResult.Failed("unreadable")
        assertSame(failed, mergeResult(failed, FormatStats("Logback / Log4j 2", 1, 1)))
    }
}
