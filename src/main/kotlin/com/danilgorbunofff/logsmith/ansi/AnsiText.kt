package com.danilgorbunofff.logsmith.ansi

/**
 * Pure ANSI escape-sequence parsing for log output (charter Day 9). Docker (`--colour`),
 * npm, pytest and friends emit SGR sequences; the plugin parses them into styled runs and
 * strips every other escape, so `←[32m` garbage never shows. No IDE imports here — this
 * file is pure-testable, and the attribute mapping lives in the highlight package.
 */
sealed interface AnsiColor {
    /** `30-37`/`90-97` foreground and `40-47`/`100-107` background, stored as the fg code (30-37/90-97). */
    data class Named(val code: Int) : AnsiColor

    /** `38;5;n` / `48;5;n` xterm-256 palette index, 0-255. */
    data class Indexed(val index: Int) : AnsiColor

    /** `38;2;r;g;b` / `48;2;r;g;b` direct colour. */
    data class Rgb(val r: Int, val g: Int, val b: Int) : AnsiColor
}

/** SGR state accumulated across one line. */
data class AnsiStyle(
    val foreground: AnsiColor? = null,
    val background: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    val isDefault: Boolean
        get() = foreground == null && background == null && !bold && !dim && !italic && !underline
}

/** A styled character range over the ORIGINAL text; escape offsets are never inside a run. */
class AnsiRun internal constructor(val start: Int, val end: Int, val style: AnsiStyle) {
    val isEmpty: Boolean get() = end <= start
    override fun toString(): String = "AnsiRun[$start..$end $style]"
}

/** A line with escape sequences removed, plus the map back to the original offsets. */
class Stripped internal constructor(val text: String, val rawIndex: IntArray) {
    /** Original-text offset for [strippedIndex]; `rawOffset(text.length)` maps to the end. */
    fun rawOffset(strippedIndex: Int): Int = rawIndex[strippedIndex]
}

object AnsiText {

    const val ESC = '\u001B'

    /** Cheap first check: any escape sequence at all? */
    fun containsEscape(text: CharSequence): Boolean {
        for (i in text.indices) if (text[i] == ESC) return true
        return false
    }

