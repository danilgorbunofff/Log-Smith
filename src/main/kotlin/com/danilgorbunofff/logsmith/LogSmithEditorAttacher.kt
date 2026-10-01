package com.danilgorbunofff.logsmith

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

/**
 * Attaches LogSmith to the platform's own text editor for `.log` / `.out` files —
 * attach, never replace (charter Day 1-2). The status strip goes under the editor via
 * [FileEditorManager.addBottomComponent]. Files the platform does not open in a text
 * editor (binary `a.out`, files too large for the text editor) are never touched.
 */
class LogSmithEditorAttacher : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        attachAll(source, file)
    }

    /** Split editors and re-opened tabs show up here rather than in [fileOpened]. */
    override fun selectionChanged(event: FileEditorManagerEvent) {
        val file = event.newFile ?: return
        attachAll(event.manager, file)
    }

    companion object {
        /** `.log` and `.out` ship by default; `.txt` stays opt-in until settings exist (Day 10). */
        private val SUPPORTED_EXTENSIONS = setOf("log", "out")

        fun hasSupportedExtension(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

        fun isSupported(file: VirtualFile): Boolean =
            !file.isDirectory && file.isValid && hasSupportedExtension(file.name) && !file.fileType.isBinary

        /** Idempotent: attaches a session to every text editor of [file] that has none yet. */
        fun attachAll(manager: FileEditorManager, file: VirtualFile): List<LogSmithEditorSession> {
            if (!isSupported(file)) return emptyList()
            val attached = ArrayList<LogSmithEditorSession>()
            for (fileEditor in manager.getEditors(file)) {
                if (fileEditor !is TextEditor) continue
                if (fileEditor.editor.getUserData(LogSmithEditorSession.SESSION_KEY) != null) continue
                val session = LogSmithEditorSession(manager.project, file, fileEditor)
                if (!Disposer.tryRegister(fileEditor, session)) continue
                manager.addBottomComponent(fileEditor, session.strip.root)
                session.start()
                attached += session
            }
            return attached
        }
    }
}
