package com.danilgorbunofff.logsmith

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
        val s = StatusText.of(null, disabled = false, colouringNote = null)
        assertEquals("Format: <unknown>", s.text)
        assertTrue(s.tooltip.contains("scanning"))
        assertFalse(s.warning)
    }

    @Test
    fun `matched state shows ratio`() {
        val s = StatusText.of(DetectionResult.Matched(logback), disabled = false, colouringNote = null)
        assertEquals("Format: Logback / Log4j 2 — matched 1,204 / 1,208 lines (99.7%)", s.text)
        assertFalse(s.warning)
    }

    @Test
    fun `no match is a visible warning that names the closest candidate`() {
        val s = StatusText.of(
            DetectionResult.NoMatch(FormatStats("Logback / Log4j 2", 3, 25), 25),
            disabled = false,
            colouringNote = null,
        )
        assertEquals(
            "Format: no format matched 25 lines — showing plain text (closest: Logback / Log4j 2, 12.0%)",
            s.text,
        )
        assertTrue(s.warning)
    }

    @Test
    fun `read failure is a visible warning`() {
        val s = StatusText.of(DetectionResult.Failed("Permission denied"), disabled = false, colouringNote = null)
        assertEquals("LogSmith could not read this file (Permission denied) — showing plain text", s.text)
        assertTrue(s.warning)
    }

    @Test
    fun `disabled state survives a late detection result`() {
        val s = StatusText.of(DetectionResult.Matched(logback), disabled = true, colouringNote = null)
        assertTrue(s.text.startsWith("Format: Logback / Log4j 2"))
        assertTrue(s.text.endsWith("— highlighting disabled for this file"))
    }

    @Test
    fun `size cap is stated`() {
        val s = StatusText.of(DetectionResult.Matched(logback), disabled = false, colouringNote = "colouring off for files over 5 MB")
        assertTrue(s.text.endsWith("— colouring off for files over 5 MB"))
    }
}
