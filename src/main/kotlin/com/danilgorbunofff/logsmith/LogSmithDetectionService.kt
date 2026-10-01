package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.FormatScorer
import com.danilgorbunofff.logsmith.sniff.FormatStats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scores a file against every registered sniffer and reports the winner.
 *
 * Charter rules (§5.1, §8 Day 3-4):
 * - a fast answer comes from the first [HEAD_LINES] lines; a background pass
 *   then refines the score over up to [REFINE_LINES] lines / [REFINE_BYTES],
 * - scans run off the EDT — the head has its own small pool so one file's long
 *   refinement never delays another file's fast answer; results are posted on
 *   the EDT,
 * - results only get more precise: a failed or empty refinement never
 *   regresses the strip from real stats back to `<unknown>`,
 * - files are read through [VirtualFile] only (R10), byte- and line-bounded.
 */
@Service(Service.Level.PROJECT)
class LogSmithDetectionService(@Suppress("unused") private val project: Project) {

    private val headExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "LogSmith detection-head").apply { isDaemon = true }
    }
    private val refineExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LogSmith detection-refine").apply { isDaemon = true }
    }

    /** Scans the head of [file], posts the fast answer, then refines in the background. */
    fun detect(file: VirtualFile, onUpdate: (FormatStats?) -> Unit) {
        val guard = BestSoFar()
        headExecutor.submit {
            post(guard, scanQuietly(file, HEAD_LINES, HEAD_BYTES), onUpdate)
            refineExecutor.submit {
                post(guard, scanQuietly(file, REFINE_LINES, REFINE_BYTES), onUpdate)
            }
        }
    }

    private fun scanQuietly(file: VirtualFile, lines: Int, bytes: Long): FormatStats? =
        try {
            scanBounded(file, lines, bytes)
        } catch (ignored: Exception) {
            null // deleted or unreadable mid-scan; a previous good result must survive
        }

    /** Posts [candidate] unless it would downgrade an already-posted result to unknown. */
    private fun post(guard: BestSoFar, candidate: FormatStats?, onUpdate: (FormatStats?) -> Unit) {
        val stats = candidate ?: guard.posted ?: return
        if (candidate != null) guard.posted = candidate
        ApplicationManager.getApplication().invokeLater { onUpdate(stats) }
    }

    private class BestSoFar {
        @Volatile
        var posted: FormatStats? = null
    }

    private fun scanBounded(file: VirtualFile, lines: Int, bytes: Long): FormatStats? {
        file.inputStream.use { raw ->
            val bounded = BoundedInputStream(raw, bytes)
            bounded.bufferedReader(Charsets.UTF_8).use { reader ->
                val scorer = FormatScorer(BuiltinSniffers.all)
                var counted = 0
                for (line in reader.lineSequence()) {
                    scorer.onLine(line)
                    counted++
                    if (counted >= lines) {
                        scorer.markCapped("scan covered the first ${grouped(lines.toLong())} lines")
                        break
                    }
                }
                if (bounded.hitLimit && counted < lines) {
                    scorer.markCapped("scan stopped at the ${grouped(bytes / BYTES_PER_MB)} MB byte cap")
                }
                return scorer.best()
            }
        }
    }

    private fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

    /**
     * InputStream that reports EOF at [limit] bytes and records whether the cap
     * was reached. No close override: the caller's `use` on the raw stream owns it.
     */
    private class BoundedInputStream(private val delegate: InputStream, limit: Long) : InputStream() {

        private var remaining = limit

        var hitLimit: Boolean = false
            private set

        override fun read(): Int {
            if (remaining <= 0) {
                hitLimit = true
                return -1
            }
            val value = delegate.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) {
                hitLimit = true
                return -1
            }
            val capped = minOf(len.toLong(), remaining).toInt()
            val read = delegate.read(b, off, capped)
            if (read > 0) remaining -= read
            return read
        }
    }

    companion object {
        private const val HEAD_LINES = 200
        private const val HEAD_BYTES = 512L * 1024
        private const val REFINE_LINES = 2_000_000
        private const val REFINE_BYTES = 256L * 1024 * 1024
        private const val BYTES_PER_MB = 1024L * 1024
    }
}
