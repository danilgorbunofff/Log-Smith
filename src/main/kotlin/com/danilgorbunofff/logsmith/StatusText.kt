package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.danilgorbunofff.logsmith.sniff.FormatStats

/**
 * Everything the status line can say, as a pure function of state (charter §5.1:
 * never silent). Kept free of Swing so every wording is unit-tested.
 */
data class StatusText(val text: String, val tooltip: String, val warning: Boolean) {

    companion object {
        fun of(result: DetectionResult?, disabled: Boolean, colouringNote: String?): StatusText {
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
            return when {
                disabled -> base.copy(
                    text = base.text + " — highlighting disabled for this file",
                    tooltip = "Use the editor context menu to re-enable LogSmith highlighting for this file.",
                )
                colouringNote != null -> base.copy(text = base.text + " — $colouringNote")
                else -> base
            }
        }
    }
}
