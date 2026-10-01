package com.danilgorbunofff.logsmith.sniff

/**
 * Pure scanner that scores every sniffer over a line stream and picks the
 * winner: the highest share of explained lines (record or continuation),
 * ties broken by sniffer priority. Blank lines never count. No platform
 * imports — this is the part the unit tests cover directly.
 */
class FormatScorer(sniffers: List<LogFormatSniffer>) {

    /** Priority-descending, so the first maximum found wins ties. */
    private val ordered: List<LogFormatSniffer> = sniffers.sortedByDescending { it.priority }

    private val explained = LongArray(ordered.size)
    private var scanned = 0L
    private var capNote: String? = null

    fun onLine(rawLine: String) {
        val line = rawLine.trimEnd('\r')
        if (line.isBlank()) return
        scanned++
        for (index in ordered.indices) {
            val sniffer = ordered[index]
            if (sniffer.matches(line) || sniffer.matchesContinuation(line)) {
                explained[index]++
            }
        }
    }

    fun markCapped(note: String) {
        capNote = note
    }

    /** Highest ratio wins; ties go to the higher-priority sniffer. */
    fun best(): FormatStats? {
        if (scanned == 0L) return null
        var bestIndex = -1
        var bestCount = 0L
        for (index in ordered.indices) {
            if (explained[index] > bestCount) {
                bestCount = explained[index]
                bestIndex = index
            }
        }
        if (bestIndex < 0) return null
        val stats = FormatStats(ordered[bestIndex].formatName, explained[bestIndex].toInt(), scanned.toInt())
        if (stats.ratio < MIN_RATIO) return null
        return capNote?.let { stats.copy(note = it) } ?: stats
    }

    companion object {
        /** Below this share of explained lines no format is claimed honestly. */
        const val MIN_RATIO = 0.5
    }
}
