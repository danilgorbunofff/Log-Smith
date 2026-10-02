package com.danilgorbunofff.logsmith.sniff

import com.danilgorbunofff.logsmith.ansi.AnsiText

/**
 * Pure scanner that scores every sniffer over a line stream and picks the
 * winner: the highest share of explained lines (record or continuation),
 * ties broken by sniffer priority. Blank lines never count. A sniffer must
 * match at least one *record* line to win — continuation patterns alone
 * (e.g. "any indented line") never claim a file. Lines are normalized before
 * scoring: a trailing `\r` is trimmed and ANSI escapes are stripped, so every
 * sniffer sees the same escape-free text. No platform imports — this
 * is the part the unit tests cover directly.
 */
class FormatScorer(sniffers: List<LogFormatSniffer>) {

    /** Priority-descending, so the first maximum found wins ties. */
    private val ordered: List<LogFormatSniffer> = sniffers.sortedByDescending { it.priority }

    private val explained = LongArray(ordered.size)
    private val records = LongArray(ordered.size)
    private var scanned = 0L
    private var capNote: String? = null

    fun onLine(rawLine: String) {
        val trimmed = rawLine.trimEnd('\r')
        // Sniffers must be ANSI-blind: colour codes land *inside* tokens (a Spring Boot
        // console pattern emits `<ESC>[32m INFO <ESC>[0m`), and no pattern matches through
        // them, so a coloured log would be mis-claimed or reported as no format at all.
        // Detection strips here; highlighting re-reads the raw text and styles the escapes.
        val line = if (AnsiText.containsEscape(trimmed)) AnsiText.strip(trimmed) else trimmed
        if (line.isBlank()) return
        scanned++
        for (index in ordered.indices) {
            val sniffer = ordered[index]
            if (sniffer.matches(line)) {
                records[index]++
                explained[index]++
            } else if (sniffer.matchesContinuation(line)) {
                explained[index]++
            }
        }
    }

    fun markCapped(note: String) {
        capNote = note
    }

    /** Highest ratio among sniffers with at least one record line; ties go to the higher priority. */
    fun bestCandidate(): FormatStats? {
        if (scanned == 0L) return null
        var bestIndex = -1
        var bestCount = 0L
        for (index in ordered.indices) {
            if (records[index] > 0 && explained[index] > bestCount) {
                bestCount = explained[index]
                bestIndex = index
            }
        }
        if (bestIndex < 0) return null
        val stats = FormatStats(ordered[bestIndex].formatName, explained[bestIndex].toInt(), scanned.toInt())
        return capNote?.let { stats.copy(note = it) } ?: stats
    }

    /** The winner, or null when no candidate reaches [MIN_RATIO]. */
    fun best(): FormatStats? = bestCandidate()?.takeIf { it.ratio >= MIN_RATIO }

    /** The full outcome of this scan, including an honest "nothing matched". */
    fun result(): DetectionResult {
        val candidate = bestCandidate()
        return if (candidate != null && candidate.ratio >= MIN_RATIO) {
            DetectionResult.Matched(candidate)
        } else {
            DetectionResult.NoMatch(candidate, scanned.toInt(), capNote)
        }
    }

    companion object {
        /** Below this share of explained lines no format is claimed honestly. */
        const val MIN_RATIO = 0.5
    }
}
