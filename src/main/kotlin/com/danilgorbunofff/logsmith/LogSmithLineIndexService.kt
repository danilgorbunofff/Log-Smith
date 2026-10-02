package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Builds the [LineOffsetIndex] for one file off the EDT, once per open file.
 *
 * Charter §5.3 (R5): line offsets are the only per-file structure the plugin keeps, so a
 * 500 MB log costs its line count, not its size. The index is read in chunks and can be
 * cancelled at any moment; the outcome — including "file too large" and "unreadable" — is
 * posted on the EDT and ends up in the status line, never silently.
 *
 * The service is a project service with a single-worker pool: indexing a 500 MB file must
 * not compete with the detection scans that make the format appear instantly, and two
 * simultaneous index builds would only fight for the same disk.
 */
@Service(Service.Level.PROJECT)
class LogSmithLineIndexService : Disposable {

    private val executor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("LogSmith line-index", 1)

    /** What the line index ended up being for one file; [Indexed] is the success case. */
    sealed interface Outcome {
        /** [index] holds the line starts of the indexed prefix of the file. */
        class Indexed(val index: LineOffsetIndex) : Outcome

        /** The file is bigger than LogSmith is willing to index; [bytes] is its size. */
        data class TooLarge(val bytes: Long) : Outcome

        /** The file could not be read; [reason] is the message the platform gave us. */
        data class Failed(val reason: String) : Outcome
    }

    /** Handle for one file's index build; disposed together with its parent. */
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

    /** Indexes [file] and reports the outcome on the EDT unless [parent] was disposed. */
    fun index(file: VirtualFile, parent: Disposable, onDone: (Outcome) -> Unit): Job =
        index(file, parent, MAX_INDEX_BYTES, LineOffsetIndex.DEFAULT_MAX_LINES, onDone)

    internal fun index(
        file: VirtualFile,
        parent: Disposable,
        maxBytes: Long,
        maxLines: Int,
        onDone: (Outcome) -> Unit,
    ): Job {
        val job = Job()
        if (!Disposer.tryRegister(parent, job)) {
            job.dispose()
            return job
        }
        val size = runCatching { file.length }.getOrDefault(0L)
        if (size > maxBytes) {
            post(job, Outcome.TooLarge(size), onDone)
            return job
        }
        job.future = executor.submit {
            val outcome = try {
                file.inputStream.use { stream ->
                    val index = LineOffsetIndex.build(
                        input = stream,
                        fallback = file.charset,
                        maxLines = maxLines,
                        isCancelled = { job.cancelled },
                    )
                    Outcome.Indexed(index)
                }
            } catch (e: CancellationException) {
                null
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("LogSmith could not index ${file.presentableUrl}", e)
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
        /** 1 GiB: past this the offsets alone stop being worth their memory (charter §5.3). */
        internal const val MAX_INDEX_BYTES = 1L shl 30
    }
}
