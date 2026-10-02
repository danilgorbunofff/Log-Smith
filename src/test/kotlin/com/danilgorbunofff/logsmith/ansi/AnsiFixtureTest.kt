package com.danilgorbunofff.logsmith.ansi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parser against bytes three tools really wrote (charter Day 9 step 3), saved under
 * `testdata/ansi/` rather than hand-typed: `npm install --color=always`, a `pytest -v --color=yes`
 * run, and `docker logs` of a container whose app logged through `rich` with `COLORTERM=truecolor`.
 * Between them they carry the 16 base colours, the 256-colour palette, direct colour, bold, dim,
 * underline, a background, `22`/`39` partial resets and the `39;49;00` triple reset.
 *
 * The assertions are facts about those exact bytes, so regenerating a fixture and quietly losing
 * a form — truecolour, or npm's `22` — fails here instead of passing silently.
 */
class AnsiFixtureTest {

    private val esc = AnsiText.ESC

    private val fixtures = listOf("npm-install.log", "pytest-verbose.log", "docker-logs.log")

    private fun fixture(name: String): String {
        val file = File("testdata/ansi/$name")
        assertTrue("missing fixture ${file.path}", file.isFile)
        return file.readText()
    }

    private fun lines(name: String) = fixture(name).split('\n')

    /** The runs of one line, keyed by the text they cover. */
    private fun runsOf(line: String): Map<String, AnsiStyle> =
        AnsiText.runs(line).associate { line.substring(it.start, it.end) to it.style }

    /** The style the fixture paints one word with — the word must be covered by a single run. */
    private fun styleOf(name: String, word: String): AnsiStyle {
        val line = lines(name).first { it.contains(word) }
        val at = line.indexOf(word)
        val run = AnsiText.runs(line).first { at >= it.start && at < it.end }
        assertEquals("the run must cover exactly $word", word, line.substring(run.start, run.end))
        return run.style
    }

    // ------------------------------------------------------------ npm install

