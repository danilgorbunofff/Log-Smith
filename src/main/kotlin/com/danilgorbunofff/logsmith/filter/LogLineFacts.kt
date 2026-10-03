package com.danilgorbunofff.logsmith.filter

import com.danilgorbunofff.logsmith.ansi.AnsiText
import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithTokenTypes
import com.danilgorbunofff.logsmith.highlight.SegmentPatterns
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer

/**
 * One byte of facts per line, produced by a cheap document scan that runs on a background
 * thread and is cached for the life of one document revision.
 *
 * Encoding (see [LogSmithFilterService]):
 *  - `0` — this line is not the start of a record (an empty line, or a stack-frame / JSON /
 *    message continuation line). Its visibility is decided by the record it belongs to in
 *    [FilterFoldPlan]; leading `0` lines above the first record stay visible.
 *  - `(0x80 or (level ordinal + 1))` — a record start whose severity word was recognised.
 *  - `0x80` alone — a record start that carries no severity (an access-log line, a Go stdlib
 *    line); a level filter cannot judge it, so it is never hidden for its level.
 *
 * A line is deliberately classified as "not a record" instead of guessing, so a mis-owned
 * continuation line never hides a real record.
 */
object LogLineFacts {
    const val RECORD_FLAG: Int = 0x80

    /** How far into a line the format-less heuristic looks for a level word. */
    private const val FALLBACK_LEVEL_WINDOW = 120

    /** `true` when [flagByte] marks the start of a recognised record. */
    fun isRecordStart(flagByte: Byte): Boolean = flagByte != 0.toByte()

    /** [LogLevel] carried by a record byte, or null for a record without a severity. */
    fun levelOf(recordByte: Byte): LogLevel? {
        val ordinal = (recordByte.toInt() and 0x0F) - 1
        return LogLevel.entries.getOrNull(ordinal)
    }

    /** `true` when [recordByte] marks an ERROR record, the target of the F2 walk. */
    fun isError(recordByte: Byte): Boolean {
        if (recordByte == 0.toByte()) return false
        return recordByte.toInt() and 0x0F == LogLevel.ERROR.ordinal + 1
    }

    /** The byte for a record start at [level]; null makes a record without a severity. */
    fun recordByte(level: LogLevel?): Byte =
        (RECORD_FLAG or (level?.let { it.ordinal + 1 } ?: 0)).toByte()

    /**
     * Format-less classification, used when detection claimed no format. It has no record shape
     * to go by, so it only trusts strong signals: a line that *starts* with a level and a colon
     * (`SEVERE: …`, `WARNING:root: …`), or a level word near the start of a line that is not
     * indented. Indented lines are continuations (stack frames, wrapped messages), and a level
     * word directly after a `.` is a method or package name (`Logger.error(`), not a severity.
     */
    fun classify(line: CharSequence): Byte {
        val text = AnsiText.strip(line)
        if (text.isEmpty() || text[0].isWhitespace()) return 0
        val prefixMatcher = SegmentPatterns.LEVEL_PREFIX.matcher(text)
        if (prefixMatcher.matches()) return recordByteFor(prefixMatcher.group(1))
        val searcher = SegmentPatterns.LEVEL_SEARCH.matcher(text)
        searcher.region(0, minOf(text.length, FALLBACK_LEVEL_WINDOW))
        while (searcher.find()) {
            val start = searcher.start()
            if (start > 0 && text[start - 1] == '.') continue
            return recordByteFor(text.substring(start, searcher.end()))
        }
        return 0
    }

    /**
     * Classification against the format detection claimed: a line is a record start exactly when
     * [sniffer] accepts it as a record, and its level is the one [segmenter] colours — the same
     * bracket-aware reading, so a thread named `[error-reporter]` does not make an INFO record an
     * error, and a stack frame through `Logger.error(…)` is a continuation, not a record.
     */
    fun classify(line: CharSequence, sniffer: LogFormatSniffer, segmenter: LineSegmenter): Byte {
        val text = AnsiText.strip(line)
        if (text.isEmpty() || !sniffer.matches(text)) return 0
        return recordByte(levelOfType(segmenter.recordLevel(text)))
    }

    /** The level a continuation line announces (`SEVERE: …` under a JUL header), if any. */
    fun continuationLevel(line: CharSequence): LogLevel? {
        val matcher = SegmentPatterns.LEVEL_PREFIX.matcher(AnsiText.strip(line))
        return if (matcher.matches()) LogLevel.parse(matcher.group(1)) else null
    }

    private fun levelOfType(type: Any?): LogLevel? = when (type) {
        LogSmithTokenTypes.LEVEL_ERROR -> LogLevel.ERROR
        LogSmithTokenTypes.LEVEL_WARN -> LogLevel.WARN
        LogSmithTokenTypes.LEVEL_DEBUG -> LogLevel.DEBUG
        LogSmithTokenTypes.LEVEL_INFO -> LogLevel.INFO
        else -> null
    }

    private fun recordByteFor(word: CharSequence): Byte {
        val level = LogLevel.parse(word.toString()) ?: return 0
        return recordByte(level)
    }
}
