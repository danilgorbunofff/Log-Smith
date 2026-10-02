package com.danilgorbunofff.logsmith.highlight

import com.danilgorbunofff.logsmith.ansi.AnsiText
import com.danilgorbunofff.logsmith.ansi.Stripped
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.intellij.psi.tree.IElementType
import java.util.regex.Pattern

/** A typed character range relative to the start of one line. */
data class Span(val type: IElementType, val start: Int, val end: Int) {
    fun text(line: CharSequence): CharSequence = line.subSequence(start, end)
}

/**
 * Pure, testable line-to-spans classifier used by the LogSmith lexer.
 *
 * For a line the detected sniffer accepts as a record, it splits out the
 * timestamp / thread / logger / level / message components; for continuation
 * lines (stack frames, `INFO:` halves, indented payloads) it keeps the IDE's
 * default look except where a level word appears. Everything it cannot
 * explain is emitted as one GENERIC span — i.e. default colouring, which is
 * the charter's "never make things worse" guarantee (§5.4).
 *
 * Coloured output (charter §5.6 R9) is classified as the text the user actually
 * sees: the escapes are stripped first, exactly as detection strips them, and the
 * spans are then mapped back onto the raw line with the escape ranges re-inserted
 * as [LogSmithTokenTypes.ANSI_ESCAPE]. A coloured Logback line therefore gets the
 * same ramp as an uncoloured one, and the escapes stay out of every other token.
 */
class LineSegmenter(private val sniffer: LogFormatSniffer?) {

    /** Formats whose timestamp-less record lines have a logger, not a message, after the date. */
    private val untimedWordsAreLogger: Boolean =
        sniffer?.formatName == "java.util.logging" || sniffer?.formatName == "syslog"

    fun spans(line: CharSequence): List<Span> {
        val text = line.toString()
        if (sniffer == null) return listOf(Span(LogSmithTokenTypes.GENERIC, 0, text.length))
        if (text.isEmpty()) return emptyList()
        if (!AnsiText.containsEscape(text)) return classify(text)
        val stripped = AnsiText.stripWithMap(text)
        return withEscapes(stripped, classify(stripped.text))
    }

    /** The spans an escape-free line earns; [text] is what the sniffer is asked to read. */
    private fun classify(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()
        val sniffer = this.sniffer ?: return listOf(Span(LogSmithTokenTypes.GENERIC, 0, text.length))
        if (sniffer.matches(text)) return recordSpans(text)
        if (sniffer.matchesContinuation(text)) return continuationSpans(text)
        return listOf(Span(LogSmithTokenTypes.GENERIC, 0, text.length))
    }

    /**
     * Puts the escape sequences back: each becomes one [LogSmithTokenTypes.ANSI_ESCAPE] span, and
     * a span the stripped text earned is cut where an escape interrupts it. The result covers the
     * raw line exactly, in ascending order, with no empty span — what the lexer requires.
     */
    private fun withEscapes(stripped: Stripped, spans: List<Span>): List<Span> {
        val escapes = AnsiText.escapeRanges(stripped)
        val result = ArrayList<Span>(spans.size + escapes.size)
        var next = 0
        var pos = 0
        for (span in spans) {
            val end = stripped.rawOffset(span.end)
            while (pos < end) {
                val escape = escapes.getOrNull(next)
                if (escape != null && escape.first <= pos) {
                    result += Span(LogSmithTokenTypes.ANSI_ESCAPE, escape.first, escape.last + 1)
                    pos = escape.last + 1
                    next++
                } else {
                    val stop = if (escape == null) end else minOf(end, escape.first)
                    result += Span(span.type, pos, stop)
                    pos = stop
                }
            }
        }
        while (next < escapes.size) {
            result += Span(LogSmithTokenTypes.ANSI_ESCAPE, escapes[next].first, escapes[next].last + 1)
            next++
        }
        return result
    }

