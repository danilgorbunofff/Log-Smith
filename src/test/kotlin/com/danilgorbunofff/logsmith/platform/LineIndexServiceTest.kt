package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.LogSmithLineIndexService
import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The line index behind the status line (charter §5.3, R5): the whole document is indexed off
 * the EDT in document offsets, an over-large one is reported rather than swallowed, and nothing
 * is delivered after the editor is gone.
 */
class LineIndexServiceTest : BasePlatformTestCase() {

    private val logback = (1..50).joinToString("\n") { "2026-10-01 09:00:00.%03d [main] INFO  c.e.App - line $it".format(it) } + "\n"

    private fun service() = project.getService(LogSmithLineIndexService::class.java)

    fun `test the whole file is indexed and reported on the EDT`() {
        var outcome: LogSmithLineIndexService.Outcome? = null
        val job = service().index(logback, testRootDisposable) { outcome = it }
        job.awaitForTests()
        assertNull("the outcome must arrive on the EDT, not on the indexing thread", outcome)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val indexed = outcome as LogSmithLineIndexService.Outcome.Indexed
        assertEquals(51, indexed.index.lineCount)
        assertEquals(logback.length.toLong(), indexed.index.scannedChars)
        assertEquals(0L, indexed.index.startOfLine(0))
        assertEquals(logback.length.toLong(), indexed.index.startOfLine(51))
    }

    fun `test a file over the cap is refused with its size`() {
        var outcome: LogSmithLineIndexService.Outcome? = null
        val job = service().index(
            logback,
            testRootDisposable,
            maxChars = 16,
            maxLines = LineOffsetIndex.DEFAULT_MAX_LINES,
        ) { outcome = it }
        job.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val tooLarge = outcome as LogSmithLineIndexService.Outcome.TooLarge
        assertEquals(logback.length.toLong(), tooLarge.length)
        assertFalse("a refused file is not an error, it is a stated limit", job.cancelled)
    }

    fun `test a CRLF file is indexed in the document's coordinates`() {
        // The document normalizes CRLF to LF; the index must describe the document, not the
        // bytes on disk, or the live tail mistakes every CRLF file for a rewritten one.
        myFixture.configureByText("crlf.log", logback.replace("\n", "\r\n"))
        val document = myFixture.editor.document
        var outcome: LogSmithLineIndexService.Outcome? = null
        service().index(document.immutableCharSequence, testRootDisposable) { outcome = it }.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val indexed = outcome as LogSmithLineIndexService.Outcome.Indexed
        assertEquals(document.textLength.toLong(), indexed.index.scannedChars)
        assertEquals(document.lineCount, indexed.index.lineCount)
        for (line in 0 until document.lineCount) {
            assertEquals(document.getLineStartOffset(line).toLong(), indexed.index.startOfLine(line))
        }
    }

    fun `test no outcome is delivered after the editor is disposed`() {
        val parent = Disposer.newDisposable()
        var delivered = 0
        val job = service().index(logback, parent) { delivered++ }
        Disposer.dispose(parent)
        job.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(job.cancelled)
        assertEquals("no index may reach a disposed editor", 0, delivered)
    }
}
