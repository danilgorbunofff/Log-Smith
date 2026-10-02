package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.filter.FilterFoldPlan
import com.danilgorbunofff.logsmith.filter.FilterState
import com.danilgorbunofff.logsmith.filter.LogLineFacts
import com.danilgorbunofff.logsmith.filter.LogLevel
import com.danilgorbunofff.logsmith.filter.LogSmithFilterService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The filter service: fold planning, fact caching and cache invalidation on edit. */
class FilterServiceTest : BasePlatformTestCase() {

    private val logback = (1..50).joinToString("\n") { "2026-10-01 09:00:00.%03d [main] INFO  c.e.App - line $it".format(it) } + "\n"

    private fun service() = project.getService(LogSmithFilterService::class.java)

    private fun pump() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    fun `test plan folds non-matching records and reports the hidden count`() {
        val text = listOf(
            "2026-10-01 09:00:00.001 [main] INFO  c.e.App - start",
            "2026-10-01 09:00:00.002 [main] ERROR c.e.App - boom",
            "2026-10-01 09:00:00.003 [main] INFO  c.e.App - end",
        ).joinToString("\n")
        val file = myFixture.configureByText("app.log", text).virtualFile
        var outcome: LogSmithFilterService.Outcome? = null
        val job = service().plan(file, myFixture.editor, FilterState(setOf(LogLevel.ERROR), "")) { _, o -> outcome = o }
        job.awaitForTests()
        assertNull("the outcome must arrive on the EDT, not from the worker", outcome)
        pump()
        val folded = outcome as LogSmithFilterService.Outcome.Folded
        assertEquals(3, folded.facts.lineCount)
        assertEquals(listOf(FilterFoldPlan.FoldRun(0, 0), FilterFoldPlan.FoldRun(2, 2)), folded.plan)
        assertEquals(2, folded.hiddenLines)
        assertTrue("the ERROR record must be classified as an error", LogLineFacts.isError(folded.facts.bytes[1]))
        assertFalse(LogLineFacts.isError(folded.facts.bytes[0]))
    }

    fun `test facts are cached and re-served for an unchanged document`() {
        val file = myFixture.configureByText("app.log", logback).virtualFile
        val document = myFixture.editor.document
        var first: LogSmithFilterService.LineFacts? = null
        var second: LogSmithFilterService.LineFacts? = null
        service().ensureFacts(file, myFixture.editor) { _, f -> first = f }.awaitForTests()
        pump()
        val cached = first!!
        assertEquals("50 records plus the trailing line", 51, cached.lineCount)
        service().ensureFacts(file, myFixture.editor) { _, f -> second = f }.awaitForTests()
        pump()
        assertSame("an unchanged document must reuse the cached facts", cached, second)
    }

    fun `test editing the document invalidates the cached facts`() {
        val file = myFixture.configureByText("app.log", logback).virtualFile
        val document = myFixture.editor.document
        var first: LogSmithFilterService.LineFacts? = null
        service().ensureFacts(file, myFixture.editor) { _, f -> first = f }.awaitForTests()
        pump()
        assertNotNull(first)
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "extra line\n")
        }
        assertNull("a stamp change must invalidate the cache",
            LogSmithFilterService.cachedFacts(file, document))
    }
}
