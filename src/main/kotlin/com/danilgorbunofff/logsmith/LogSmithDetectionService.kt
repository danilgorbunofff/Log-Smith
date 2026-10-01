package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.FormatStats
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scores a file against every registered sniffer and reports the winner.
 *
 * Rules taken from the charter (§5.1, §8 Day 3-4):
 * - only the first 200 lines are scanned for the fast answer; refinement over the
 *   whole file is a later-day background job,
 * - the scan runs off the EDT and the result callback runs on the EDT,
 * - files are read through [VirtualFile] only (R10), bounded to a small buffer.
 */
@Service(Service.Level.PROJECT)
class LogSmithDetectionService(@Suppress("unused") private val project: Project) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LogSmith detection").apply { isDaemon = true }
    }

    /** Scans the head of [file] on a background thread, then calls [onUpdate] on the EDT. */
    fun detect(file: VirtualFile, onUpdate: (FormatStats?) -> Unit) {
        executor.submit {
            val stats = try {
                detectBlocking(file)
            } catch (ignored: Exception) {
                null // deleted / unreadable mid-scan: keep the visible failure state
            }
            ApplicationManager.getApplication().invokeLater { onUpdate(stats) }
        }
    }

    fun detectBlocking(file: VirtualFile): FormatStats? {
        file.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val head = generateSequence { reader.readLine() }
                .take(BUFFER_LINES)
                .take(BUFFER_BYTES)
                .map { it.trimEnd('\r') }
                .toList()
            return score(head)
        }
    }

    private fun score(lines: List<String>): FormatStats? {
        val meaningful = lines.filter { it.isNotBlank() }
        if (meaningful.isEmpty()) return null
        var best: FormatStats? = null
        for (sniffer in BuiltinSniffers.all) {
            val stats = sniffer.score(meaningful)
            val better = when (best) {
                null -> true
                else -> stats.isBetterThan(best)
            }
            if (better) best = stats
        }
        return best
    }

    private fun LogFormatSniffer.score(lines: List<String>): FormatStats {
        val matched = lines.count { matches(it) }
        return FormatStats(formatName, matched, lines.size)
    }

    private fun FormatStats.isBetterThan(other: FormatStats): Boolean =
        ratio > other.ratio ||
            (ratio == other.ratio && snifferPriority(formatName) > snifferPriority(other.formatName))

    private fun snifferPriority(name: String): Int =
        BuiltinSniffers.all.firstOrNull { it.formatName == name }?.priority ?: 0

    companion object {
        private const val BUFFER_LINES = 200
        // Byte ceiling so a pathological no-newline monster file cannot blow the read.
        private const val BUFFER_BYTES = 512 * 1024
    }
}
