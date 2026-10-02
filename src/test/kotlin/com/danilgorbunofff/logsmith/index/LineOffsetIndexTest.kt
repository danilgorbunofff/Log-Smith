package com.danilgorbunofff.logsmith.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** Pure-JVM index checks: line semantics, chunk independence, cancellation, caps (no IDE). */
class LineOffsetIndexTest {

    private fun index(text: String, chunkChars: Int = LineOffsetIndex.CHUNK_CHARS): LineOffsetIndex =
        LineOffsetIndex.build(StringReader(text), chunkChars = chunkChars)

    @Test
    fun `line semantics match the platform document`() {
        // Document: "" is 1 empty line, a trailing '\n' opens an empty last line.
        assertEquals(1, index("").lineCount)
        assertEquals(1, index("a").lineCount)
        assertEquals(2, index("a\n").lineCount)
        assertEquals(2, index("a\nb").lineCount)
        assertEquals(3, index("a\n\n").lineCount)
        assertEquals(4, index("a\n\n\nb").lineCount)
        assertEquals(0L, index("a\nb").startOfLine(0))
        assertEquals(2L, index("a\nb").startOfLine(1))
        assertEquals(3L, index("a\nb").startOfLine(2))
        assertEquals(3L, index("a\nb").scannedChars)
    }

    @Test
    fun `chunk size never changes the result`() {
        val text = "first line\n\n2024-01-01 12:00:00.123 [main] INFO  x\r\nlast line, no newline"
        val whole = index(text, text.length)
        for (chunk in 1..8) {
            val chunked = index(text, chunk)
            assertEquals("lineCount at chunk=$chunk", whole.lineCount, chunked.lineCount)
            assertEquals("scannedChars at chunk=$chunk", whole.scannedChars, chunked.scannedChars)
            for (line in 0..whole.lineCount) {
                assertEquals("startOfLine($line) at chunk=$chunk", whole.startOfLine(line), chunked.startOfLine(line))
            }
            for (offset in 0..text.length) {
                assertEquals(
                    "lineOf($offset) at chunk=$chunk",
                    whole.lineOf(offset.toLong()),
                    chunked.lineOf(offset.toLong()),
                )
            }
        }
    }

    @Test
    fun `crlf keeps the carriage return inside the line`() {
        val crlf = index("a\r\nb\r\nc")
        assertEquals(3, crlf.lineCount)
        assertEquals(3L, crlf.startOfLine(1))
        assertEquals(6L, crlf.startOfLine(2))
        assertEquals(0, crlf.lineOf(1))
        assertEquals(1, crlf.lineOf(3))
        assertEquals(2, crlf.lineOf(7))
    }