    @Test
    fun `npm capture keeps bold, the yellow warn and the bright blue word apart`() {
        val line = lines("npm-install.log").first()
        val runs = runsOf(line)
        assertEquals(AnsiStyle(bold = true), runs["npm"])
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(33)), runs["warn"])
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(94)), runs["deprecated"])
        assertEquals("npm's `22` and `39` must end both runs", 3, AnsiText.runs(line).size)
        assertFalse(
            "the deprecation text after `39` must be plain",
            runs.keys.any { it.contains("har-validator") },
        )
    }

    @Test
    fun `npm capture strips to the warnings a terminal-less user sees`() {
        val stripped = AnsiText.strip(fixture("npm-install.log"))
        assertFalse(AnsiText.containsEscape(stripped))
        assertTrue(stripped.startsWith("npm warn deprecated har-validator@5.1.5: this library is no longer supported"))
        assertTrue(stripped.endsWith("added 49 packages in 29s\n"))
    }

    // ------------------------------------------------------------ pytest

    @Test
    fun `pytest capture marks passes green and failures red`() {
        val passed = lines("pytest-verbose.log").first { it.contains("test_addition") }
        val passedRuns = AnsiText.runs(passed)
        assertEquals("`PASSED` and the progress column are coloured separately", 2, passedRuns.size)
        for (run in passedRuns) assertEquals(AnsiStyle(foreground = AnsiColor.Named(32)), run.style)
        assertEquals("PASSED", passed.substring(passedRuns[0].start, passedRuns[0].end))

        val failed = lines("pytest-verbose.log").first { it.contains("test_missing_key") }
        val failedRuns = AnsiText.runs(failed)
        assertEquals("FAILED", failed.substring(failedRuns[0].start, failedRuns[0].end))
        for (run in failedRuns) assertEquals(AnsiStyle(foreground = AnsiColor.Named(31)), run.style)
    }

    @Test
    fun `pytest traceback keeps its colours through the triple reset`() {
        val line = lines("pytest-verbose.log").first { it.startsWith("    ") && it.contains("test_wrong_expectation") }
        val runs = runsOf(line)
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(94)), runs["def"])
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(90)), runs[" "])
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(92)), runs["test_wrong_expectation"])
        assertFalse(
            "`39;49;00` must return the syntax to plain text",
            runs.keys.any { it.contains("()") },
        )
    }

    @Test
    fun `pytest assertion line is bold red from two sequences in a row`() {
        val line = lines("pytest-verbose.log").first { it.contains("E       AssertionError") }
        val runs = AnsiText.runs(line)
        assertEquals("two sequences before one word are one run", 1, runs.size)
        assertEquals(AnsiStyle(foreground = AnsiColor.Named(31), bold = true), runs[0].style)
        assertTrue(line.substring(runs[0].start, runs[0].end).startsWith("E       AssertionError"))
    }

    // ------------------------------------------------------------ docker logs

    @Test
    fun `docker capture carries bold, dim, underline, a background and both extended colours`() {
        assertEquals(
            AnsiStyle(foreground = AnsiColor.Named(32), bold = true),
            styleOf("docker-logs.log", "INFO"),
        )
        assertEquals(
            AnsiStyle(foreground = AnsiColor.Named(33), bold = true),
            styleOf("docker-logs.log", "WARN"),
        )
        assertEquals(
            AnsiStyle(foreground = AnsiColor.Named(31), bold = true),
            styleOf("docker-logs.log", "ERROR"),
        )
        assertEquals(AnsiStyle(dim = true), styleOf("docker-logs.log", "TRACE"))
        assertEquals(AnsiStyle(underline = true), styleOf("docker-logs.log", "DEPLOY"))
        assertEquals(
            AnsiStyle(foreground = AnsiColor.Rgb(255, 136, 0)),
            styleOf("docker-logs.log", "NOTICE"),
        )
        assertEquals(
            AnsiStyle(foreground = AnsiColor.Indexed(196)),
            styleOf("docker-logs.log", "CRITICAL"),
        )
        assertEquals(
            AnsiStyle(foreground = AnsiColor.Named(34), background = AnsiColor.Named(37)),
            styleOf("docker-logs.log", "BANNER"),
        )
    }

    @Test
    fun `a fixture line without escapes has no runs at all`() {
        assertEquals(emptyList<AnsiRun>(), AnsiText.runs(lines("docker-logs.log").first()))
        assertEquals("waiting for dependencies...", lines("docker-logs.log").first())
    }

    // ------------------------------------------------------------ properties over every fixture

    @Test
    fun `the fixtures still carry every escape form the charter names`() {
        val all = fixtures.joinToString("\n") { fixture(it) }
        val forms = listOf(
            "${esc}[38;5;196m", "${esc}[38;2;255;136;0m", "${esc}[34;47m",
            "${esc}[1;32m", "${esc}[2m", "${esc}[4m",
            "${esc}[22m", "${esc}[39m", "${esc}[39;49;00m",
            "${esc}[94m", "${esc}[90m", "${esc}[0m",
        )
        for (form in forms) assertTrue("the fixtures must keep ${form.replace("$esc", "ESC")}", all.contains(form))
    }

    @Test
    fun `escape ranges account for exactly the bytes the tools sent`() {
        for (name in fixtures) {
            val text = fixture(name)
            val stripped = AnsiText.strip(text)
            assertFalse("$name must strip clean", AnsiText.containsEscape(stripped))
            var covered = 0
            var previousEnd = -1
            for (range in AnsiText.escapeRanges(text)) {
                assertTrue("$name: ranges must ascend", range.first > previousEnd)
                assertEquals("$name: a range starts on the escape", esc, text[range.first])
                previousEnd = range.last
                covered += range.last - range.first + 1
            }
            assertEquals("$name: ranges must cover every removed byte", text.length - stripped.length, covered)
        }
    }

    @Test
    fun `parsing line by line equals parsing the whole file`() {
        for (name in fixtures) {
            val text = fixture(name)
            val whole = AnsiText.runs(text).map { it.start to it.end }
            val perLine = ArrayList<Pair<Int, Int>>()
            var base = 0
            for (line in text.split('\n')) {
                for (run in AnsiText.runs(line)) perLine.add((base + run.start) to (base + run.end))
                base += line.length + 1
            }
            assertEquals("$name: windowed parsing must agree with whole-file parsing", whole, perLine)
            for (run in AnsiText.runs(text)) {
                assertFalse("$name: no run may cross a line", text.substring(run.start, run.end).contains('\n'))
            }
        }
    }
}
