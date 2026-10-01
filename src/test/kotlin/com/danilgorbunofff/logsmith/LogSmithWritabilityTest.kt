package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

/**
 * R4 (charter §5.2): LogSmith must never change whether a file can be edited.
 * The attach path runs for real here — session, status strip, detection and the
 * highlighter swap — and writability is asserted before and after, then the file
 * is edited and saved through the normal document APIs.
 */
class LogSmithWritabilityTest : BasePlatformTestCase() {

    private val sample = "2026-10-01 09:00:00.001 [main] INFO  c.e.App - started\n" +
        "2026-10-01 09:00:00.002 [main] ERROR c.e.App - boom\n" +
        "\tat com.example.App.main(App.java:10)\n"

    private fun attach(file: VirtualFile): LogSmithEditorSession {
        val manager = FileEditorManager.getInstance(project)
        LogSmithEditorAttacher.attachAll(manager, file)
        val session = myFixture.editor.getUserData(LogSmithEditorSession.SESSION_KEY)
        assertNotNull("LogSmith did not attach to ${file.name}", session)
        session!!.detection!!.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return session
    }

    fun `test supported extensions`() {
        assertTrue(LogSmithEditorAttacher.hasSupportedExtension("app.log"))
        assertTrue(LogSmithEditorAttacher.hasSupportedExtension("RUN.OUT"))
        assertFalse(LogSmithEditorAttacher.hasSupportedExtension("notes.txt"))
        assertFalse(LogSmithEditorAttacher.hasSupportedExtension("log"))
    }

    fun `test attach keeps the file writable and editable`() {
        val file = myFixture.configureByText("app.log", sample).virtualFile
        val document = myFixture.editor.document
        assertTrue(file.isWritable)
        assertTrue(document.isWritable)

        val session = attach(file)

        assertTrue("file became read-only", file.isWritable)
        assertTrue("document became read-only", document.isWritable)
        assertTrue(session.result is DetectionResult.Matched)
        assertTrue("highlighter not installed", session.isHighlighting)

        myFixture.editor.caretModel.moveToOffset(document.textLength)
        myFixture.type("2026-10-01 09:00:00.003 [main] INFO  c.e.App - typed by the test\n")
        FileDocumentManager.getInstance().saveDocument(document)
        assertTrue(String(file.contentsToByteArray(), file.charset).contains("typed by the test"))
        assertTrue(file.isWritable)
    }

    fun `test read-only file stays read-only`() {
        val file = myFixture.configureByText("ro.log", sample).virtualFile
        com.intellij.openapi.application.WriteAction.run<Exception> { file.isWritable = false }
        attach(file)
        assertFalse(file.isWritable)
        com.intellij.openapi.application.WriteAction.run<Exception> { file.isWritable = true }
    }

    fun `test status strip is laid out with a real size`() {
        val file = myFixture.configureByText("app.log", sample).virtualFile
        val session = attach(file)
        val root = session.strip.root
        root.setSize(800, root.preferredSize.height)
        layoutTree(root)
        val label = findLabel(root)
        assertNotNull(label)
        assertTrue("status label has no on-screen size: ${label!!.bounds}", label.width > 0 && label.height > 0)
        assertTrue(label.text, label.text.startsWith("Format: Logback / Log4j 2 — matched 3 / 3 lines"))
    }

    fun `test non-log files are not attached`() {
        val txt = myFixture.configureByText("notes.txt", sample).virtualFile
        assertEmpty(LogSmithEditorAttacher.attachAll(FileEditorManager.getInstance(project), txt))
        assertNull(myFixture.editor.getUserData(LogSmithEditorSession.SESSION_KEY))
    }

    fun `test binary out file is not supported`() {
        val bytes = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 1, 2, 0, 0, 0)
        val aOut = myFixture.tempDirFixture.createFile("a.out")
        com.intellij.openapi.application.WriteAction.run<Exception> { aOut.setBinaryContent(bytes) }
        assertFalse("binary a.out must not be treated as a log", LogSmithEditorAttacher.isSupported(aOut))
    }

    private fun layoutTree(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach { layoutTree(it) }
        }
    }

    private fun findLabel(c: Component): JLabel? {
        if (c is JLabel) return c
        if (c is Container) c.components.forEach { child -> findLabel(child)?.let { return it } }
        return null
    }
}
