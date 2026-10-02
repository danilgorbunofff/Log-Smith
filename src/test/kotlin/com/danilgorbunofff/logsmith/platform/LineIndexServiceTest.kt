package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.LogSmithLineIndexService
import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.IOException
import java.io.InputStream

/**
 * The line index behind the status line (charter §5.3, R5): the whole file is indexed off
 * the EDT, an over-large or unreadable file is reported rather than swallowed, and nothing
 * is delivered after the editor is gone.
 */
class LineIndexServiceTest : BasePlatformTestCase() {

    private val logback = (1..50).joinToString("\n") { "2026-10-01 09:00:00.%03d [main] INFO  c.e.App - line $it".format(it) } + "\n"

    private fun service() = project.getService(LogSmithLineIndexService::class.java)

    fun `test the whole file is indexed and reported on the EDT`() {
        val file = myFixture.configureByText("app.log", logback).virtualFile
        var outcome: LogSmithLineIndexService.Outcome? = null
        val job = service().index(file, testRootDisposable) { outcome = it }
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
        val file = myFixture.configureByText("app.log", logback).virtualFile
        var outcome: LogSmithLineIndexService.Outcome? = null
        val job = service().index(
            file,
            testRootDisposable,
            maxBytes = 16,
            maxLines = LineOffsetIndex.DEFAULT_MAX_LINES,
        ) { outcome = it }
        job.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val tooLarge = outcome as LogSmithLineIndexService.Outcome.TooLarge
        assertEquals(logback.length.toLong(), tooLarge.bytes)
        assertFalse("a refused file is not an error, it is a stated limit", job.cancelled)
    }

    fun `test an unreadable file is reported, never swallowed`() {
        val broken = object : LightVirtualFile("broken.log", logback) {
            override fun getInputStream(): InputStream = throw IOException("permission denied")
        }
        var outcome: LogSmithLineIndexService.Outcome? = null
        val job = service().index(broken, testRootDisposable) { outcome = it }
        job.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val failed = outcome as LogSmithLineIndexService.Outcome.Failed
        assertEquals("permission denied", failed.reason)
    }

    fun `test no outcome is delivered after the editor is disposed`() {
        val file = myFixture.configureByText("app.log", logback).virtualFile
        val parent = Disposer.newDisposable()
        var delivered = 0
        val job = service().index(file, parent) { delivered++ }
        Disposer.dispose(parent)
        job.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(job.cancelled)
        assertEquals("no index may reach a disposed editor", 0, delivered)
    }
}