    private fun recordSpans(text: String): List<Span> {
        val sniffer = this.sniffer ?: return listOf(Span(LogSmithTokenTypes.GENERIC, 0, text.length))
        val result = ArrayList<Span>()
        var pos = 0
        sniffer.timestamp?.matcher(text)?.let { m ->
            if (m.lookingAt()) {
                result += Span(LogSmithTokenTypes.TIMESTAMP, 0, m.end())
                pos = m.end()
            }
        }
        val levelMatch = SegmentPatterns.LEVEL_SEARCH.matcher(text)
        var levelStart = Int.MAX_VALUE
        var levelEnd = -1
        if (levelMatch.find(pos)) {
            levelStart = levelMatch.start()
            levelEnd = levelMatch.end()
        }
        var messageStarted = false
        var levelDone = false
        while (pos < text.length) {
            val c = text[pos]
            if (Character.isWhitespace(c)) {
                var end = pos
                while (end < text.length && Character.isWhitespace(text[end])) end++
                result += Span(LogSmithTokenTypes.WS, pos, end)
                pos = end
                continue
            }
            if (pos == levelStart) {
                result += Span(rampFor(text.substring(levelStart, levelEnd)), levelStart, levelEnd)
                pos = levelEnd
                levelStart = Int.MAX_VALUE
                levelDone = true
                continue
            }
            if (c == '[') {
                var end = pos
                while (end < text.length && text[end] != ']') end++
                if (end < text.length) end++
                val inside = text.substring(pos + 1, (end - 1).coerceAtLeast(pos + 1))
                val ts = sniffer.timestamp
                val bracketLevel = if (!messageStarted) bracketLevelToken(inside) else null
                when {
                    ts != null && ts.matcher(inside).matches() ->
                        result += Span(LogSmithTokenTypes.TIMESTAMP, pos, end)
                    bracketLevel != null -> {
                        result += Span(rampFor(bracketLevel), pos, end)
                        levelStart = Int.MAX_VALUE
                        levelDone = true
                    }
                    else -> {
                        result += Span(
                            if (messageStarted) LogSmithTokenTypes.MESSAGE else LogSmithTokenTypes.THREAD,
                            pos, end
                        )
                        if (levelStart != Int.MAX_VALUE && levelStart in pos..end) {
                            // The pre-scan hit a level-like word inside this bracket
                            // (thread names may contain "error"); look for the real one.
                            levelStart = Int.MAX_VALUE
                            if (levelMatch.find(end)) {
                                levelStart = levelMatch.start()
                                levelEnd = levelMatch.end()
                            }
                        }
                    }
                }
                pos = end
                continue
            }
            if (c == '"') {
                var end = pos + 1
                while (end < text.length && text[end] != '"') end++
                if (end < text.length) end++
                result += Span(LogSmithTokenTypes.MESSAGE, pos, end)
                messageStarted = true
                pos = end
                continue
            }
            var end = pos
            while (end < text.length && !Character.isWhitespace(text[end]) &&
                text[end] != '[' && text[end] != '"'
            ) end++
            if (end == pos) {
                result += Span(LogSmithTokenTypes.MESSAGE, pos, pos + 1)
                pos++
                continue
            }
            if (!messageStarted && levelStart != Int.MAX_VALUE && levelStart >= pos && levelEnd <= end) {
                // Word carries the level token inside it, e.g. `production.ERROR:`.
                if (levelStart > pos) result += Span(LogSmithTokenTypes.LOGGER, pos, levelStart)
                result += Span(rampFor(text.substring(levelStart, levelEnd)), levelStart, levelEnd)
                if (levelEnd < end) result += Span(LogSmithTokenTypes.MESSAGE, levelEnd, end)
                messageStarted = true
                levelDone = true
                levelStart = Int.MAX_VALUE
                pos = end
                continue
            }
            val word = text.substring(pos, end)
            val beforeLevel = !levelDone && pos < levelStart
            val isDotted = word.any { it == '.' }
            result += Span(
                when {
                    beforeLevel -> LogSmithTokenTypes.LOGGER
                    !messageStarted && isDotted -> LogSmithTokenTypes.LOGGER
                    untimedWordsAreLogger && !messageStarted -> LogSmithTokenTypes.LOGGER
                    else -> LogSmithTokenTypes.MESSAGE
                },
                pos, end
            )
            if (!beforeLevel) messageStarted = true
            pos = end
        }
        return result
    }

