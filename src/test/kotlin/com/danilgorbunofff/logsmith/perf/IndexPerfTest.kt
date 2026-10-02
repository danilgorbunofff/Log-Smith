package com.danilgorbunofff.logsmith.perf

import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import junit.framework.TestCase
import java.io.File

/**
 * §5.3 (R5) and §8.1: the CI perf job indexes a ~500 MB fixture
 * (`./gradlew generateTestData -PbigLogLines=3700000`, ~135 bytes a record); locally the
 * same file is whatever `generateTestData` last wrote — the default 4,200,000 records is
 * ~570 MB. Gated behind `-Plogsmith.perf=true`, because it needs that file on disk and takes
 * seconds rather than milliseconds. The gate is a plain early return: a JUnit-3 `TestCase`
 * reports an assumption violation as a failure, not a skip.
 */
class IndexPerfTest : TestCase() {

    fun `test a multi-hundred-megabyte log indexes in one streaming pass`() {
        if (System.getProperty("logsmith.perf") != "true") return
        val file = File("testdata/big.log")
        if (!file.isFile) return
        val megabytes = file.length() / 1e6
        var progress = 0L

        val started = System.nanoTime()
        val index = file.inputStream().use { stream ->
            LineOffsetIndex.build(stream, Charsets.UTF_8) { progress = it }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println(
            "PERF index: %.0f MB, %,d lines, %.2f s, %.0f MB/s, %.0f MB retained (%.1f%% of the file)".format(
                megabytes, index.lineCount, seconds, megabytes / seconds,
                index.retainedBytes / 1e6, 100.0 * index.retainedBytes / file.length(),
            ),
        )

        assertFalse("a file this size must not hit the line cap", index.capped)
        // Line density check derived from the fixture itself: this log averages ~124 bytes a
        // line, so anything below one line per 500 bytes means the index stopped early.
        assertTrue(
            "the fixture must be indexed in full (%,d lines in %,d bytes)".format(index.lineCount, file.length()),
            index.lineCount > file.length() / 500,
        )
        assertEquals("progress must have tracked the whole file", index.scannedChars, progress)
        assertEquals("the ASCII fixture is one character per byte", file.length(), index.scannedChars)
        assertTrue("indexing must stay far below a minute", seconds < 60)
        // §5.3 (R5): memory is bounded by the index plus the window cache, never by file size.
        assertTrue(
            "the index must stay small (%.0f MB for a %.0f MB file)".format(index.retainedBytes / 1e6, megabytes),
            index.retainedBytes < file.length() / 4,
        )
    }
}
