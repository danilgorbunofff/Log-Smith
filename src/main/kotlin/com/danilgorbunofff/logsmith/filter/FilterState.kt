package com.danilgorbunofff.logsmith.filter

/**
 * The current filter of one editor's view. Filtering never mutates the document, so this
 * state is per-editor on purpose: two editors of the same file may keep different views.
 *
 * A record matches when its recognised level is among [levels] AND, when [text] is
 * non-blank, its line content contains [text] ignoring case — free text therefore matches
 * logger names, thread names and message keywords alike without a dedicated parser.
 */
data class FilterState(
    val levels: Set<LogLevel> = LogLevel.entries.toSet(),
    val text: String = "",
) {
    /** The untouched state — used by the Reset button and as the "no filter" sentinel. */
    val isDefault: Boolean
        get() = levels == LogLevel.entries.toSet() && text.isBlank()

    /** Level predicate only; text matching stays with the fold planner, which knows the line. */
    fun matchesLevel(level: LogLevel): Boolean = level in levels
}
