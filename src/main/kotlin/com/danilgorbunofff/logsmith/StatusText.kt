package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.index.LineOffsetIndex
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.danilgorbunofff.logsmith.sniff.FormatStats
import java.util.Locale

/**
 * Everything the status line can say, as a pure function of state (charter §5.1:
 * never silent). Kept free of Swing so every wording is unit-tested.
 */
data class StatusText(val text: String, val tooltip: String, val warning: Boolean) {

    companion object {
        fun of(
            result: DetectionResult?,
            disabled: Boolean,
            lineIndex: LogSmithLineIndexService.Outcome? = null,
            filterNote: String? = null,
            tailNote: String? = null,
        ): StatusText {
            val base = when (result) {
                null -> StatusText(
                    FormatStats.UNKNOWN,
                    "Detection is scanning the first ${LogSmithDetectionService.HEAD_LINES} lines…",
                    warning = false,
                )
                is DetectionResult.Matched -> StatusText(
                    result.stats.toString(),
                    "matched ${result.stats.matched} of ${result.stats.scanned} non-blank lines scanned" +
                        (result.stats.note?.let { " — $it" } ?: ""),
                    warning = false,
                )
                is DetectionResult.NoMatch -> StatusText(
                    "Format: no format matched ${result.scanned} lines — showing plain text" +
                        (result.closest?.let { " (closest: ${it.formatName}, ${it.percentText})" } ?: ""),
                    "LogSmith leaves files it cannot explain uncoloured" +
                        (result.note?.let { " — $it" } ?: ""),
                    warning = true,
                )
                is DetectionResult.Failed -> StatusText(
                    "LogSmith could not read this file (${result.reason}) — showing plain text",
                    "The file is shown exactly as the IDE renders it without LogSmith.",
                    warning = true,
                )
            }
            val notes = buildList {
                if (disabled) add("highlighting disabled for this file")
                lineIndex?.let { add(lineNote(it)) }
                // Right after the count: it is the reason that count has stopped moving.
                tailNote?.let { add(it) }
                filterNote?.let { add(it) }
            }
            if (notes.isEmpty()) return base
            return base.copy(
                text = base.text + " — " + notes.joinToString(" — "),
                tooltip = if (disabled) {
                    "Use the editor context menu to re-enable LogSmith highlighting for this file."
                } else {
                    base.tooltip
                },
            )
        }

        /**
         * The line-index half of the status line (charter §5.3 R5): a number when the whole
         * file was indexed, a stated cap when it was not. Never silent, never a warning —
         * a capped index or an unreadable second pass does not make the file less usable.
         */
        private fun lineNote(outcome: LogSmithLineIndexService.Outcome): String = when (outcome) {
            is LogSmithLineIndexService.Outcome.Indexed ->
                if (outcome.index.capped) "${group(outcome.index.lineCount)}+ lines (line index capped)"
                else "${group(outcome.index.lineCount)} lines"
            is LogSmithLineIndexService.Outcome.TooLarge ->
                "not indexed: file is over ${LogSmithLineIndexService.MAX_INDEX_BYTES / (1024L * 1024 * 1024)} GB"
            is LogSmithLineIndexService.Outcome.Failed ->
                "line count unavailable: ${outcome.reason}"
        }

        /** Test hook: an index of [text], as if the file had been read. */
        internal fun indexOf(text: String): LogSmithLineIndexService.Outcome =
            LogSmithLineIndexService.Outcome.Indexed(LineOffsetIndex().apply { accept(text) })

        private fun group(value: Int): String = String.format(Locale.US, "%,d", value)
    }
}
