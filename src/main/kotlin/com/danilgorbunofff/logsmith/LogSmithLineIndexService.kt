package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Builds the [LineOffsetIndex] for one open document off the EDT.
 *
 * Charter §5.3 (R5): line offsets are the only per-file structure the plugin keeps, so the
 * index costs the line count, not the text. It is built from the editor's own document
 * snapshot rather than from the bytes on disk: the document has already normalized line
 * separators (`\r\n` becomes `\n`), dropped the BOM and applied the charset, so the index and
 * the live tail — which consumes document changes — share one coordinate space. Built from
 * the disk bytes, a CRLF file indexed longer than its document, and the tail took that for a
 * rewrite on every rebuild. The outcome — including "too large" — is posted on the EDT and
 * ends up in the status line, never silently.
 *
 * The service is a project service with a single-worker pool: two simultaneous index builds
 * would only fight each other, and the detection scans keep their own pools.
 */
@Service(Service.Level.PROJECT)
class LogSmithLineIndexService : Disposable {

    private val executor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("LogSmith line-index", 1)

    /** What the line index ended up being for one document; [Indexed] is the success case. */
    sealed interface Outcome {
        /** [index] holds the line starts of the indexed snapshot, in document offsets. */
        class Indexed(val index: LineOffsetIndex) : Outcome

        /** The document is longer than LogSmith is willing to index; [length] is its size in characters. */
        data class TooLarge(val length: Long) : Outcome

        /** Indexing failed; [reason] is the message of the failure. */
        data class Failed(val reason: String) : Outcome
    }

    /** Handle for one index build; disposed together with its parent. */
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

        /** Test hook: waits until the build has finished or was cancelled. */
        internal fun awaitForTests(timeoutSeconds: Long = 60) {
            val future = future ?: return
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (ignored: CancellationException) {
            } catch (ignored: java.util.concurrent.ExecutionException) {
            }
        }
    }

    /**
     * Indexes [text] — an immutable document snapshot, e.g. `Document.immutableCharSequence` —
     * and reports the outcome on the EDT unless [parent] was disposed.
     */
    fun index(text: CharSequence, parent: Disposable, onDone: (Outcome) -> Unit): Job =
        index(text, parent, MAX_INDEX_CHARS, LineOffsetIndex.DEFAULT_MAX_LINES, onDone)

    internal fun index(
        text: CharSequence,
        parent: Disposable,
        maxChars: Long,
        maxLines: Int,
        onDone: (Outcome) -> Unit,
    ): Job {
        val job = Job()
        if (!Disposer.tryRegister(parent, job)) {
            job.dispose()
            return job
        }
        if (text.length > maxChars) {
            post(job, Outcome.TooLarge(text.length.toLong()), onDone)
            return job
        }
        job.future = executor.submit {
            val outcome = try {
                Outcome.Indexed(LineOffsetIndex.build(text, maxLines = maxLines, isCancelled = { job.cancelled }))
            } catch (e: CancellationException) {
                null
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("LogSmith could not index a document", e)
                Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (outcome != null) post(job, outcome, onDone)
        }
        return job
    }

    private fun post(job: Job, outcome: Outcome, onDone: (Outcome) -> Unit) {
        if (job.cancelled) return
        ApplicationManager.getApplication().invokeLater(
            { if (!job.cancelled) onDone(outcome) },
            ModalityState.defaultModalityState(),
        ) { job.cancelled }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    companion object {
        /** 1 G characters: past this the offsets alone stop being worth their memory (charter §5.3). */
        internal const val MAX_INDEX_CHARS = 1L shl 30
    }
}
