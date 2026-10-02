package com.danilgorbunofff.logsmith.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests of the Day 8 filter layer: level parsing, per-line facts classification,
 * fold planning and the F2 error walk. No platform fixtures.
 */
class PureFilterTest {

    // ---- LogLevel.parse ---------------------------------------------------------------

    @Test fun parseResolvesCanonicalWords() {
        assertEquals(LogLevel.DEBUG, LogLevel.parse("DEBUG"))
        assertEquals(LogLevel.INFO, LogLevel.parse("INFO"))
        assertEquals(LogLevel.WARN, LogLevel.parse("WARN"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("ERROR"))
    }

    @Test fun parseResolvesVendorAndLowercaseForms() {
        assertEquals(LogLevel.DEBUG, LogLevel.parse("TRACE"))
        assertEquals(LogLevel.DEBUG, LogLevel.parse("FINEST"))
        assertEquals(LogLevel.DEBUG, LogLevel.parse("debug"))
        assertEquals(LogLevel.INFO, LogLevel.parse("CONFIG"))
        assertEquals(LogLevel.WARN, LogLevel.parse("WARNING"))
        assertEquals(LogLevel.WARN, LogLevel.parse("notice"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("SEVERE"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("FATAL"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("fail"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("error"))
    }

    @Test fun parseResolvesThreeLetterAbbreviations() {
        assertEquals(LogLevel.ERROR, LogLevel.parse("ERR"))
        assertEquals(LogLevel.WARN, LogLevel.parse("WRN"))
        assertEquals(LogLevel.INFO, LogLevel.parse("INF"))
        assertEquals(LogLevel.DEBUG, LogLevel.parse("DBG"))
    }

    @Test fun parseRejectsNonLevelWords() {
        assertNull(LogLevel.parse("executor"))
        assertNull(LogLevel.parse("unsupported"))
        assertNull(LogLevel.parse("x"))
        assertNull(LogLevel.parse(""))
    }

    // ---- LogLineFacts.classify --------------------------------------------------------

    @Test fun timestampedRecordLinesAreRecords() {
        val facts = LogLineFacts.classify("2026-02-05 10:44:55.101 [qtp5-1] ERROR app.service - boom")
        assertTrue(LogLineFacts.isRecordStart(facts))
        assertEquals(LogLevel.ERROR, LogLineFacts.levelOf(facts))
    }

    @Test fun prefixLinesAreRecords() {
        val severe = LogLineFacts.classify("SEVERE: Cannot open file /etc/app.cfg")
        assertTrue(LogLineFacts.isRecordStart(severe))
        assertEquals(LogLevel.ERROR, LogLineFacts.levelOf(severe))

        val warning = LogLineFacts.classify("WARNING:root: cache is cold")
        assertTrue(LogLineFacts.isRecordStart(warning))
        assertEquals(LogLevel.WARN, LogLineFacts.levelOf(warning))
    }

    @Test fun continuationLinesHaveNoLevel() {
        assertEquals(0, LogLineFacts.classify("\tat com.example.service.UserService.sendWelcomeEmail(UserService.java:128)").toInt())
        assertEquals(0, LogLineFacts.classify("java.lang.NullPointerException: Cannot invoke \"User.getEmail()\"").toInt())
        assertEquals(0, LogLineFacts.classify("").toInt())
        assertEquals(0, LogLineFacts.classify("     ").toInt())
    }

    @Test fun messageLevelWordsAreWordGuarded() {
        // `MyErrorHandler` contains "error" with letters around it: not a level.
        assertEquals(0, LogLineFacts.classify("2026-02-05 10:44:55.101 MyErrorHandler Hello").toInt())
        // A standalone lowercase `error` in the message is the same heuristic the lexer uses.
        val hit = LogLineFacts.classify("2026-02-05 10:44:55.101 dao.upsert returned error for row 7")
        assertTrue(LogLineFacts.isRecordStart(hit))
        assertEquals(LogLevel.ERROR, LogLineFacts.levelOf(hit))
    }

    @Test fun encodingRoundTrip() {
        for (level in LogLevel.entries) {
            val byte = LogLineFacts.classify(level.name + ": record")
            assertTrue(LogLineFacts.isRecordStart(byte))
            assertEquals(level, LogLineFacts.levelOf(byte))
        }
        assertEquals(LogLevel.ERROR, LogLevel.entries[3])
        assertTrue(LogLineFacts.isError(LogLineFacts.classify("ERROR: x")))
        for (level in LogLevel.entries - LogLevel.ERROR) {
            assertTrue(!LogLineFacts.isError(LogLineFacts.classify(level.name + ": x")))
        }
    }

    // ---- FilterFoldPlan.plan ----------------------------------------------------------

    /** 10 lines: ERROR record, 2 continuations, INFO record, 3 continuations, ERROR, 2 continuations. */
    private val demoFacts = byteArrayOf(
        LogLineFacts.classify("2026-02-05 10:44:55.101 ERROR first"),
        0, 0,
        LogLineFacts.classify("2026-02-05 10:44:55.200 INFO second"),
        0, 0, 0,
        LogLineFacts.classify("2026-02-05 10:44:55.300 ERROR third"),
        0, 0,
    )

    @Test fun defaultStatePlansNoFolds() {
        val plan = FilterFoldPlan.plan(demoFacts, FilterState()) { null }
        assertTrue(plan.isEmpty())
    }

    @Test fun levelOnlyFilterHidesNonMatchingRecordsAndTheirContinuations() {
        val plan = FilterFoldPlan.plan(demoFacts, FilterState(levels = setOf(LogLevel.ERROR))) { null }
        assertEquals(listOf(FilterFoldPlan.FoldRun(3, 6)), plan)
    }

    @Test fun errorOnlyFilterKeepsErrorRecordsAndTheirStacks() {
        val plan = FilterFoldPlan.plan(demoFacts, FilterState(levels = setOf(LogLevel.ERROR, LogLevel.WARN))) { null }
        assertEquals(listOf(FilterFoldPlan.FoldRun(3, 6)), plan)
    }

    @Test fun textFilterMatchesRecordLinesOnly() {
        var looked = ArrayList<Int>()
        val textful = byteArrayOf(
            LogLineFacts.classify("INFO alpha"),
            0,
            LogLineFacts.classify("INFO beta"),
        )
        val plan = FilterFoldPlan.plan(textful, FilterState(text = "beta")) { line ->
            looked += line
            when (line) {
                0 -> "INFO alpha"
                1 -> "\tat frames"
                else -> "INFO beta"
            }
        }
        assertEquals(listOf(0, 2), looked)
        assertEquals(listOf(FilterFoldPlan.FoldRun(0, 1)), plan)
    }

    @Test fun textFilterWithNoHitsFoldsEverything() {
        val plan = FilterFoldPlan.plan(demoFacts, FilterState(text = "nothing-here")) { "line" }
        assertEquals(listOf(FilterFoldPlan.FoldRun(0, 9)), plan)
    }

    @Test fun runsAtEdgesAndEof() {
        val allInfo = ByteArray(5) { LogLineFacts.classify("INFO fill") }
        assertEquals(listOf(FilterFoldPlan.FoldRun(0, 4)), FilterFoldPlan.plan(allInfo, FilterState(levels = setOf(LogLevel.ERROR))) { null })

        val headHidden = byteArrayOf(
            LogLineFacts.classify("INFO head"),
            LogLineFacts.classify("ERROR head"),
        )
        assertEquals(
            listOf(FilterFoldPlan.FoldRun(0, 0)),
            FilterFoldPlan.plan(headHidden, FilterState(levels = setOf(LogLevel.ERROR))) { null },
        )
    }

    @Test fun leadInContinuationsStayVisible() {
        val leadIn = byteArrayOf(0, 0, LogLineFacts.classify("INFO only"))
        assertEquals(listOf(FilterFoldPlan.FoldRun(2, 2)), FilterFoldPlan.plan(leadIn, FilterState(levels = setOf(LogLevel.ERROR))) { null })
    }

    @Test fun alternatingRecordsProduceSingleLineRuns() {
        val alternating = byteArrayOf(
            LogLineFacts.classify("ERROR one"),
            LogLineFacts.classify("INFO two"),
            LogLineFacts.classify("ERROR three"),
        )
        val plan = FilterFoldPlan.plan(alternating, FilterState(levels = setOf(LogLevel.ERROR))) { null }
        assertEquals(
            listOf(FilterFoldPlan.FoldRun(1, 1)),
            plan,
        )
    }

    // ---- ErrorNavigator ---------------------------------------------------------------

    private val errors = byteArrayOf(
        0,
        LogLineFacts.classify("ERROR a"),
        0, 0,
        LogLineFacts.classify("INFO mid"),
        LogLineFacts.classify("ERROR b"),
        0, 0, 0,
    )

    @Test fun navigatorWalksForwardAndBackward() {
        assertEquals(5, ErrorNavigator.next(errors, 1, forward = true))
        assertEquals(1, ErrorNavigator.next(errors, 5, forward = true))
        assertEquals(5, ErrorNavigator.next(errors, 1, forward = false))
    }

    @Test fun navigatorWrapsAround() {
        assertEquals(1, ErrorNavigator.next(errors, 8, forward = true))
        assertEquals(5, ErrorNavigator.next(errors, 0, forward = false))
    }

    @Test fun navigatorReturnsNullWithoutErrors() {
        val noErrors = ByteArray(6) { LogLineFacts.classify("INFO calm") }
        assertNull(ErrorNavigator.next(noErrors, 2, forward = true))
        assertNull(ErrorNavigator.next(noErrors, 2, forward = false))
    }

    @Test fun navigatorHandlesEmptyAndSingleLines() {
        assertNull(ErrorNavigator.next(byteArrayOf(), 0, forward = true))
        val singleError = byteArrayOf(LogLineFacts.classify("ERROR alone"))
        assertEquals(0, ErrorNavigator.next(singleError, 0, forward = true)!!)
        assertEquals(0, ErrorNavigator.next(singleError, 0, forward = false)!!)
    }

    @Test fun singleErrorAlwaysWrapsBackToItself() {
        val only = byteArrayOf(0, LogLineFacts.classify("ERROR only"), 0, 0)
        assertEquals(1, ErrorNavigator.next(only, 1, forward = true)!!)
        assertEquals(1, ErrorNavigator.next(only, 1, forward = false)!!)
    }
}
