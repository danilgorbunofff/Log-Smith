package com.danilgorbunofff.logsmith.filter

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The filtering side of the plugin (charter §5.5, §5.6 group F): classification, fold
 * planning and navigation all run off the EDT on the same document snapshot, and the
 * outcome is handed back on the EDT, cancellable and disposal-bounded.
 *
 * What counts as a record is decided by the format detection claimed ([LogLineFacts]), so the
 * filter and F2 agree with the highlighter about which lines are records and at which level.
 */
@Service(Service.Level.PROJECT)
class LogSmithFilterService internal constructor() : Disposable {

    sealed class Outcome {
        class Folded internal constructor(val plan: List<FilterFoldPlan.FoldRun>, val facts: LineFacts, val hiddenLines: Int) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * Per-line facts for one document snapshot: the starting offset of every line and its
     * classification byte, for the format named [formatName] (null: no format was claimed).
     */
    class LineFacts internal constructor(
        val documentStamp: Long,
        val lineStarts: IntArray,
        val bytes: ByteArray,
        val formatName: String?,
        val textLength: Int,
    ) {
        val lineCount: Int get() = bytes.size
    }

    class Job internal constructor() : Disposable {
        @Volatile
        var cancelled: Boolean = false
            private set

        @Volatile
        internal var future: Future<*>? = null

        override fun dispose() {
            cancelled = true
            future?.cancel(true)
        }

        internal fun awaitForTests(timeoutSeconds: Long = 60) {
            try {
                future?.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: CancellationException) {
            } catch (_: ExecutionException) {
            }
        }
    }

    private val executor: ExecutorService = AppExecutorUtil.createBoundedApplicationPoolExecutor("LogSmith filter", 1)

    private fun post(job: Job, outcome: Outcome, onDone: (Job, Outcome) -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            { if (!job.cancelled) onDone(job, outcome) },
            ModalityState.defaultModalityState(),
        ) { job.cancelled }
    }

    private fun postFacts(job: Job, facts: LineFacts, onDone: (Job, LineFacts) -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            { if (!job.cancelled) onDone(job, facts) },
            ModalityState.defaultModalityState(),
        ) { job.cancelled }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    /**
     * Plans the fold runs for [state] against the document behind [editor], delivering
     * [Outcome.Folded] (or a scan-cancelling/stamp-mismatch silence) on the EDT.
     *
     * [appendedTo] is an earlier facts snapshot of the same document that the caller knows has
     * only been appended to since; its lines are reused and only the tail is classified, so a
     * filtered live log does not re-classify the whole file on every append.
     */
    fun plan(
        file: VirtualFile,
        editor: Editor,
        state: FilterState,
        sniffer: LogFormatSniffer? = null,
        appendedTo: LineFacts? = null,
        onDone: (Job, Outcome) -> Unit,
    ): Job {
        val job = Job()
        val document = editor.document
        if (document.textLength > MAX_FILTER_BYTES) {
            post(job, Outcome.Failed("file is over the 64 MB filtering cap"), onDone)
            return job
        }
        job.future = executor.submit {
            try {
                val facts = cachedFacts(file, document, sniffer)
                    ?: scanFacts(document, job, file, sniffer, appendedTo)
                    ?: return@submit
                if (document.modificationStamp != facts.documentStamp) return@submit
                val text = document.immutableCharSequence
                val lineTexts: (Int) -> String = { line ->
                    val start = facts.lineStarts[line]
                    val end = if (line + 1 < facts.lineStarts.size) facts.lineStarts[line + 1] else text.length
                    text.subSequence(start, end).toString()
                }
                val foldPlan = FilterFoldPlan.plan(facts.bytes, state, lineTexts)
                val hidden = foldPlan.sumOf { it.lineCount }
                post(job, Outcome.Folded(foldPlan, facts, hidden), onDone)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("LogSmith filter planning failed", e)
                post(job, Outcome.Failed(e.message ?: e.javaClass.simpleName), onDone)
            }
        }
        return job
    }

