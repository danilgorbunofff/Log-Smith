package com.danilgorbunofff.logsmith.platform

import com.danilgorbunofff.logsmith.LogSmithEditorAttacher
import com.danilgorbunofff.logsmith.LogSmithEditorSession
import com.danilgorbunofff.logsmith.LogSmithLineIndexService
import com.danilgorbunofff.logsmith.StatusText
import com.danilgorbunofff.logsmith.highlight.LogSmithTokenTypes
import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import javax.swing.JLabel

/**
 * Day 9 R9, on a real file: a log being written to keeps moving while it is open. Appending on
 * disk grows the line count and the counts of the format that already won, the colouring
 * continues into the new lines, and an edit that rewrites the indexed prefix pauses the tail
 * with a visible reason instead of showing a count that is no longer true.
 *
 * The file has to exist on disk for an append to be an *external* change, so these tests write
 * into a real temp directory and hand its files to the editor (a light fixture file cannot be
 * appended to behind the platform's back).
 */
class LiveTailTest : BasePlatformTestCase() {

    /** 20 records; the trailing newline makes 21 lines, as the platform counts them. */
    private val head = (1..20).joinToString("\n") { record(it) } + "\n"

    private lateinit var dir: File

    private fun record(n: Int) = "2026-10-01 09:00:00.%03d [main] INFO  c.e.App - line $n".format(n)

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            if (this::dir.isInitialized) FileUtil.delete(dir)
        }
    }

    /** A real directory on disk: an appending process writes outside the VFS, so the file must exist. */
    private fun tempDir(): File =
        if (this::dir.isInitialized) dir
        else Files.createTempDirectory("logsmith-live-tail").toFile().also { dir = it }

    private fun attach(name: String, text: String): LogSmithEditorSession {
        val real = File(tempDir(), name)
        real.writeText(text)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(real.toPath())
        assertNotNull("no VFS entry for ${real.path}", file)
        myFixture.openFileInEditor(file!!)
        LogSmithEditorAttacher.attachAll(FileEditorManager.getInstance(project), file)
        val session = myFixture.editor.getUserData(LogSmithEditorSession.SESSION_KEY)
        assertNotNull("LogSmith did not attach to $name", session)
        settle(session!!)
        return session
    }

    private fun settle(session: LogSmithEditorSession) {
        session.detection?.awaitForTests()
        session.indexJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun lines(session: LogSmithEditorSession): Int =
        (session.lineIndex as LogSmithLineIndexService.Outcome.Indexed).index.lineCount

    private fun stats(session: LogSmithEditorSession) =
        (session.result as? DetectionResult.Matched)?.stats
            ?: throw AssertionError("expected a claimed format, got ${session.result}")

    /** Appends the way a logging process would: to the file on disk, then let the IDE notice. */
    private fun append(session: LogSmithEditorSession, text: String) {
        File(session.file.path).appendText(text)
        session.file.refresh(false, false)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun `test appended lines grow the count, the matched lines and the colouring`() {
        val session = attach("grow.log", head)
        assertEquals(21, lines(session))
        assertEquals(20, stats(session).matched)

        append(session, record(21) + "\n" + record(22) + "\n")

        assertEquals("the line index follows the file", 23, lines(session))
        val grown = stats(session)
        assertEquals(22, grown.matched)
        assertEquals(22, grown.scanned)
        assertTrue(session.strip.text, session.strip.text.endsWith("— 23 lines"))

        // Colours continue past the boundary: the appended record highlights as a record.
        val document = session.textEditor.editor.document
        val appended = document.text.lastIndexOf(record(22))
        assertTrue("the appended line is not in the document", appended > 0)
        val iterator = (session.textEditor.editor as EditorEx).highlighter.createIterator(appended)
        assertEquals(LogSmithTokenTypes.TIMESTAMP, iterator.getTokenType())
    }

    fun `test a line split across two writes is counted once`() {
        val session = attach("split.log", head)
        val appended = record(21)

        append(session, appended.substring(0, 12))
        assertEquals("an unterminated line is not a line yet", 21, lines(session))
        assertEquals(20, stats(session).matched)

        append(session, appended.substring(12) + "\n")
        assertEquals(22, lines(session))
        assertEquals(21, stats(session).matched)
    }

    fun `test editing the file pauses the tail instead of re-indexing it`() {
        val session = attach("edit.log", head)

        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("x")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("an edit must not trigger an index rebuild", 21, lines(session))
        assertTrue(session.strip.text, session.strip.text.contains("live tail paused: unsaved changes"))
        val label = session.strip.root.components.first() as JLabel
        assertNull("a paused tail is not a warning", label.icon)
    }

    fun `test an error appended to a live tail is still reachable with F2`() {
        val session = attach("nav.log", head)
        myFixture.editor.caretModel.moveToOffset(0)

        append(session, "2026-10-01 09:00:00.021 ERROR c.e.App - boom\n")
        settle(session)

        session.gotoError(forward = true)
        session.factsJob?.awaitForTests()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val editor = session.textEditor.editor
        assertEquals(
            "F2 does not reach the appended ERROR record",
            20,
            editor.document.getLineNumber(editor.caretModel.offset),
        )
    }

    fun `test an over-cap file states the tail limit instead of stalling silently`() {
        val session = attach("overcap.log", head)

        // The real cap is 16 M lines, so the capped outcome is handed to the session directly:
        // this is the state a file past the cap reaches, and it has to say so in the strip.
        session.onIndexed(
            LogSmithLineIndexService.Outcome.Indexed(LineOffsetIndex(maxLines = 2).apply { accept(head) }),
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(session.strip.text, session.strip.text.contains("(line index capped)"))
        assertTrue(
            session.strip.text,
            session.strip.text.endsWith("— ${StatusText.TAIL_UNAVAILABLE_NOTE}"),
        )
        val label = session.strip.root.components.first() as JLabel
        assertNull("a stated limit is not a warning", label.icon)
    }

    fun `test a clean append after an edit rebuilds and reports the whole file`() {
        val session = attach("recover.log", head)
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("x")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(
            "the edit must pause the tail before the save: '${session.strip.text}'",
            session.strip.text.contains("live tail paused"),
        )
        FileDocumentManager.getInstance().saveDocument(session.textEditor.editor.document)

        // The saved document no longer matches the indexed prefix, so this change cannot be an
        // append to it: the honest answer is a rebuild, and the count covers the whole file.
        append(session, record(21) + "\n")
        settle(session)

        assertEquals("x" + head + record(21) + "\n", session.textEditor.editor.document.text)
        assertEquals(22, lines(session))
        assertTrue(session.result is DetectionResult.Matched)
        // Whole file, not a count carried over from before the edit: the typed "x" now leads
        // line 1, so records 2..21 match and line 1 does not.
        assertEquals(20, stats(session).matched)
        assertEquals(21, stats(session).scanned)
        assertFalse(session.strip.text, session.strip.text.contains("live tail paused"))
        assertTrue(session.strip.text, session.strip.text.endsWith("— 22 lines"))
    }
}
