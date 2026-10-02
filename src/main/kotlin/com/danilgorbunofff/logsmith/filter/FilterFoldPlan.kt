package com.danilgorbunofff.logsmith.filter

/**
 * Pure, line-array fold planner: given one facts byte per line and the filter state,
 * it lays out the contiguous runs of lines the editor should fold away.
 *
 * Semantics of facts lines ([LogLineFacts]):
 *  - a record line is visible when it matches the filter (level + optional free text);
 *  - a non-record (`0`) line follows the record it belongs to: hidden while a hidden run
 *    is open, visible as lead-in before the first record or right after a matching
 *    record — so stack frames of a *visible* record are never cut from view;
 *  - a hidden run is only emitted when it has at least one line.
 */
object FilterFoldPlan {

    /** A contiguous run of lines to collapse; both bounds are inclusive. */
    data class FoldRun(val firstLine: Int, val lastLine: Int) {
        val lineCount: Int get() = lastLine - firstLine + 1
    }

    fun plan(facts: ByteArray, state: FilterState, lineTexts: (Int) -> String?): List<FoldRun> {
        val runs = ArrayList<FoldRun>()
        var runStart = -1
        facts.indices.forEach { line ->
            val byte = facts[line]
            val hide = when {
                byte == 0.toByte() -> runStart != -1
                else -> !recordVisible(byte, line, state, lineTexts)
            }
            when {
                hide && runStart == -1 -> runStart = line
                !hide && runStart != -1 -> {
                    runs += FoldRun(runStart, line - 1)
                    runStart = -1
                }
            }
        }
        if (runStart != -1) runs += FoldRun(runStart, facts.size - 1)
        return runs
    }

    private fun recordVisible(
        byte: Byte,
        line: Int,
        state: FilterState,
        lineTexts: (Int) -> String?,
    ): Boolean {
        if (!state.matchesLevel(LogLineFacts.levelOf(byte))) return false
        if (state.text.isBlank()) return true
        val text = lineTexts(line) ?: return true
        return text.contains(state.text, ignoreCase = true)
    }
}

/**
 * Pure next/prev ERROR walk over the same facts bytes, used by the F2 / Shift+F2 actions.
 * Wraps around at the edges; returns null when the file contains no error besides the
 * starting line itself.
 */
object ErrorNavigator {

    fun next(facts: ByteArray, fromLine: Int, forward: Boolean): Int? {
        val n = facts.size
        if (n == 0) return null
        for (offset in 1..n) {
            val candidate = if (forward) {
                (fromLine + offset) % n
            } else {
                (fromLine - offset + n) % n
            }
            if (LogLineFacts.isError(facts[candidate])) return candidate
        }
        return null
    }
}
