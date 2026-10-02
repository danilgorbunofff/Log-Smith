package com.danilgorbunofff.logsmith.filter

import com.danilgorbunofff.logsmith.highlight.SegmentPatterns

/**
 * One byte of facts per line, produced by a cheap document scan that runs on a background
 * thread and is cached for the life of one document revision.
 *
 * Encoding (see [LogSmithFilterService]):
 *  - `0` — this line is not the start of a record (an empty line, or a stack-frame / JSON /
 *    message continuation line). Its visibility is decided by the record it belongs to in
 *    [FilterFoldPlans]; leading `0` lines above the first record stay visible.
 *  - `(0x80 or (level ordinal + 1))` — a record start whose severity word was recognised.
 *
 * A line is deliberately classified as "not a record" instead of guessing, so a mis-owned
 * continuation line never hides a real record.
 */
object LogLineFacts {
    const val RECORD_FLAG: Int = 0x80

    /** `true` when [flagByte] marks the start of a recognised record. */
    fun isRecordStart(flagByte: Byte): Boolean = flagByte != 0.toByte()

    /** [LogLevel] carried by a record byte — only valid for bytes from [isRecordStart]. */
    fun levelOf(recordByte: Byte): LogLevel =
        LogLevel.entries[(recordByte.toInt() and 0x0F) - 1]

    /** `true` when [recordByte] marks an ERROR record, the target of the F2 walk. */
    fun isError(recordByte: Byte): Boolean {
        if (recordByte == 0.toByte()) return false
        return recordByte.toInt() and 0x0F == LogLevel.ERROR.ordinal + 1
    }

    /**
     * Classifies one line's content (no trailing newline) into a facts byte.
     *
     * Two recognisers, both sourced from the detection lexer's vocabulary so a new vendor
     * word extends both at once:
     *  1. `LEVEL_PREFIX.matches()` — lines whose whole head is `SEVERE:`, `WARN:`, `INFO` etc.
     *     (JUL, python's logging, some syslog variants).
     *  2. `LEVEL_SEARCH.find()` — a word-bounded level anywhere in the line, which covers the
     *     timestamped layouts (`2026-01-01 09:14:02.101 [qtp5] ERROR app - message`).
     */
    fun classify(line: CharSequence): Byte {
        val prefixMatcher = SegmentPatterns.LEVEL_PREFIX.matcher(line)
        if (prefixMatcher.matches()) {
            return recordByteFor(prefixMatcher.group(1))
        }
        val searcher = SegmentPatterns.LEVEL_SEARCH.matcher(line)
        if (searcher.find()) {
            return recordByteFor(line.subSequence(searcher.start(), searcher.end()).toString())
        }
        return 0
    }

    private fun recordByteFor(word: CharSequence): Byte {
        val level = LogLevel.parse(word.toString()) ?: return 0
        return (RECORD_FLAG or (level.ordinal + 1)).toByte()
    }
}
