package com.danilgorbunofff.logsmith.filter

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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

/**
 * The filtering side of the plugin (charter §5.5, §5.6 group F): classification, fold
 * planning and navigation all run off the EDT on the same document snapshot, and the
 * outcome is handed back on the EDT, cancellable and disposal-bounded.
 */
@Service(Service.Level.PROJECT)
class LogSmithFilterService internal constructor() {

    sealed class Outcome {
        class Folded internal constructor(val plan: List<FilterFoldPlan.FoldRun>, val facts: LineFacts, val hiddenLines: Int) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** Per-line facts for one document snapshot: the starting offset of every line and its classification byte. */
    class LineFacts internal constructor(val documentStamp: Long, val lineStarts: IntArray, val bytes: ByteArray) {
        val lineCount: Int get() = bytes.size
    }

    class Job internal constructor() : Disposable {
        @Volatile
        var cancelled: Boolean
            private set

        init {
            cancelled = false
        }

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

    fun dispose() {
        executor.shutdownNow()
    }

    /**
     * Plans the fold runs for [state] against the document behind [editor], delivering
     * [Outcome.Folded] (or a scan-cancelling/stamp-mismatch silence) on the EDT.
     */
    fun plan(file: VirtualFile, editor: Editor, state: FilterState, onDone: (Job, Outcome) -> Unit): Job {
        val job = Job()
        val document = editor.document
        if (document.textLength > MAX_FILTER_BYTES) {
            post(job, Outcome.Failed("file is over the 64 MB filtering cap"), onDone)
            return job
        }
        job.future = executor.submit {
            try {
                val facts = cachedFacts(file, document) ?: scanFacts(document, job, file) ?: return@submit
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

    /** Scans the document on a background thread and caches the facts on the [VirtualFile]. */
    fun ensureFacts(file: VirtualFile, editor: Editor, onDone: (Job, LineFacts) -> Unit): Job {
        val job = Job()
        val document = editor.document
        if (document.textLength > MAX_FILTER_BYTES) {
            // A too-large file simply has no error map: the navigator callers skip it.
            return job
        }
        cachedFacts(file, document)?.let { facts ->
            postFacts(job, facts, onDone)
            return job
        }
        job.future = executor.submit {
            try {
                val facts = scanFacts(document, job, file) ?: return@submit
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

        fun cachedFacts(file: VirtualFile, document: Document): LineFacts? {
            val cached = file.getUserData(FACTS_KEY) ?: return null
            return if (cached.documentStamp == document.modificationStamp) cached else null
        }

        /** Returns null when the job was cancelled or the document changed while scanning. */
        private fun scanFacts(document: Document, job: Job, file: VirtualFile): LineFacts? {
            val stamp = document.modificationStamp
            val text = document.immutableCharSequence
            var lineStarts = IntArray(1024)
            var bytes = ByteArray(1024)
            var count = 0
            val length = text.length
            var offset = 0
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
                val factsByte = if (contentEnd > offset) LogLineFacts.classify(text.subSequence(offset, contentEnd)) else 0
                lineStarts[count] = offset
                bytes[count] = factsByte
                count++
                if (nl >= length) break
                offset = nl + 1
            }
            if (document.modificationStamp != stamp) return null
            val facts = LineFacts(stamp, lineStarts.copyOf(count), bytes.copyOf(count))
            file.putUserData(FACTS_KEY, facts)
            return facts
            }
    }
}
