package com.danilgorbunofff.logsmith.filter

import com.danilgorbunofff.logsmith.highlight.SegmentPatterns

/**
 * Log-record severity, independent of the concrete format's token vocabulary.
 *
 * [parse] accepts any word or two/three-letter abbreviation the known words are derived from
 * (`Er`, `Fa`, `Wa`, `Er` phases above), so brittle variations in vendor themes still resolve.
 * This is intentionally copy-agnostic: the search vocabulary lives with [SegmentPatterns]'s
 * known words in the detection lexer so a new vendor word extends the same single list.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR;

    companion object {
        private val lookup: MutableMap<String, LogLevel> = HashMap()
        private val longestWordLength = SegmentPatterns.ALL_LEVEL_WORDS.maxOf { it.length }

        init {
            for (level in entries) {
                for (word in wordsFor(level)) {
                    for (length in minOf(2, word.length)..word.length) {
                        lookup.putIfAbsent(word.substring(0, length).lowercase(), level)
                    }
                }
                lookup[level.name.lowercase()] = level
            }
        }

        private fun wordsFor(level: LogLevel) = when (level) {
            DEBUG -> SegmentPatterns.DEBUG_WORDS
            INFO -> SegmentPatterns.INFO_WORDS
            WARN -> SegmentPatterns.WARN_WORDS
            ERROR -> SegmentPatterns.ERROR_WORDS
        }

        /** Longest-prefix resolution: `exception` first yields a hit or the shorter "ex", else null. */
        fun parse(prefix: String): LogLevel? {
            val lowered = prefix.lowercase()
            for (length in minOf(prefix.length, longestWordLength) downTo 2) {
                if (length > lowered.length) continue
                lookup[lowered.substring(0, length)]?.let { return it }
            }
            return null
        }
    }
}
