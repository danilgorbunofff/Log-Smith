package com.danilgorbunofff.logsmith.live

import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.danilgorbunofff.logsmith.sniff.FormatScorer
import com.danilgorbunofff.logsmith.sniff.FormatStats
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.danilgorbunofff.logsmith.sniff.LogScanner

/**
 * The live tail of a growing log (charter §8 Day 9, R9), as pure logic: what one document
 * change means for a tail that is already following the file, and how appended lines extend
 * the counts of the format that already won. No platform imports — the session does the
 * wiring, this decides.
 *
 * The tail follows the **document**, not the file. An appended write on disk reaches the open
 * editor as a pure insert of the new text at the end of the document, so the appended
 * characters arrive already decoded, already in the coordinate space of the line index, and
 * need no byte offset, no charset and no file read of our own.
 */

/** What one document change means for a tail that has consumed the first [covered] characters. */
sealed interface TailGrowth {

    /** `[from, to)` is new text after everything the tail has already consumed. */
    data class Appended(val from: Int, val to: Int) : TailGrowth

    /** The change rewrote text the tail had consumed, or arrived while the tail was not trusted. */
    object Untrusted : TailGrowth

    companion object {
        /**
         * Only a pure insertion exactly at [covered] extends a trusted tail. Every other change —
         * an edit, a deletion, a rewrite of the same length — leaves the consumed prefix, and with
         * it the line index, describing text that is no longer there, so it is reported instead of
         * patched.
         */
        fun classify(covered: Int, offset: Int, oldLength: Int, newLength: Int): TailGrowth =
            if (covered >= 0 && oldLength == 0 && offset == covered) {
                Appended(offset, offset + newLength)
            } else {
                Untrusted
            }
    }
}

/**
 * Counts appended lines against the format that already won, normalizing exactly as first
 * detection does ([FormatScorer]: `\r` trimmed, ANSI escapes stripped, blank lines ignored),
 * so a tail cannot disagree with the scan it extends.
 *
 * A line is counted only once its terminator has arrived: a feed ending mid-line holds the
 * fragment back and joins it to the next feed, so a line split across two writes is counted
 * once rather than twice. The held fragment is capped at [LogScanner.MAX_LINE_CHARS], the same
 * bound a first scan puts on one line.
 */
class TailClassifier(private val sniffer: LogFormatSniffer) {

    private val scorer = FormatScorer(listOf(sniffer))
    private val pending = StringBuilder()

    /** Feeds appended document text: exactly the characters that were added, in order. */
    fun accept(text: CharSequence) {
        var start = 0
        while (true) {
            val newline = newlineIn(text, start)
            if (newline < 0) break
            emit(text, start, newline)
            start = newline + 1
        }
        hold(text, start, text.length)
    }

    /** Cumulative counts over the appended lines, or null while none of them was non-blank. */
    fun stats(): FormatStats? = scorer.statsFor(sniffer)

    private fun newlineIn(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '\n') return i
            i++
        }
        return -1
    }

    private fun emit(text: CharSequence, start: Int, end: Int) {
        if (pending.isEmpty()) {
            scorer.onLine(text.subSequence(start, end).toString())
            return
        }
        hold(text, start, end)
        scorer.onLine(pending.toString())
        pending.setLength(0)
    }

    /** Buffers text up to the per-line cap; past it the line is truncated, as a scan truncates it. */
    private fun hold(text: CharSequence, start: Int, end: Int) {
        val room = LogScanner.MAX_LINE_CHARS - pending.length
        if (room <= 0 || start >= end) return
        pending.append(text, start, start + minOf(end - start, room))
    }
}

/**
 * Adds the tail's counts to the counts of the first scan of the same file, keeping the format
 * and the note that scan reported. The format is never re-derived: appended lines that explain
 * nothing show up as a falling percentage instead of a quiet re-sniff (charter §5.1).
 */
fun mergeStats(base: FormatStats, tail: FormatStats?): FormatStats =
    if (tail == null) {
        base
    } else {
        base.copy(matched = base.matched + tail.matched, scanned = base.scanned + tail.scanned)
    }

/**
 * The status result after appended lines were counted. Only a claimed format grows — a file no
 * format explained keeps its verdict, an unreadable one its reason.
 */
fun mergeResult(base: DetectionResult, tail: FormatStats?): DetectionResult =
    if (tail != null && base is DetectionResult.Matched) {
        DetectionResult.Matched(mergeStats(base.stats, tail))
    } else {
        base
    }
