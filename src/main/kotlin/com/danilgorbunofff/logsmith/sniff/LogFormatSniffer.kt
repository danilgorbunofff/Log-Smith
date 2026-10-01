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

    fun matches(line: String): Boolean
}

class RegexSniffer(
    override val formatName: String,
    override val priority: Int,
    private val pattern: Pattern,
) : LogFormatSniffer {

    override fun matches(line: String): Boolean = pattern.matcher(line).matches()
}