    @Test
    fun `lineOf finds the line containing an offset at every boundary`() {
        val text = "abc\nde\nf"
        val index = index(text)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 2, 2), (0..text.length).map { index.lineOf(it.toLong()) })
        assertEquals("past the end lands on the last line", 2, index.lineOf(99L))
        assertEquals(0, index.lineOf(-5L))
    }

    @Test
    fun `accept can be fed the same text in pieces`() {
        val index = LineOffsetIndex()
        index.accept("ab")
        index.accept("c\nd")
        index.accept("\ne")
        assertEquals(3, index.lineCount)
        assertEquals(7L, index.scannedChars)
        assertEquals(6L, index.startOfLine(2))
        assertEquals("line starts must survive being fed piece by piece", listOf(0L, 4L, 6L),
            (0..2).map { index.startOfLine(it) })
    }

    @Test
    fun `progress is reported per chunk`() {
        val seen = ArrayList<Long>()
        val text = (1..50).joinToString("\n") { "line $it" } + "\n"
        LineOffsetIndex.build(StringReader(text), chunkChars = 16, onProgress = { seen.add(it) })
        assertTrue("progress must be reported", seen.isNotEmpty())
        assertEquals("progress is the scanned character count", seen.sorted(), seen)
        assertEquals(text.length.toLong(), seen.last())
    }

    @Test
    fun `cancellation aborts the build`() {
        val checks = AtomicInteger()
        val reader = RepeatingReader("2026-10-01 09:00:00.000 [main] INFO  c.e.App - line\n", 200_000)
        try {
            LineOffsetIndex.build(reader, chunkChars = 1024, isCancelled = { checks.incrementAndGet() > 2 })
            throw AssertionError("build should have thrown")
        } catch (expected: CancellationException) {
            assertTrue("must have been cancelled mid-build", checks.get() > 2)
        }
    }

    @Test
    fun `maxLines stops the scan and marks the index capped`() {
        val index = LineOffsetIndex(maxLines = 3)
        index.accept("a\nb\nc\nd\ne\n")
        assertTrue(index.capped)
        assertEquals(3, index.lineCount)
        assertEquals("scanned stops right after the line that hit the cap", 6L, index.scannedChars)
        assertEquals(6L, index.startOfLine(index.lineCount))
        index.accept("ignored\n")
        assertEquals("a capped index ignores further input", 3, index.lineCount)
    }

    @Test
    fun `build honours the byte order mark charset`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "a\nb".toByteArray(StandardCharsets.UTF_8)
        val bomIndex = LineOffsetIndex.build(utf8.inputStream(), StandardCharsets.ISO_8859_1)
        assertEquals(2, bomIndex.lineCount)
        assertEquals(3L, bomIndex.scannedChars)

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\nb".toByteArray(StandardCharsets.UTF_16LE)
        val utf16Index = LineOffsetIndex.build(utf16.inputStream(), StandardCharsets.UTF_8)
        assertEquals(2, utf16Index.lineCount)
        assertEquals(3L, utf16Index.scannedChars)
    }

    @Test
    fun `five million lines index correctly`() {
        val lines = 5_000_000
        val line = "2026-10-01 09:00:00.000 [main] INFO  c.e.App - line\n"
        val index = LineOffsetIndex.build(RepeatingReader(line, lines))
        assertEquals(lines + 1, index.lineCount)
        assertEquals(line.length.toLong() * lines, index.scannedChars)
        assertEquals(line.length.toLong() * (lines - 1), index.startOfLine(lines - 1))
        assertEquals(lines - 1, index.lineOf(line.length.toLong() * lines - 2))
        assertFalse(index.capped)
    }

    @Test
    fun `memory tracks the line count not the file size`() {
        val lines = 2_000_000
        val line = "2026-10-01 09:00:00.000 [main] INFO  c.e.App - line\n"
        val wideLine = line.trimEnd('\n').repeat(4) + "\n"

        val narrow = LineOffsetIndex.build(RepeatingReader(line, lines))
        val wide = LineOffsetIndex.build(RepeatingReader(wideLine, lines))

        // 2,000,000 newlines end with an empty line, as in a Document.
        assertEquals(lines + 1, narrow.lineCount)
        assertEquals(lines + 1, wide.lineCount)
        // Four times the bytes for the same line count must cost exactly the same.
        assertEquals(narrow.retainedBytes, wide.retainedBytes)
        // 8 bytes a slot, and at most 2x the line count because the array doubles.
        assertTrue("index must stay within 2x its line count", narrow.retainedBytes <= 16L * narrow.lineCount)
        // How far under the file this lands depends on the line length; the real 500 MB
        // fixture ratio is asserted by the gated IndexPerfTest instead.
        assertTrue("index must stay far below the file size", narrow.retainedBytes < wideLine.length.toLong() * lines / 8)
    }

    @Test
    fun `toString names the line count`() {
        assertEquals("LineOffsetIndex(lines=5)", index("a\nb\nc\nd\n").toString())
    }

    /** Reads [line] exactly [times] times, then EOF — a file-sized stream without a file-sized string. */
    private class RepeatingReader(private val line: String, private val times: Int) : Reader() {

        private var served = 0
        private var position = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (served >= times) return -1
            var written = 0
            while (written < len && served < times) {
                val take = minOf(len - written, line.length - position)
                line.toCharArray(cbuf, off + written, position, position + take)
                written += take
                position += take
                if (position == line.length) {
                    position = 0
                    served++
                }
            }
            return written
        }

        override fun close() {}
    }
}
