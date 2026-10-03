package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.filter.ErrorNavigator
import com.danilgorbunofff.logsmith.filter.FilterState
import com.danilgorbunofff.logsmith.filter.LogLineFacts
import com.danilgorbunofff.logsmith.filter.LogSmithFilterBar
import com.danilgorbunofff.logsmith.filter.LogSmithFilterService
import com.danilgorbunofff.logsmith.filter.LogSmithStackFrameLink
import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.danilgorbunofff.logsmith.live.TailClassifier
import com.danilgorbunofff.logsmith.live.TailGrowth
import com.danilgorbunofff.logsmith.live.mergeResult
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale

/**
 * LogSmith's state for one platform [TextEditor]: the status strip, the highlighter
 * installer, the detection job, the line index and the live tail. The editor itself is never
 * replaced or wrapped (axis B: the file stays exactly as editable as without the plugin).
 * Disposed with the editor, which cancels detection and the index build.
 *
 * A file being written to keeps being followed (charter §8 Day 9): appended text extends the
 * line index and the counts of the format that already won, so the line count moves and the
 * colours keep coming while a program logs. No file is read for this — the platform reloads
 * the document, and the inserted text is the appended text.
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

    /** A re-filter for document changes is already queued; further changes ride on it. */
    private var refilterQueued = false

    /**
     * The last facts the filter produced, kept only while every document change since has been a
     * pure append: the next plan then classifies just the appended lines instead of the file.
     */
    private var appendBase: LogSmithFilterService.LineFacts? = null

    /**
     * Characters of the document the line index and the tail have consumed, or -1 while no
     * prefix is trusted. Only ever assigned from the index's own [LineOffsetIndex.scannedChars]
     * (never incremented independently), so it cannot drift from what the index describes.
     */
    private var covered: Int = -1

    /** Counts appended lines against the format that won; null when nothing claimed the file. */
    private var tail: TailClassifier? = null

    /** The first scan's verdict, kept so appended lines can grow its counts rather than replace them. */
    private var baseResult: DetectionResult? = null

    /** Why the line count stopped moving, if it did; shown in the status line. */
    private var tailNote: String? = null

    /** True while no trusted prefix is being followed, so a document change cannot grow anything. */
    private var tailOff: Boolean = true

    /**
     * Set when the document changed in some way other than a pure append while the line index
     * was still being built from an earlier snapshot. The snapshot's prefix is then no longer
     * the document's prefix, so the finished index cannot simply be caught up.
     */
    private var rewrittenWhileIndexing: Boolean = false

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
        startIndex()
    }

    /** Indexes the document as it is now; changes from here on are judged against this snapshot. */
    private fun startIndex() {
        rewrittenWhileIndexing = false
        indexJob = project.getService(LogSmithLineIndexService::class.java)
            .index(editorDocument.immutableCharSequence, this, ::onIndexed)
    }

    /**
     * Fold changes must never happen inside a document write action (charter §5.5), so a
     * document change only schedules a re-filter on the EDT; folds are re-applied after.
     *
     * The tail is the exception: it must consume appended text *while the change is being
     * applied*, because the line index has to advance with the document or its offsets would
     * describe text that has already moved. Consuming text is arithmetic only; everything that
     * touches the editor or Swing is deferred by [refreshLater].
     */
    private fun attachListeners() {
        if (listenersAttached) return
        listenersAttached = true
        val editor = textEditor.editor
        editor.addEditorMouseListener(frameLink)
        editor.addEditorMouseMotionListener(frameLink)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!isDisposed && !tailOff) {
                    when (val growth = TailGrowth.classify(covered, event.offset, event.oldLength, event.newLength)) {
                        is TailGrowth.Appended -> grow(growth.from, growth.to)
                        TailGrowth.Untrusted -> onUntrustedChange()
                    }
                } else if (!isDisposed && lineIndex == null) {
                    // The index is still building from an older snapshot: an append is caught up
                    // when it lands, anything else means the snapshot's prefix has moved.
                    val append = event.oldLength == 0 && event.offset + event.newLength == event.document.textLength
                    if (!append) rewrittenWhileIndexing = true
                }
                if (!(event.oldLength == 0 && event.offset + event.newLength == event.document.textLength)) {
                    appendBase = null
                }
                if (filter.isDefault || refilterQueued) return
                // A burst of changes — a program logging fast — is answered by one re-filter.
                refilterQueued = true
                ApplicationManager.getApplication().invokeLater(
                    {
                        refilterQueued = false
                        if (!isDisposed && !filter.isDefault) applyFilter(filter)
                    },
                    ModalityState.defaultModalityState(),
                ) { isDisposed }
            }
        }, this)
    }

    internal fun onResult(result: DetectionResult) {
        baseResult = result
        // Always fresh: a scan that already covered appended lines and a tail that also counted
        // them would count them twice, so the tail only ever counts what happens from here on.
        tail = tailFor(result)
        this.result = mergeResult(result, tail?.stats())
        refresh()
    }

    internal fun onIndexed(outcome: LogSmithLineIndexService.Outcome) {
        lineIndex = outcome
        val index = indexedIndex()
        covered = coveredFrom(index?.scannedChars)
        // No index, a capped one, or offsets beyond what an Int can address: nothing to follow.
        tailOff = !StatusText.tailFollows(outcome) || covered < 0
        tailNote = if (tailOff) StatusText.TAIL_UNAVAILABLE_NOTE else null
        if (!tailOff) {
            if (rewrittenWhileIndexing) onUntrustedChange() else catchUp()
        }
        refresh()
    }

    /** The format that won, as a counter for the lines appended after it was claimed. */
    private fun tailFor(result: DetectionResult): TailClassifier? =
        (result as? DetectionResult.Matched)
            ?.stats?.formatName
            ?.let { BuiltinSniffers.byName[it] }
            ?.let { TailClassifier(it) }

    // ------------------------------------------------------------------ live tail

    /**
     * Consumes `[from, to)` of the document into the line index and into the tail's counts.
     * The range comes from the classified document event and [covered] always equals what the
     * index has consumed, so the index is only ever extended, never re-based.
     */
    private fun grow(from: Int, to: Int) {
        val index = indexedIndex() ?: return
        val text = editorDocument.immutableCharSequence
        if (from < 0 || from >= to || to > text.length) return
        index.accept(text, from, to)
        if (index.capped) {
            // The status line already says "N+ lines (line index capped)"; the tail note says
            // the count will not move again, and counting further lines would only make a
            // number the index can no longer support look exact.
            tailOff = true
            covered = -1
            tailNote = StatusText.TAIL_UNAVAILABLE_NOTE
            refreshLater()
            return
        }
        covered = coveredFrom(index.scannedChars)
        tailNote = null
        tail?.accept(text.subSequence(from, to))
        result = baseResult?.let { mergeResult(it, tail?.stats()) }
        refreshLater()
    }

    /** Catches the tail up with a document that moved while no change of its own was seen. */
    private fun catchUp() {
        if (covered < 0) return
        val length = editorDocument.textLength
        when {
            length > covered -> grow(covered, length)
            length < covered -> onUntrustedChange()
        }
    }

    /**
     * The document changed in a way no append explains, so the consumed prefix describes text
     * that is no longer there. A file being edited keeps its own text (axis B): the tail pauses
     * and says so rather than re-indexing on every keystroke. A clean rewrite — a rotation, a
     * truncation, a new file at the same path — is answered by rebuilding, because that is a
     * different file and no amount of patching makes the old counts true.
     */
    private fun onUntrustedChange() {
        covered = -1
        if (!FileDocumentManager.getInstance().isDocumentUnsaved(editorDocument)) {
            restart()
            return
        }
        if (tailNote == STALE_TAIL_NOTE) return
        tailNote = STALE_TAIL_NOTE
        refreshLater()
    }

    /** Rebuilds detection and the line index from scratch; both services drop their stale posts. */
    private fun restart() {
        tail = null
        baseResult = null
        tailNote = null
        covered = -1
        tailOff = true
        lineIndex = null
        result = null
        indexJob?.dispose()
        detection?.dispose()
        detection = project.getService(LogSmithDetectionService::class.java).detect(file, this, ::onResult)
        startIndex()
        refreshLater()
    }

    /** The indexed case only; [LineOffsetIndex] is absent when the file was not indexed. */
    private fun indexedIndex(): LineOffsetIndex? =
        (lineIndex as? LogSmithLineIndexService.Outcome.Indexed)?.index

    /** -1 when there is nothing to consume, or when offsets outgrow what a document can address. */
    private fun coveredFrom(scanned: Long?): Int =
        if (scanned == null || scanned > Int.MAX_VALUE) -1 else scanned.toInt()

    private val editorDocument get() = textEditor.editor.document

    /**
     * Refreshing installs a highlighter and repaints a Swing strip, so it never happens inside
     * the write action that just changed the document; the deferred run still sees the state
     * the change produced, because the state was already updated synchronously.
     */
    private fun refreshLater() {
        if (isDisposed) return
        ApplicationManager.getApplication().invokeLater(
            { if (!isDisposed) refresh() },
            ModalityState.defaultModalityState(),
        ) { isDisposed }
    }

    /** Re-applies highlighting and status text from current state. */
    fun refresh() {
        installer?.update(result, isDisabled)
        strip.render(StatusText.of(result, isDisabled, lineIndex, lastFilterNote, tailNote))
    }

    /**
     * Applies a filter from the bar (or a document change). The current folds stay in place until
     * the new plan replaces them — fold regions follow the text they cover, so they stay correct
     * meanwhile — rather than expanding the whole file for the moment the plan takes, which on a
     * live log meant the view jumped on every append.
     */
    internal fun applyFilter(state: FilterState) {
        if (isDisposed) return
        filter = state
        filterJob?.dispose()
        filterJob = null
        if (state.isDefault) {
            lastFilterNote = null
            clearFolds()
        } else {
            filterJob = project.getService(LogSmithFilterService::class.java)
                .plan(file, textEditor.editor, state, claimedSniffer(), appendBase) { job, outcome -> onFilterOutcome(job, outcome) }
        }
        refresh()
    }

    /** The format detection claimed, which decides what a record is; null when nothing was claimed. */
    private fun claimedSniffer() =
        (result as? DetectionResult.Matched)?.stats?.formatName?.let { BuiltinSniffers.byName[it] }

    internal fun onFilterOutcome(job: LogSmithFilterService.Job, outcome: LogSmithFilterService.Outcome) {
        if (filterJob !== job || job.cancelled) return
        filterJob = null
        when (outcome) {
            is LogSmithFilterService.Outcome.Folded -> {
                if (outcome.facts.documentStamp == textEditor.editor.document.modificationStamp) appendBase = outcome.facts
                lastFilterNote = "filter hides %,d of %,d lines".format(Locale.US, outcome.hiddenLines, outcome.facts.lineCount)
                applyFolds(outcome)
            }
            is LogSmithFilterService.Outcome.Failed -> {
                lastFilterNote = "filter could not be applied: ${outcome.reason}"
                clearFolds()
            }
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
                // End before the newline of the last hidden line: that newline stays visible, so
                // the placeholder gets a line of its own and the next record starts on the next.
                val end = if (run.lastLine + 1 < starts.size) starts[run.lastLine + 1] - 1 else document.textLength
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
        if (editor.document.textLength > LogSmithFilterService.MAX_FILTER_BYTES) {
            hint("LogSmith error navigation is off for files over 64 MB")
            return
        }
        val sniffer = claimedSniffer()
        LogSmithFilterService.cachedFacts(file, editor.document, sniffer)?.let { facts ->
            moveToError(facts, forward)
            return
        }
        factsJob?.dispose()
        val job = project.getService(LogSmithFilterService::class.java).ensureFacts(file, editor, sniffer) { j, facts ->
            if (factsJob === j) moveToError(facts, forward)
        }
        factsJob = job
    }

    /** Last message shown by [hint]; kept so the never-silent paths are testable headless. */
    internal var lastHint: String? = null
        private set

    private fun hint(message: String) {
        lastHint = message
        HintManager.getInstance().showInformationHint(textEditor.editor, message)
    }

    private fun moveToError(facts: LogSmithFilterService.LineFacts, forward: Boolean) {
        if (isDisposed) return
        val editor = textEditor.editor
        val document = editor.document
        if (facts.documentStamp != document.modificationStamp) return
        val from = document.getLineNumber(editor.caretModel.offset)
        val target = ErrorNavigator.next(facts.bytes, from, forward)
        if (target == null) {
            hint(if (facts.bytes.any { LogLineFacts.isError(it) }) "This is the only ERROR record in this log" else "No ERROR records in this log")
            return
        }
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

        /**
         * Says why the count stopped moving instead of pretending it is current. The file stays
         * fully editable; saving it makes the next change a clean one, which resumes the tail.
         */
        internal const val STALE_TAIL_NOTE = "live tail paused: unsaved changes"
    }
}
