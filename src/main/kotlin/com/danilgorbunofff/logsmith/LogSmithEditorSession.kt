package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/**
 * LogSmith's state for one platform [TextEditor]: the status strip, the highlighter
 * installer, the detection job and the line index. The editor itself is never replaced or
 * wrapped (axis B: the file stays exactly as editable as without the plugin). Disposed with
 * the editor, which cancels detection and the index build.
 */
class LogSmithEditorSession(
    private val project: Project,
    val file: VirtualFile,
    val textEditor: TextEditor,
) : Disposable {

    val strip = LogSmithStatusStrip()
    private val installer: LogSmithHighlighterInstaller? =
        (textEditor.editor as? EditorEx)?.let { LogSmithHighlighterInstaller(project, it, file) }

    var result: DetectionResult? = null
        private set

    var detection: LogSmithDetectionService.Job? = null
        private set

    /** The line index for this file, or why there is none; null while it is still building. */
    var lineIndex: LogSmithLineIndexService.Outcome? = null
        private set

    internal var indexJob: LogSmithLineIndexService.Job? = null
        private set

    val isDisabled: Boolean get() = file.getUserData(logSmithHighlightDisabled) == true

    val isHighlighting: Boolean get() = installer?.isInstalled == true

    fun start() {
        textEditor.editor.putUserData(SESSION_KEY, this)
        refresh()
        detection = project.getService(LogSmithDetectionService::class.java).detect(file, this, ::onResult)
        indexJob = project.getService(LogSmithLineIndexService::class.java).index(file, this, ::onIndexed)
    }

    internal fun onResult(result: DetectionResult) {
        this.result = result
        refresh()
    }

    internal fun onIndexed(outcome: LogSmithLineIndexService.Outcome) {
        lineIndex = outcome
        refresh()
    }

    /** Re-applies highlighting and status text from current state. */
    fun refresh() {
        installer?.update(result, isDisabled)
        strip.render(StatusText.of(result, isDisabled, lineIndex))
    }


    /** Flips the per-file switch and refreshes every LogSmith editor showing this file. */
    fun toggle() {
        file.putUserData(logSmithHighlightDisabled, !isDisabled)
        for (fileEditor in FileEditorManager.getInstance(project).getEditors(file)) {
            (fileEditor as? TextEditor)?.editor?.getUserData(SESSION_KEY)?.refresh()
        }
        refresh() // in case this editor is not (or no longer) registered with the manager
    }

    override fun dispose() {
        textEditor.editor.putUserData(SESSION_KEY, null)
    }

    companion object {
        val SESSION_KEY: Key<LogSmithEditorSession> = Key.create("LOGSMITH_EDITOR_SESSION")
    }
}
