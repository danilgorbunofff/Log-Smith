package com.danilgorbunofff.logsmith.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CancellationException

/** Bounded scanning, decoding, cap notes, and the §7.2 fixture files. */
class LogScannerTest {

    private val record = "2026-10-01 09:00:00.001 [main] INFO  c.e.App - ok"

    private fun scan(
        bytes: ByteArray,
        maxLines: Int = 1_000_000,
        maxBytes: Long = Long.MAX_VALUE,
    ): ScanResult = LogScanner.scan(ByteArrayInputStream(bytes), Charsets.UTF_8, maxLines, maxBytes)

    private fun scan(text: String, maxLines: Int = 1_000_000, maxBytes: Long = Long.MAX_VALUE) =
        scan(text.toByteArray(Charsets.UTF_8), maxLines, maxBytes)

    private fun matched(result: ScanResult): FormatStats =
        (result.result as? DetectionResult.Matched)?.stats ?: throw AssertionError("not matched: $result")

    // ------------------------------------------------------------ fixtures

    @Test
    fun `hibernate fixture is logback and fully explained`() {
        val stats = matched(scan(File("testdata/hibernate.log").readBytes()))
        assertEquals("Logback / Log4j 2", stats.formatName)
        assertEquals(29, stats.matched)
        assertEquals(29, stats.scanned)
        assertEquals("100.0%", stats.percentText)
    }

    @Test
    fun `docker ansi fixture is reported as no match, not silence`() {
        val result = scan(File("testdata/docker.log").readBytes())
        assertTrue(result.result is DetectionResult.NoMatch)
        assertEquals(7, (result.result as DetectionResult.NoMatch).scanned)
        assertTrue(result.complete)
    }

    // ------------------------------------------------------------ decoding

    @Test
    fun `crlf endings are matched`() {
        val stats = matched(scan("$record\r\n$record\r\n\tat a.B.c(B.java:1)\r\n"))
        assertEquals(3, stats.matched)
        assertEquals(3, stats.scanned)
    }

    @Test
    fun `utf8 bom is skipped so the first line still matches`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "$record\n$record\n".toByteArray()
        val stats = matched(scan(bytes))
        assertEquals(2, stats.matched)
    }

    @Test
    fun `utf16le with bom is decoded`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "$record\r\n$record\r\n".toByteArray(Charsets.UTF_16LE)
        val stats = matched(scan(bytes))
        assertEquals("Logback / Log4j 2", stats.formatName)
        assertEquals(2, stats.matched)
    }

    // ------------------------------------------------------------ caps

    @Test
    fun `a file of exactly the line cap is not reported as capped`() {
        val text = (1..200).joinToString("\n") { record } + "\n"
        val result = scan(text, maxLines = 200)
        assertTrue(result.complete)
        assertNull(matched(result).note)
    }

    @Test
    fun `one line past the line cap is reported as capped`() {
        val text = (1..201).joinToString("\n") { record } + "\n"
        val result = scan(text, maxLines = 200)
        assertFalse(result.complete)
        assertEquals("scan covered the first 200 lines", matched(result).note)
        assertEquals(200, matched(result).scanned)
    }

    @Test
    fun `byte cap note uses KB below one megabyte and drops the cut line`() {
        val line = "$record\n"
        val text = line.repeat(10)
        val cap = line.length * 3L + 5 // three whole lines plus a fragment of the fourth
        val result = scan(text, maxBytes = cap)
        assertFalse(result.complete)
        val stats = matched(result)
        assertEquals("the fragment must not be scored", 3, stats.scanned)
        assertEquals("scan stopped at the $cap-byte byte cap", stats.note)
        assertEquals("512 KB", LogScanner.formatBytes(512L * 1024))
        assertEquals("256 MB", LogScanner.formatBytes(256L * 1024 * 1024))
    }

    @Test
    fun `a file exactly at the byte cap is complete`() {
        val text = "$record\n$record\n"
        val result = scan(text, maxBytes = text.length.toLong())
        assertTrue(result.complete)
        assertNull(matched(result).note)
    }

    @Test
    fun `huge single line is truncated, not buffered whole`() {
        val text = record + " " + "x".repeat(LogScanner.MAX_LINE_CHARS * 4) + "\n" + record + "\n"
        val stats = matched(scan(text))
        assertEquals(2, stats.scanned)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is honoured`() {
        LogScanner.scan(ByteArrayInputStream("$record\n".toByteArray()), Charsets.UTF_8, 10, Long.MAX_VALUE) { true }
    }

    // ------------------------------------------------------------ rendering

    @Test
    fun `percent never rounds up to 100 while a line is unmatched`() {
        assertEquals("99.9%", FormatStats("x", 1_999_999, 2_000_000).percentText)
        assertEquals("100.0%", FormatStats("x", 5, 5).percentText)
        assertEquals("99.7%", FormatStats("x", 1204, 1208).percentText)
    }
}