    /** Level word forming the whole bracket interior, or after its last colon (`core:error`). */
    private fun bracketLevelToken(inside: String): String? {
        val token = inside.trim().substringAfterLast(':').trim()
        return if (token in SegmentPatterns.ALL_LEVEL_WORDS) token else null
    }

    private fun continuationSpans(text: String): List<Span> {
        val m = SegmentPatterns.LEVEL_PREFIX.matcher(text)
        if (m.matches()) {
            return listOf(
                Span(rampFor(m.group(1)), 0, m.end(1)),
                Span(LogSmithTokenTypes.MESSAGE, m.end(1), text.length),
            )
        }
        return listOf(Span(LogSmithTokenTypes.GENERIC, 0, text.length))
    }

    private fun rampFor(level: String): IElementType = when {
        level in SegmentPatterns.ERROR_WORDS -> LogSmithTokenTypes.LEVEL_ERROR
        level in SegmentPatterns.WARN_WORDS -> LogSmithTokenTypes.LEVEL_WARN
        level in SegmentPatterns.DEBUG_WORDS -> LogSmithTokenTypes.LEVEL_DEBUG
        else -> LogSmithTokenTypes.LEVEL_INFO
    }
}

internal object SegmentPatterns {

    /**
     * Level word anywhere in a record line, guarded so it does not fire inside
     * a dotted logger name like `MyErrorHandler` (letters before/after reject it),
     * but does fire on `production.ERROR:` and inside `[INFO]`.
     */
    val LEVEL_SEARCH: Pattern = Pattern.compile(
        "(?<![A-Za-z])(?:TRACE|DEBUG|INFO|NOTICE|WARNING|WARN|ERROR|SEVERE|FATAL|CRITICAL|ALERT|EMERGENCY" +
            "|INF|WRN|ERR|DBG|TRC|FTL" +
            "|warning|notice|error|warn|emerg|crit|info|debug|dbug|trce|fail|eror|ftl)(?![A-Za-z])"
    )

    /**
     * Continuation lines that begin with a bare level and colon: `SEVERE: ...`
     * (JUL), `WARNING:root: ...` (python default form).
     */
    val LEVEL_PREFIX: Pattern = Pattern.compile(
        "(TRACE|DEBUG|INFO|NOTICE|WARNING|WARN|ERROR|SEVERE|FATAL|CRITICAL|ALERT|EMERGENCY" +
            "|FINER|FINEST|FINE|CONFIG)(?::[\\w.]+)?: .*"
    )

    val ERROR_WORDS: Set<String> = setOf(
        "ERROR", "SEVERE", "FATAL", "CRITICAL", "ALERT", "EMERGENCY", "ERR", "FTL",
        "fail", "eror", "ftl", "crit", "emerg", "error",
    )
    val WARN_WORDS: Set<String> = setOf("WARN", "WARNING", "NOTICE", "WRN", "warn", "warning", "notice")
    val DEBUG_WORDS: Set<String> = setOf(
        "DEBUG", "TRACE", "DBG", "TRC", "FINE", "FINER", "FINEST", "debug", "dbug", "trce",
    )
    val INFO_WORDS: Set<String> = setOf("INFO", "INF", "CONFIG", "info", "inf")
    val ALL_LEVEL_WORDS: Set<String> = ERROR_WORDS + WARN_WORDS + DEBUG_WORDS + INFO_WORDS
}