    /**
     * Styled runs over [text]. Runs never cross a `'\n'` — SGR state is deliberately reset at
     * every line end, because the lazy highlighter's windows tile the document at arbitrary
     * line boundaries and each window must produce the same styles on its own.
     */
    fun runs(text: CharSequence): List<AnsiRun> {
        val runs = ArrayList<AnsiRun>(4)
        val state = MutableStyle()
        var runStart = -1
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                closeRun(runs, state, runStart, i)
                runStart = -1
                state.reset()
                i++
            } else if (c != ESC) {
                if (runStart < 0 && !state.isDefault) runStart = i
                i++
            } else {
                closeRun(runs, state, runStart, i)
                runStart = -1
                i = sequenceEnd(text, i, state).let { if (it > i) it else i + 1 }
            }
        }
        closeRun(runs, state, runStart, n)
        return mergeAdjacent(runs)
    }

    /**
     * Removes every escape sequence from [text]. Escape-free input is returned untouched —
     * detection calls this once per line, and almost no line carries an escape.
     */
    fun strip(text: CharSequence): String {
        if (!containsEscape(text)) return text.toString()
        val out = StringBuilder(text.length)
        stripInto(text, out, null)
        return out.toString()
    }

    /** Removes escape sequences and maps every kept character (and the end) to its original offset. */
    fun stripWithMap(text: CharSequence): Stripped {
        val n = text.length
        val out = StringBuilder(n)
        val raw = IntArray(n + 1)
        val kept = stripInto(text, out, raw)
        raw[kept] = n
        return Stripped(out.toString(), raw.copyOf(kept + 1))
    }

    /** One stripping pass, recording the original offset of each kept character into [raw] when given. */
    private fun stripInto(text: CharSequence, out: StringBuilder, raw: IntArray?): Int {
        val n = text.length
        var kept = 0
        var i = 0
        while (i < n) {
            if (text[i] == ESC) {
                val next = sequenceEnd(text, i, null)
                i = if (next > i) next else i + 1
                continue
            }
            if (raw != null) raw[kept] = i
            out.append(text[i])
            kept++
            i++
        }
        return kept
    }

    /** Ranges of [text] occupied by escape sequences — used to render them invisible. */
    fun escapeRanges(text: CharSequence): List<IntRange> {
        val stripped = stripWithMap(text)
        val ranges = ArrayList<IntRange>(4)
        var prev = -1
        for (k in 0..stripped.text.length) {
            val p = stripped.rawOffset(k)
            if (p > prev + 1) ranges.add(IntRange(prev + 1, p - 1))
            prev = p
        }
        return ranges
    }

    private fun closeRun(runs: ArrayList<AnsiRun>, state: MutableStyle, runStart: Int, end: Int) {
        if (runStart in 0 until end && !state.isDefault) runs.add(AnsiRun(runStart, end, state.toStyle()))
    }

    /**
     * End offset of the escape starting at [start] (where `text[start] == ESC`), with any SGR
     * applied to [state] when it is non-null. Returns [start] when only the ESC byte itself is
     * present; incomplete sequences are consumed up to the line end without changing the style.
     */
    private fun sequenceEnd(text: CharSequence, start: Int, state: MutableStyle?): Int {
        val n = text.length
        var j = start + 1
        if (j >= n) return start
        return when (val second = text[j]) {
            '[' -> csiEnd(text, j, state)
            ']', 'P', '^', '_' -> terminatorEnd(text, j + 1)
            '(', ')', '*', '+' -> if (j + 1 < n) j + 2 else n
            else -> if (second == '\n') start else j + 1
        }
    }

    /** CSI: `ESC [` parameters intermediates final-byte; the SGR final byte is `m`. */
    private fun csiEnd(text: CharSequence, bracket: Int, state: MutableStyle?): Int {
        val n = text.length
        var k = bracket + 1
        while (k < n && text[k] in '0'..'?') k++
        while (k < n && text[k] in ' '..'/') k++
        if (k < n && text[k] in '@'..'~') {
            if (text[k] == 'm' && state != null) applySgr(text.subSequence(bracket + 1, k), state)
            return k + 1
        }
        return k
    }

    /** OSC / DCS / PM / APC payloads run to BEL, ST (`ESC \`), the line end or the text end. */
    private fun terminatorEnd(text: CharSequence, from: Int): Int {
        val n = text.length
        var i = from
        while (i < n) {
            when (val c = text[i]) {
                '\u0007' -> return i + 1
                ESC -> return if (i + 1 < n && text[i + 1] == '\\') i + 2 else i
                '\n' -> return i
                else -> i++
            }
        }
        return n
    }

    /**
     * SGR parameter walk (charter Day 9): 16-colour, 256-colour and truecolour, plus
     * bold/dim/italic/underline and the partial resets. Blink (`5`) and reverse video
     * (`7`/`27`) are ignored — reverse state cannot be undone without keeping a snapshot,
     * and coloured log output rarely needs it. `:` sub-parameter separators are treated
     * like `;`.
     */
    private fun applySgr(raw: CharSequence, s: MutableStyle) {
        if (raw.isEmpty()) {
            s.reset()
            return
        }
        val params: CharSequence = if (raw.indexOf(':') < 0) raw else raw.toString().replace(':', ';')
        val n = params.length
        var j = 0
        while (j < n) {
            var value = 0
            while (j < n && params[j] in '0'..'9') {
                value = value * 10 + (params[j] - '0')
                j++
            }
            when (value) {
                0 -> s.reset()
                1 -> s.bold = true
                2 -> s.dim = true
                3 -> s.italic = true
                4, 21 -> s.underline = true
                22 -> {
                    s.bold = false
                    s.dim = false
                }
                23 -> s.italic = false
                24 -> s.underline = false
                in 30..37 -> s.foreground = AnsiColor.Named(value)
                39 -> s.foreground = null
                in 40..47 -> s.background = AnsiColor.Named(value - 10)
                49 -> s.background = null
                in 90..97 -> s.foreground = AnsiColor.Named(value)
                in 100..107 -> s.background = AnsiColor.Named(value - 10)
                38, 48 -> j = extendedColour(value, params, j, s)
                else -> {}
            }
            if (j < n && params[j] == ';') j++ else if (j < n) j = n
        }
    }

    /** Consumes `;5;n` / `;2;r;g;b` after a `38`/`48`; returns the index after the group, or the end when malformed. */
    private fun extendedColour(code: Int, params: CharSequence, sep: Int, s: MutableStyle): Int {
        val n = params.length
        if (sep >= n || params[sep] != ';') return n
        var j = sep + 1
        var kind = 0
        while (j < n && params[j] in '0'..'9') {
            kind = kind * 10 + (params[j] - '0')
            j++
        }
        if (j >= n || params[j] != ';') return n
        return when (kind) {
            5 -> {
                j++
                var v = 0
                while (j < n && params[j] in '0'..'9') {
                    v = v * 10 + (params[j] - '0')
                    j++
                }
                if (j < n && params[j] != ';') return n
                val color = AnsiColor.Indexed(v.coerceIn(0, 255))
                if (code == 38) s.foreground = color else s.background = color
                j
            }
            2 -> {
                val rgb = IntArray(3)
                var got = 0
                while (got < 3 && j < n && params[j] == ';') {
                    j++
                    var v = 0
                    while (j < n && params[j] in '0'..'9') {
                        v = v * 10 + (params[j] - '0')
                        j++
                    }
                    rgb[got++] = v
                }
                if (j < n && params[j] != ';') return n
                if (got < 3) return n
                val color = AnsiColor.Rgb(
                    rgb[0].coerceIn(0, 255), rgb[1].coerceIn(0, 255), rgb[2].coerceIn(0, 255),
                )
                if (code == 38) s.foreground = color else s.background = color
                j
            }
            else -> n
        }
    }

    /** Merges neighbouring runs carrying the same style, so a re-styled span is one paint range. */
    private fun mergeAdjacent(runs: ArrayList<AnsiRun>): List<AnsiRun> {
        if (runs.size < 2) return runs
        val merged = ArrayList<AnsiRun>(runs.size)
        var current: AnsiRun? = null
        for (run in runs) {
            val last = current
            if (last != null && last.end == run.start && last.style == run.style) {
                current = AnsiRun(last.start, run.end, last.style)
                merged[merged.size - 1] = current
            } else {
                current = run
                merged.add(run)
            }
        }
        return merged
    }

    private class MutableStyle {
        var foreground: AnsiColor? = null
        var background: AnsiColor? = null
        var bold = false
        var dim = false
        var italic = false
        var underline = false

        val isDefault: Boolean
            get() = foreground == null && background == null && !bold && !dim && !italic && !underline

        fun reset() {
            foreground = null
            background = null
            bold = false
            dim = false
            italic = false
            underline = false
        }

        fun toStyle() = AnsiStyle(foreground, background, bold, dim, italic, underline)
    }
}
