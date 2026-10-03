package com.danilgorbunofff.logsmith.ansi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure ANSI parser (charter Day 9): runs, stripping, offset maps, escape ranges. */
class AnsiTextTest {

    private val esc = AnsiText.ESC
    private val bel = 7.toChar()

    private fun sgr(params: String) = "${esc}[$params"

    private fun ends(runs: List<AnsiRun>) = runs.map { it.start to it.end }

    // ------------------------------------------------------------ containsEscape

    @Test
    fun `containsEscape is a cheap scan`() {
        assertTrue(AnsiText.containsEscape("${sgr("32m")}hello${sgr("0m")}"))
        assertFalse(AnsiText.containsEscape("plain log line"))
        assertFalse(AnsiText.containsEscape(""))
    }

    // ------------------------------------------------------------ runs

    @Test
    fun `coloured span becomes one run ending before the reset`() {
        val text = "${sgr("31m")}ERROR: boom${sgr("0m")} and more"
        val runs = AnsiText.runs(text)
        assertEquals(listOf(5 to 16), ends(runs))
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(31)), runs[0].style)
    }

    @Test
    fun `runs cover only styled text and never the escapes`() {
        val text = "${sgr("32m")}ok${sgr("0m")}"
        val runs = AnsiText.runs(text)
        assertEquals(listOf(5 to 7), ends(runs))
    }

    @Test
    fun `state resets at every newline`() {
        val text = "${sgr("32m")}ok\nplain"
        val runs = AnsiText.runs(text)
        assertEquals(listOf(5 to 7), ends(runs))
    }

    @Test
    fun `style accumulates across sequences on one line`() {
        val text = "${sgr("3m")}it ${sgr("4m")}and u"
        val runs = AnsiText.runs(text)
        assertEquals(2, runs.size)
        assertEquals(AnsiStyle(italic = true), runs[0].style)
        assertEquals(AnsiStyle(italic = true, underline = true), runs[1].style)
        assertEquals(listOf(4 to 7, 11 to 16), ends(runs))
    }

    @Test
    fun `bold dim italic and underline are parsed`() {
        val runs = AnsiText.runs("${sgr("1;2;3;4m")}x")
        assertEquals(AnsiStyle(bold = true, dim = true, italic = true, underline = true), runs[0].style)
    }

    @Test
    fun `partial resets clear only their attribute`() {
        // `22` drops bold and `39`/`49` drop the colours, so `y` is unstyled and has no run.
        val runs = AnsiText.runs("${sgr("1;31;44m")}x${sgr("22;39;49m")}y")
        assertEquals(1, runs.size)
        assertEquals(
            AnsiStyle(foreground = AnsiColor.Named(31), background = AnsiColor.Named(34), bold = true),
            runs[0].style,
        )
        assertEquals(listOf(10 to 11), ends(runs))

        // A partial reset leaves every other attribute alone: `y` keeps the red.
        val kept = AnsiText.runs("${sgr("1;31m")}x${sgr("22m")}y")
        assertEquals(listOf(7 to 8, 13 to 14), ends(kept))
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(31)), kept[1].style)
    }

    @Test
    fun `SGR reset clears everything`() {
        val runs = AnsiText.runs("${sgr("1;31;44;4m")}x${sgr("0m")}y")
        assertEquals(listOf(12 to 13), ends(runs))
    }

    @Test
    fun `empty SGR resets like 0`() {
        val runs = AnsiText.runs("${sgr("31m")}x${sgr("")}y")
        assertEquals(listOf(5 to 6), ends(runs))
    }

    @Test
    fun `high intensity codes keep their raw SGR number`() {
        val bright = AnsiText.runs("${sgr("91m")}x")[0].style.foreground
        assertEquals(AnsiColor.Named(91), bright)
        val bg = AnsiText.runs("${sgr("103m")}x")[0].style.background
        assertEquals(AnsiColor.Named(93), bg)
    }

    @Test
    fun `256 colour codes parse to Indexed`() {
        val runs = AnsiText.runs("${sgr("38;5;196m")}x")
        assertEquals(AnsiStyle(foreground = AnsiColor.Indexed(196)), runs[0].style)
        val bg = AnsiText.runs("${sgr("48;5;17m")}x")[0].style.background
        assertEquals(AnsiColor.Indexed(17), bg)
    }

    @Test
    fun `truecolour codes parse to Rgb`() {
        val runs = AnsiText.runs("${sgr("38;2;12;34;56m")}x")
        assertEquals(AnsiStyle(foreground = AnsiColor.Rgb(12, 34, 56)), runs[0].style)
        val bg = AnsiText.runs("${sgr("48;2;250;100;0m")}x")[0].style.background
        assertEquals(AnsiColor.Rgb(250, 100, 0), bg)
    }

    @Test
    fun `colon sub-parameter separators are treated like semicolons`() {
        val runs = AnsiText.runs("${sgr("38:5:196m")}x")
        assertEquals(AnsiStyle(foreground = AnsiColor.Indexed(196)), runs[0].style)
        assertEquals(listOf(11 to 12), ends(runs))
    }

    @Test
    fun `ITU direct colour skips the colour-space id`() {
        val expected = AnsiStyle(foreground = AnsiColor.Rgb(255, 0, 0))
        assertEquals(expected, AnsiText.runs("${sgr("38:2::255:0:0m")}x")[0].style)
        assertEquals(expected, AnsiText.runs("${sgr("38:2:0:255:0:0m")}x")[0].style)
        assertEquals("the short colon form still works", expected, AnsiText.runs("${sgr("38:2:255:0:0m")}x")[0].style)
    }

    @Test
    fun `malformed extended colour drops the rest of the sequence`() {
        // `38;5` with no value: no partial style, sequence consumed.
        assertTrue(AnsiText.runs("${sgr("38;5")}x").isEmpty())
        assertTrue(AnsiText.runs("${sgr("38;2;12")}x").isEmpty())
    }

    @Test
    fun `reverse video and blink are ignored not fatal`() {
        assertTrue(AnsiText.runs("${sgr("7;5m")}x").isEmpty())
    }

    // ------------------------------------------------------------ other escapes

    @Test
    fun `non SGR CSI sequences are consumed without style change`() {
        val text = "${esc}[2J${esc}[?25l${esc}[Kok"
        assertTrue(AnsiText.runs(text).isEmpty())
        assertEquals("ok", AnsiText.strip(text))
    }

    @Test
    fun `OSC sequences run to BEL`() {
        val text = "${esc}]0;my title${bel}ok"
        assertEquals("ok", AnsiText.strip(text))
        assertTrue(AnsiText.runs(text).isEmpty())
    }

    @Test
    fun `OSC ends at ST and at line end`() {
        assertEquals("ok", AnsiText.strip("${esc}]8;;http://x${esc}\\ok"))
        // The newline ends the OSC but stays in the text, so line numbering survives.
        assertEquals("\nok\nnext", AnsiText.strip("${esc}]0;tail\nok\nnext"))
    }

    @Test
    fun `charset designation is three bytes`() {
        assertEquals("ok", AnsiText.strip("${esc}(Bok"))
        assertEquals("ok", AnsiText.strip("${esc})0ok"))
    }

    @Test
    fun `lone ESC at end is consumed without crash`() {
        assertEquals("ok", AnsiText.strip("ok$esc"))
        assertTrue(AnsiText.runs("ok$esc").isEmpty())
        assertEquals("ok", AnsiText.strip("ok${esc}["))
        assertEquals("ok", AnsiText.strip("ok${esc}[3"))
        assertEquals("ok", AnsiText.strip("ok${esc}[3m")) // empty params = reset, no style
    }

    @Test
    fun `truncated CSI tail never renders as styled text`() {
        // A file cut mid-sequence must not leak the tail as coloured text.
        val runs = AnsiText.runs("${sgr("31m")}err\nnext${sgr("3")}")
        assertEquals(listOf(5 to 8), ends(runs))
    }

    @Test
    fun `same style across a reset stays two runs that exclude the escapes`() {
        // Two spans never share a raw edge — the escapes between them are not part of a run —
        // so they stay separate instead of merging into a range that would paint the escapes.
        val text = "${sgr("31m")}a${sgr("0m")}${sgr("31m")}b${sgr("0m")}"
        val runs = AnsiText.runs(text)
        assertEquals(listOf(5 to 6, 15 to 16), ends(runs))
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(31)), runs[0].style)
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(31)), runs[1].style)
    }

    @Test
    fun `plain text has no runs`() {
        assertTrue(AnsiText.runs("2026-10-01 INFO all good").isEmpty())
        assertTrue(AnsiText.runs("").isEmpty())
    }

    // ------------------------------------------------------------ strip

    @Test
    fun `strip removes sequences and keeps text`() {
        val text = "${sgr("31m")}ERROR: boom${sgr("0m")} and more"
        assertEquals("ERROR: boom and more", AnsiText.strip(text))
    }

    @Test
    fun `strip is idempotent`() {
        val text = "${sgr("31m")}ERROR: boom${sgr("0m")}"
        assertEquals(AnsiText.strip(text), AnsiText.strip(AnsiText.strip(text)))
    }

    @Test
    fun `docker fixture style line strips to its message`() {
        val text = "${sgr("32m")}nginx.1${sgr("0m")} | ${sgr("36m")}GET /health 200${sgr("0m")}"
        assertEquals("nginx.1 | GET /health 200", AnsiText.strip(text))
    }

    // ------------------------------------------------------------ stripWithMap

    @Test
    fun `raw offsets map stripped chars to their original position`() {
        val text = "${sgr("31m")}ERROR${sgr("0m")} boom"
        val stripped = AnsiText.stripWithMap(text)
        assertEquals("ERROR boom", stripped.text)
        assertEquals(5, stripped.rawOffset(0))
        assertEquals(9, stripped.rawOffset(4))
        assertEquals(14, stripped.rawOffset(5))
        assertEquals(19, stripped.rawOffset(10)) // sentinel: end of original text
    }

    @Test
    fun `raw offsets survive an escape before the first character`() {
        val text = "${esc}(B${sgr("31m")}x"
        val stripped = AnsiText.stripWithMap(text)
        assertEquals("x", stripped.text)
        assertEquals(8, stripped.rawOffset(0))
        assertEquals(9, stripped.rawOffset(1))
    }

    // ------------------------------------------------------------ escapeRanges

    @Test
    fun `escape ranges cover exactly the sequence bytes`() {
        val text = "${sgr("31m")}ERROR${sgr("0m")}"
        assertEquals(listOf(IntRange(0, 4), IntRange(10, 13)), AnsiText.escapeRanges(text))
    }

    @Test
    fun `plain text has no escape ranges`() {
        assertTrue(AnsiText.escapeRanges("2026-10-01 INFO all good").isEmpty())
        assertTrue(AnsiText.escapeRanges("").isEmpty())
    }

    @Test
    fun `a lone escape is one range`() {
        assertEquals(listOf(IntRange(1, 1)), AnsiText.escapeRanges("a$esc"))
    }
}
