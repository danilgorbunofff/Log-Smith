package com.danilgorbunofff.logsmith.sniff

import java.util.regex.Pattern

/**
 * A format candidate. Detection scores every sniffer over the scanned lines;
 * the winner is the sniffer with the highest match ratio, ties broken by priority.
 */
interface LogFormatSniffer {
    /** Human-readable format name, as shown in the status line. */
    val formatName: String

    /** Higher priority wins ties between equal match ratios. */
    val priority: Int

    /**
     * Timestamp sub-pattern, used by the highlighter to colour the leading
     * date/time region of a record. Null when the format has no colourable
     * timestamp (e.g. JSON). The pattern is written to match either the very
     * start of a record or the interior of a bracketed field, without brackets.
     */
    val timestamp: Pattern? get() = null

    fun matches(line: String): Boolean

    /**
     * Lines that belong to the format without carrying a full record shape —
     * stack frames, wrapped messages, indented payloads. Counted as explained.
     */
    fun matchesContinuation(line: String): Boolean = false
}

class RegexSniffer(
    override val formatName: String,
    override val priority: Int,
    private val pattern: Pattern,
    private val continuation: Pattern? = null,
    override val timestamp: Pattern? = null,
) : LogFormatSniffer {

    override fun matches(line: String): Boolean = pattern.matcher(line).matches()

    override fun matchesContinuation(line: String): Boolean =
        continuation?.matcher(line)?.matches() == true
}
