package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.filter.ErrorNavigator
import com.danilgorbunofff.logsmith.filter.FilterState
import com.danilgorbunofff.logsmith.filter.LogSmithFilterBar
import com.danilgorbunofff.logsmith.filter.LogSmithFilterService
import com.danilgorbunofff.logsmith.filter.LogSmithStackFrameLink
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale

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

    /** The active filter; the default one folds nothing and matches everything. */
    var filter: FilterState = FilterState()
        private set

    internal var filterJob: LogSmithFilterService.Job? = null

    internal var factsJob: LogSmithFilterService.Job? = null

    internal var lastFilterNote: String? = null

    internal val foldRegions = ArrayList<FoldRegion>()

    private val frameLink = LogSmithStackFrameLink()

    val filterBar = LogSmithFilterBar { applyFilter(it) }

    @Volatile
    private var isDisposed = false

    private var listenersAttached = false

    val isDisabled: Boolean get() = file.getUserData(logSmithHighlightDisabled) == true

    val isHighlighting: Boolean get() = installer?.isInstalled == true

    fun start() {
        textEditor.editor.putUserData(SESSION_KEY, this)
        refresh()
        attachListeners()
        detection = project.getService(LogSmithDetectionService::class.java).detect(file, this, ::onResult)
        indexJob = project.getService(LogSmithLineIndexService::class.java).index(file, this, ::onIndexed)
    }

    /**
     * Fold changes must never happen inside a document write action (charter §5.5), so a
     * document change only schedules a re-filter on the EDT; folds are re-applied after.
     */
    private fun attachListeners() {
        if (listenersAttached) return
        listenersAttached = true
        val editor = textEditor.editor
        editor.addEditorMouseListener(frameLink)
        editor.addEditorMouseMotionListener(frameLink)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (filter.isDefault) return
                ApplicationManager.getApplication().invokeLater(
                    { if (!isDisposed && !filter.isDefault) applyFilter(filter) },
                    ModalityState.defaultModalityState(),
                ) { isDisposed }
            }
        }, this)
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
        strip.render(StatusText.of(result, isDisabled, lineIndex, lastFilterNote))
    }

    /** Applies a filter from the bar (or a document change): folds stay cleared until the plan returns. */
    internal fun applyFilter(state: FilterState) {
        if (isDisposed) return
        filter = state
        lastFilterNote = null
        filterJob?.dispose()
        filterJob = null
        clearFolds()
        if (!state.isDefault) {
            filterJob = project.getService(LogSmithFilterService::class.java).plan(file, textEditor.editor, state) { job, outcome ->
                onFilterOutcome(job, outcome)
            }
        }
        refresh()
    }

    internal fun onFilterOutcome(job: LogSmithFilterService.Job, outcome: LogSmithFilterService.Outcome) {
        if (filterJob !== job || job.cancelled) return
        filterJob = null
        when (outcome) {
            is LogSmithFilterService.Outcome.Folded -> {
                lastFilterNote = "filter hides %,d of %,d lines".format(Locale.US, outcome.hiddenLines, outcome.facts.lineCount)
                applyFolds(outcome)
            }
            is LogSmithFilterService.Outcome.Failed -> lastFilterNote = "filter could not be applied: ${outcome.reason}"
        }
        refresh()
    }

    /** Swaps the fold regions for [outcome]'s plan, verifying the document has not moved on. */
    private fun applyFolds(outcome: LogSmithFilterService.Outcome.Folded) {
        val editor = textEditor.editor
        val document = editor.document
        if (outcome.facts.documentStamp != document.modificationStamp) return
        val starts = outcome.facts.lineStarts
        editor.foldingModel.runBatchFoldingOperation {
            for (region in foldRegions) {
                if (region.isValid) editor.foldingModel.removeFoldRegion(region)
            }
            foldRegions.clear()
            for (run in outcome.plan) {
                val start = starts[run.firstLine]
                val end = if (run.lastLine + 1 < starts.size) starts[run.lastLine + 1] else document.textLength
                if (start >= end) continue
                val region = editor.foldingModel.addFoldRegion(
                    start, end, "… ${"%,d".format(Locale.US, run.lineCount)} hidden",
                ) ?: continue
                region.isExpanded = false
                foldRegions.add(region)
            }
        }
    }

    private fun clearFolds() {
        val editor = textEditor.editor
        editor.foldingModel.runBatchFoldingOperation {
            for (region in foldRegions) {
                if (region.isValid) editor.foldingModel.removeFoldRegion(region)
            }
            foldRegions.clear()
        }
    }

    /** F2 / Shift+F2: to the next (previous) ERROR record, wrapping at the ends (charter R12). */
    internal fun gotoError(forward: Boolean) {
        if (isDisposed) return
        val editor = textEditor.editor
        LogSmithFilterService.cachedFacts(file, editor.document)?.let { facts ->
            moveToError(facts, forward)
            return
        }
        factsJob?.dispose()
        val job = project.getService(LogSmithFilterService::class.java).ensureFacts(file, editor) { j, facts ->
            if (factsJob === j) moveToError(facts, forward)
        }
        factsJob = job
    }

    private fun moveToError(facts: LogSmithFilterService.LineFacts, forward: Boolean) {
        if (isDisposed) return
        val editor = textEditor.editor
        val document = editor.document
        if (facts.documentStamp != document.modificationStamp) return
        val from = document.getLineNumber(editor.caretModel.offset)
        val target = ErrorNavigator.next(facts.bytes, from, forward) ?: return
        val offset = document.getLineStartOffset(target)
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        val fold = editor.foldingModel.allFoldRegions.firstOrNull {
            it.startOffset <= offset && offset < it.endOffset && !it.isExpanded
        }
        if (fold != null) {
            editor.foldingModel.runBatchFoldingOperation { fold.setExpanded(true) }
        }
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
        isDisposed = true
        filterJob?.dispose()
        factsJob?.dispose()
        textEditor.editor.putUserData(SESSION_KEY, null)
    }

    companion object {
        val SESSION_KEY: Key<LogSmithEditorSession> = Key.create("LOGSMITH_EDITOR_SESSION")
    }
}
