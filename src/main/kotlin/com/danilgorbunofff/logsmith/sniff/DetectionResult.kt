package com.danilgorbunofff.logsmith.sniff

/**
 * Outcome of one detection scan. Every outcome is shown to the user — there is no
 * "silent" branch (charter §5.1, §11.4 rule 2).
 */
sealed interface DetectionResult {

    /** A format explained at least [FormatScorer.MIN_RATIO] of the scanned lines. */
    data class Matched(val stats: FormatStats) : DetectionResult

    /**
     * The scan ran but no format reached a believable ratio. [closest] is the best
     * candidate that matched at least one record line, if any — reported, not applied.
     */
    data class NoMatch(val closest: FormatStats?, val scanned: Int, val note: String? = null) : DetectionResult

    /** The file could not be read; [reason] is shown verbatim. */
    data class Failed(val reason: String) : DetectionResult
}
