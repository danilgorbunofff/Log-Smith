package com.danilgorbunofff.logsmith.filter

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the filter and F2 call a record, and at which level, follows the claimed format and the
 * highlighter's reading of it — not any level-shaped word anywhere in a line.
 */
class FormatAwareFactsTest {

    private val logback = BuiltinSniffers.byName.getValue("Logback / Log4j 2")
    private val segmenter = LineSegmenter(logback)

    private fun classify(line: String) = LogLineFacts.classify(line, logback, segmenter)

    @Test
    fun `a stack frame through a logging call is not a record`() {
        assertFalse(LogLineFacts.isRecordStart(classify("\tat ch.qos.logback.classic.Logger.error(Logger.java:538)")))
        assertFalse("nor without a format", LogLineFacts.isRecordStart(LogLineFacts.classify("\tat ch.qos.logback.classic.Logger.error(Logger.java:538)")))
        assertFalse(
            "a method name is not a severity",
            LogLineFacts.isRecordStart(LogLineFacts.classify("org.slf4j.Logger.error(Logger.java:10)")),
        )
    }

    @Test
    fun `a thread name does not override the record level`() {
        val byte = classify("2026-10-01 09:00:00.001 [error-reporter-1] INFO  c.e.App - all good")
        assertTrue(LogLineFacts.isRecordStart(byte))
        assertEquals(LogLevel.INFO, LogLineFacts.levelOf(byte))
        assertFalse(LogLineFacts.isError(byte))
    }

    @Test
    fun `the level is the one the highlighter colours`() {
        assertEquals(LogLevel.ERROR, LogLineFacts.levelOf(classify("2026-10-01 09:00:00.001 [main] ERROR c.e.App - info about it")))
        assertEquals(LogLevel.WARN, LogLineFacts.levelOf(classify("2026-10-01T09:00:00.001+02:00  WARN 1 --- [main] c.e.App : x")))
    }

    @Test
    fun `an ERROR filter keeps the whole stack trace of a visible error`() {
        val lines = listOf(
            "2026-10-01 09:00:00.001 [main] ERROR c.e.App - failed",
            "java.lang.IllegalStateException: boom",
            "\tat com.example.App.run(App.java:10)",
            "\tat org.slf4j.helpers.SubstituteLogger.info(SubstituteLogger.java:150)",
            "\tat com.example.App.main(App.java:5)",
            "2026-10-01 09:00:00.002 [main] INFO  c.e.App - next",
        )
        val facts = ByteArray(lines.size) { classify(lines[it]) }
        val plan = FilterFoldPlan.plan(facts, FilterState(setOf(LogLevel.ERROR), "")) { lines[it] }
        assertEquals("only the INFO record may be hidden", listOf(FilterFoldPlan.FoldRun(5, 5)), plan)
    }

    @Test
    fun `a record without a severity is never hidden for its level`() {
        val byte = LogLineFacts.recordByte(null)
        assertTrue(LogLineFacts.isRecordStart(byte))
        assertNull(LogLineFacts.levelOf(byte))
        assertFalse(LogLineFacts.isError(byte))
        val plan = FilterFoldPlan.plan(byteArrayOf(byte), FilterState(setOf(LogLevel.ERROR), "")) { "GET /" }
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a JUL level line announces the level of its header`() {
        assertEquals(LogLevel.ERROR, LogLineFacts.continuationLevel("SEVERE: Cannot open file"))
        assertNull(LogLineFacts.continuationLevel("\tat com.example.App.main(App.java:5)"))
    }

    @Test
    fun `coloured lines are classified by their text`() {
        val esc = "\u001B"
        val byte = classify("$esc[32m2026-10-01 09:00:00.001 [main] ERROR$esc[0m c.e.App - boom")
        assertTrue(LogLineFacts.isError(byte))
    }
}
