package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.danilgorbunofff.logsmith.sniff.LogScanner
import com.danilgorbunofff.logsmith.sniff.ScanResult
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
 * Scores a file against every registered sniffer and reports the outcome.
 *
 * Charter rules (§5.1, §5.3, §8 Day 3-4):
 * - a fast answer comes from the first [HEAD_LINES] lines; when the head did not cover
 *   the whole file, a background pass refines it over up to [REFINE_LINES] lines / [REFINE_BYTES],
 * - scans run off the EDT on platform pools — the head has its own pool so one file's long
 *   refinement never delays another file's fast answer; results are posted on the EDT,
 * - every outcome is posted, including "no format matched"; only an I/O failure of the
 *   refinement keeps the earlier head answer instead of replacing it,
 * - work is tied to a parent [Disposable] (the editor session): disposing it cancels the
 *   scan and drops any not-yet-delivered result,
 * - files are read through [VirtualFile] only (R10), byte- and line-bounded.
 */
@Service(Service.Level.PROJECT)
class LogSmithDetectionService : Disposable {

    private val headExecutor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("LogSmith detection-head", 2)
    private val refineExecutor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("LogSmith detection-refine", 1)

    /** Handle for one file's detection; disposed together with its parent. */
    class Job internal constructor() : Disposable {
        @Volatile
        var cancelled: Boolean = false
            private set

        @Volatile
        internal var posted: DetectionResult? = null

        @Volatile
        internal var head: Future<*>? = null

        @Volatile
        internal var refine: Future<*>? = null

        override fun dispose() {
            cancelled = true
            head?.cancel(true)
            refine?.cancel(true)
        }

        /** Test hook: waits until both scans have finished or were cancelled. */
        internal fun awaitForTests(timeoutSeconds: Long = 30) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            for (pick in listOf<() -> Future<*>?>({ head }, { refine })) {
                val future = pick() ?: continue
                try {
                    future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                } catch (ignored: CancellationException) {
                } catch (ignored: java.util.concurrent.ExecutionException) {
                }
            }
        }
    }

    /** Scans the head of [file], posts the fast answer, then refines in the background. */
    fun detect(file: VirtualFile, parent: Disposable, onUpdate: (DetectionResult) -> Unit): Job {
        val job = Job()
        if (!Disposer.tryRegister(parent, job)) {
            job.dispose()
            return job
        }
        job.head = headExecutor.submit {
            val head = scanQuietly(file, HEAD_LINES, HEAD_BYTES, job) ?: return@submit
            post(job, head.result, onUpdate)
            if (head.complete || head.result is DetectionResult.Failed || job.cancelled) return@submit
            job.refine = refineExecutor.submit {
                val refined = scanQuietly(file, REFINE_LINES, REFINE_BYTES, job) ?: return@submit
                post(job, refined.result, onUpdate)
            }
        }
        return job
    }

    /** null only when the job was cancelled; I/O problems become [DetectionResult.Failed]. */
    private fun scanQuietly(file: VirtualFile, lines: Int, bytes: Long, job: Job): ScanResult? {
        if (job.cancelled) return null
        return try {
            file.inputStream.use { LogScanner.scan(it, file.charset, lines, bytes) { job.cancelled } }
        } catch (e: CancellationException) {
            null
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("LogSmith could not scan ${file.presentableUrl}", e)
            ScanResult(DetectionResult.Failed(e.message ?: e.javaClass.simpleName), complete = true)
        }
    }

    /** Posts [candidate] on the EDT unless it is a failure that would hide an earlier answer. */
    private fun post(job: Job, candidate: DetectionResult, onUpdate: (DetectionResult) -> Unit) {
        if (job.cancelled) return
        if (candidate is DetectionResult.Failed && job.posted != null) return
        job.posted = candidate
        ApplicationManager.getApplication().invokeLater(
            { if (!job.cancelled) onUpdate(candidate) },
            ModalityState.defaultModalityState(),
        ) { job.cancelled }
    }

    override fun dispose() {
        headExecutor.shutdownNow()
        refineExecutor.shutdownNow()
    }

    companion object {
        internal const val HEAD_LINES = 200
        private const val HEAD_BYTES = 512L * 1024
        private const val REFINE_LINES = 2_000_000
        private const val REFINE_BYTES = 256L * 1024 * 1024
    }
}
