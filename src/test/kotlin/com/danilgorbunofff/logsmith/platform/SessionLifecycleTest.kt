package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.LogSmithDetectionService
import com.danilgorbunofff.logsmith.LogSmithEditorAttacher
import com.danilgorbunofff.logsmith.LogSmithEditorSession
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Toggle action, disposal/cancellation, and "never silent" for an unmatched file. */
class SessionLifecycleTest : BasePlatformTestCase() {

    private val logback = (1..50).joinToString("\n") { "2026-10-01 09:00:00.%03d [main] INFO  c.e.App - line $it".format(it) } + "\n"

    private fun attached(name: String, text: String): LogSmithEditorSession {
        val file = myFixture.configureByText(name, text).virtualFile
        LogSmithEditorAttacher.attachAll(FileEditorManager.getInstance(project), file)
        val session = myFixture.editor.getUserData(LogSmithEditorSession.SESSION_KEY)!!
        session.detection!!.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return session
    }

    fun `test toggle action is visible and switches highlighting off and on`() {
        val session = attached("app.log", logback)
        assertTrue(session.isHighlighting)
        val action = ActionManager.getInstance().getAction("LogSmith.ToggleHighlight")

        val shown = myFixture.testAction(action)
        assertTrue("toggle action must be visible in a LogSmith editor", shown.isEnabledAndVisible)
        assertEquals("Disable LogSmith highlighting for this file", shown.text)
        assertFalse(session.isHighlighting)
        assertTrue(session.strip.text, session.strip.text.endsWith("highlighting disabled for this file"))

        // A late detection result must not paint over the disabled state.
        session.onResult(session.result!!)
        assertFalse(session.isHighlighting)
        assertTrue(session.strip.text.endsWith("highlighting disabled for this file"))

        // testAction returns the presentation computed before performing: it offers "Enable".
        val again = myFixture.testAction(action)
        assertEquals("Enable LogSmith highlighting for this file", again.text)
        assertTrue(session.isHighlighting)
        assertFalse(session.strip.text.contains("disabled"))
    }

    fun `test toggle action is hidden outside LogSmith editors`() {
        myFixture.configureByText("notes.txt", logback)
        val action = ActionManager.getInstance().getAction("LogSmith.ToggleHighlight")
        assertFalse(myFixture.testAction(action).isEnabledAndVisible)
    }

    fun `test unmatched file shows a visible no-match message`() {
        val session = attached("prose.log", "just some words\nmore words\nnothing structured here\n")
        assertTrue(session.result is DetectionResult.NoMatch)
        assertTrue(session.strip.text, session.strip.text.startsWith("Format: no format matched"))
        assertFalse(session.isHighlighting)
    }

    fun `test disposing the parent cancels delivery`() {
        val file = myFixture.configureByText("app.log", logback).virtualFile
        val parent = Disposer.newDisposable()
        var delivered = 0
        val job = project.getService(LogSmithDetectionService::class.java).detect(file, parent) { delivered++ }
        Disposer.dispose(parent)
        job.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(job.cancelled)
        assertEquals("no result may reach a disposed editor", 0, delivered)
    }

    fun `test session is dropped when the editor is closed`() {
        val session = attached("app.log", logback)
        val editor = session.textEditor.editor
        Disposer.dispose(session)
        assertNull(editor.getUserData(LogSmithEditorSession.SESSION_KEY))
        assertTrue(session.detection!!.cancelled)
    }
}
