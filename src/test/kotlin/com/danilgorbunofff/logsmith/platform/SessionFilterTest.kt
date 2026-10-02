package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.LogSmithEditorAttacher
import com.danilgorbunofff.logsmith.LogSmithEditorSession
import com.danilgorbunofff.logsmith.filter.FilterState
import com.danilgorbunofff.logsmith.filter.LogLevel
import com.danilgorbunofff.logsmith.filter.LogSmithStackFrames
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Filter wiring through the session: folds, notes, re-filtering, the F2 walk and frame resolution. */
class SessionFilterTest : BasePlatformTestCase() {

    private val logback = (1..50).joinToString("\n") { "2026-10-01 09:00:00.%03d [main] INFO  c.e.App - line $it".format(it) } + "\n"

    private val walk = listOf(
        "2026-10-01 09:00:00.001 [main] INFO  c.e.App - one",
        "2026-10-01 09:00:00.002 [main] INFO  c.e.App - two",
        "2026-10-01 09:00:00.003 [main] ERROR c.e.App - first",
        "2026-10-01 09:00:00.004 [main] INFO  c.e.App - four",
        "2026-10-01 09:00:00.005 [main] INFO  c.e.App - five",
        "2026-10-01 09:00:00.006 [main] INFO  c.e.App - six",
        "2026-10-01 09:00:00.007 [main] ERROR c.e.App - second",
        "2026-10-01 09:00:00.008 [main] INFO  c.e.App - eight",
    ).joinToString("\n")

    private fun attached(name: String, text: String): LogSmithEditorSession {
        val file = myFixture.configureByText(name, text).virtualFile
        LogSmithEditorAttacher.attachAll(FileEditorManager.getInstance(project), file)
        val session = myFixture.editor.getUserData(LogSmithEditorSession.SESSION_KEY)!!
        session.detection!!.awaitForTests()
        session.indexJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return session
    }

    private fun pump() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    private fun LogSmithEditorSession.applyFilterAndWait(state: FilterState) {
        applyFilter(state)
        filterJob?.awaitForTests()
        pump()
    }

    private fun LogSmithEditorSession.gotoErrorAndWait(forward: Boolean) {
        gotoError(forward)
        factsJob?.awaitForTests()
        pump()
    }

    fun `test filtering folds the hidden records and says so`() {
        val session = attached("app.log", logback)
        val editor = session.textEditor.editor
        session.applyFilterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        assertTrue(session.strip.text, session.strip.text.contains("filter hides 51 of 51 lines"))
        assertEquals(1, editor.foldingModel.allFoldRegions.size)
    }

    fun `test resetting to the default filter clears the folds`() {
        val session = attached("app.log", logback)
        val editor = session.textEditor.editor
        session.applyFilterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        assertFalse(editor.foldingModel.allFoldRegions.isEmpty())
        session.applyFilterAndWait(FilterState())
        assertTrue(editor.foldingModel.allFoldRegions.isEmpty())
        assertFalse(session.strip.text, session.strip.text.contains("filter hides"))
    }

    fun `test a text filter keeps matching records visible`() {
        val session = attached("app.log", logback)
        val document = session.textEditor.editor.document
        session.applyFilterAndWait(FilterState(text = "line 42"))
        assertTrue(session.strip.text, session.strip.text.contains("filter hides 50 of 51 lines"))
        val folds = session.textEditor.editor.foldingModel.allFoldRegions
        assertEquals(2, folds.size)
        val lower = folds.minBy { it.startOffset }
        val upper = folds.maxBy { it.startOffset }
        assertEquals(document.getLineStartOffset(41), lower.endOffset)
        assertEquals(document.getLineStartOffset(42), upper.startOffset)
    }

    fun `test editing the document refilters with the new line count`() {
        val session = attached("app.log", logback)
        session.applyFilterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = session.textEditor.editor.document
            document.insertString(document.textLength, "2026-10-01 09:00:00.999 [main] INFO  c.e.App - late\n")
        }
        session.applyFilterAndWait(session.filter)
        assertTrue(session.strip.text, session.strip.text.contains("filter hides 52 of 52 lines"))
    }

    fun `test F2 walks forward and backward between errors and wraps`() {
        val session = attached("walk.log", walk)
        val editor = session.textEditor.editor
        val lineOf = { offset: Int -> editor.document.getLineNumber(offset) }
        editor.caretModel.moveToOffset(editor.document.getLineStartOffset(1))
        session.gotoErrorAndWait(true)
        assertEquals(2, lineOf(editor.caretModel.offset))
        session.gotoError(true)
        assertEquals(6, lineOf(editor.caretModel.offset))
        session.gotoError(true)
        assertEquals("the walk must wrap around the file end", 2, lineOf(editor.caretModel.offset))
        session.gotoError(false)
        assertEquals(6, lineOf(editor.caretModel.offset))
    }

    fun `test F2 keeps working while folds hide the non-error lines`() {
        val session = attached("walk.log", walk)
        val editor = session.textEditor.editor
        session.applyFilterAndWait(FilterState(setOf(LogLevel.ERROR), ""))
        editor.caretModel.moveToOffset(editor.document.getLineStartOffset(1))
        session.gotoErrorAndWait(true)
        assertEquals(2, editor.document.getLineNumber(editor.caretModel.offset))
        assertTrue(session.strip.text, session.strip.text.contains("filter hides 6 of 8 lines"))
    }

    fun `test ctrl-click resolution walks relative to the log directory`() {
        val thing = myFixture.addFileToProject("src/Thing.kt", "class Thing\n").virtualFile
        val log = myFixture.addFileToProject("logs/app.log", "at ../src/Thing.kt:12\n").virtualFile
        val resolved = LogSmithStackFrames.resolve(project, log, "../src/Thing.kt")
        assertEquals(thing.path, resolved?.path)    }

    fun `test ctrl-click resolution falls back to the bare file name`() {
        val thing = myFixture.addFileToProject("src/deep/Only.kt", "class Only\n").virtualFile
        val log = myFixture.addFileToProject("logs/app.log", "x\n").virtualFile
        val resolved = LogSmithStackFrames.resolve(project, log, "Only.kt")
        assertEquals(thing.path, resolved?.path)
    }

    fun `test spanAt finds java kotlin and python frames`() {
        val java = LogSmithStackFrames.spanAt("\tat org.example.Thing.java:42", 6)!!
        assertEquals("org.example.Thing.java", java.path)
        assertEquals(42, java.line)
        assertEquals(0, java.column)
        val kotlin = LogSmithStackFrames.spanAt("at org.example.Thing.kt:12:7", 25)!!
        assertEquals("org.example.Thing.kt", kotlin.path)
        assertEquals(12, kotlin.line)
        assertEquals(6, kotlin.column)
        val python = LogSmithStackFrames.spanAt("  File \"src/app.py\", line 10, in handler", 10)!!
        assertEquals("src/app.py", python.path)
        assertEquals(10, python.line)
        assertNull(LogSmithStackFrames.spanAt("plain text line", 3))
    }
}