    /**
     * Scans the document on a background thread and caches the facts on the [VirtualFile]. The
     * caller checks [MAX_FILTER_BYTES] first; over the cap this returns a job that never reports.
     */
    fun ensureFacts(
        file: VirtualFile,
        editor: Editor,
        sniffer: LogFormatSniffer? = null,
        onDone: (Job, LineFacts) -> Unit,
    ): Job {
        val job = Job()
        val document = editor.document
        if (document.textLength > MAX_FILTER_BYTES) return job
        cachedFacts(file, document, sniffer)?.let { facts ->
            postFacts(job, facts, onDone)
            return job
        }
        job.future = executor.submit {
            try {
                val facts = scanFacts(document, job, file, sniffer, null) ?: return@submit
                if (document.modificationStamp != facts.documentStamp) return@submit
                postFacts(job, facts, onDone)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("LogSmith facts scan failed", e)
            }
        }
        return job
    }

    companion object {
        /** 64 MB cap, mirroring the line-index service limit. */
        const val MAX_FILTER_BYTES: Long = 64L shl 20

        private val FACTS_KEY: Key<LineFacts> = Key.create("LOGSMITH_LINE_FACTS")

        fun cachedFacts(file: VirtualFile, document: Document, sniffer: LogFormatSniffer? = null): LineFacts? {
            val cached = file.getUserData(FACTS_KEY) ?: return null
            if (cached.documentStamp != document.modificationStamp) return null
            return if (cached.formatName == sniffer?.formatName) cached else null
        }

        /**
         * Returns null when the job was cancelled or the document changed while scanning. With a
         * usable [appendedTo], classification resumes at its last record start: that record may
         * have gained continuation lines, and its last line may have been incomplete.
         */
        private fun scanFacts(
            document: Document,
            job: Job,
            file: VirtualFile,
            sniffer: LogFormatSniffer?,
            appendedTo: LineFacts?,
        ): LineFacts? {
            val stamp = document.modificationStamp
            val text = document.immutableCharSequence
            val length = text.length
            val segmenter = sniffer?.let { LineSegmenter(it) }
            val base = appendedTo?.takeIf {
                it.formatName == sniffer?.formatName && it.textLength <= length && it.lineCount > 0
            }
            var resume = 0
            if (base != null) {
                resume = base.lineCount - 1
                while (resume > 0 && !LogLineFacts.isRecordStart(base.bytes[resume])) resume--
            }
            val initial = maxOf(1024, (base?.lineCount ?: 0) * 2)
            var lineStarts = IntArray(initial)
            var bytes = ByteArray(initial)
            if (base != null && resume > 0) {
                base.lineStarts.copyInto(lineStarts, 0, 0, resume)
                base.bytes.copyInto(bytes, 0, 0, resume)
            }
            var count = if (base != null) resume else 0
            var offset = if (base != null && resume > 0) base.lineStarts[resume] else 0
            var lastRecord = -1
            for (line in count - 1 downTo 0) {
                if (LogLineFacts.isRecordStart(bytes[line])) {
                    lastRecord = line
                    break
                }
            }
            while (offset <= length) {
                if (job.cancelled) return null
                if (count == lineStarts.size) {
                    lineStarts = lineStarts.copyOf(count * 2)
                    bytes = bytes.copyOf(count * 2)
                }
                var nl = offset
                while (nl < length && text[nl] != '\n') nl++
                var contentEnd = nl
                if (contentEnd > offset && text[contentEnd - 1] == '\r') contentEnd--
                var factsByte: Byte = 0
                if (contentEnd > offset) {
                    val content = text.subSequence(offset, contentEnd)
                    factsByte = if (sniffer != null && segmenter != null) {
                        LogLineFacts.classify(content, sniffer, segmenter)
                    } else {
                        LogLineFacts.classify(content)
                    }
                    // java.util.logging prints the header first and the level on the next line
                    // (`SEVERE: …`): the level belongs to the record the header started.
                    if (factsByte == 0.toByte() && sniffer != null && lastRecord == count - 1 && lastRecord >= 0 &&
                        LogLineFacts.levelOf(bytes[lastRecord]) == null
                    ) {
                        LogLineFacts.continuationLevel(content)?.let { bytes[lastRecord] = LogLineFacts.recordByte(it) }
                    }
                }
                lineStarts[count] = offset
                bytes[count] = factsByte
                if (factsByte != 0.toByte()) lastRecord = count
                count++
                if (nl >= length) break
                offset = nl + 1
            }
            if (document.modificationStamp != stamp) return null
            val facts = LineFacts(stamp, lineStarts.copyOf(count), bytes.copyOf(count), sniffer?.formatName, length)
            file.putUserData(FACTS_KEY, facts)
            return facts
        }
    }
}
