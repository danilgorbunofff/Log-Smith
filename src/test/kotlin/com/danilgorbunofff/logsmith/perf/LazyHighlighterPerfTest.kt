package com.danilgorbunofff.logsmith.perf

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithLazyHighlighter
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.LogFormatSniffer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * §5.3 (R5): opening a 20 MB log — the largest the platform will load into an editor at
 * all — must paint its first screen in under a second, and scrolling must never block the
 * EDT for more than 50 ms. Gated behind `-Plogsmith.perf=true` with a plain early return:
 * a JUnit-3 `TestCase` reports an assumption violation as a failure, not a skip.
 */
class LazyHighlighterPerfTest : BasePlatformTestCase() {

    private class CountingSniffer(private val inner: LogFormatSniffer) : LogFormatSniffer by inner {
        var calls = 0
        override fun matches(line: String): Boolean {
            calls++
            return inner.matches(line)
        }
    }

    fun `test a 20 MB log paints fast and stays cheap to scroll`() {
        if (System.getProperty("logsmith.perf") != "true") return
        val document = EditorFactory.getInstance().createDocument(logText(20 shl 20))
        val sniffer = CountingSniffer(BuiltinSniffers.byName.getValue("Logback / Log4j 2"))
        val highlighter = LogSmithLazyHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            EditorColorsManager.getInstance().globalScheme,
        )
        highlighter.setEditor(object : HighlighterClient {
            override fun getProject() = this@LazyHighlighterPerfTest.project
            override fun repaint(start: Int, end: Int) {}
            override fun getDocument() = document
        })
        highlighter.setText(document.immutableCharSequence)

        // What the user waits for when a 20 MB file opens: one window, not one document.
        val paintStarted = System.nanoTime()
        val iterator = highlighter.createIterator(0)
        val windowMs = (System.nanoTime() - paintStarted) / 1e6
        val attributesStarted = System.nanoTime()
        val firstPaint = iterator.textAttributes
        val attributesMs = (System.nanoTime() - attributesStarted) / 1e6
        val paintMs = windowMs + attributesMs
        println(
            "PERF paint: %.0f MB, %,d lines, first paint %.1f ms (window %.1f + attributes %.1f), %d sniffer calls".format(
                document.textLength / 1e6, document.lineCount, paintMs, windowMs, attributesMs, sniffer.calls,
            ),
        )

        assertNotNull("the first paint must resolve attributes", firstPaint)
        assertTrue("one window may not lex more than its own lines", sniffer.calls <= 2 * LogSmithLazyHighlighter.WINDOW_LINES)
        assertTrue("time to first paint must be under a second (was %.1f ms)".format(paintMs), paintMs < 1_000)

        // Scrolling: every jump rebuilds exactly one window, and that is the EDT block.
        // The first few builds of a fresh JVM pay class loading and JIT (the IDE has long
        // since done that by the time a user scrolls), so a warm-up pass runs first and the
        // cache is dropped again; both numbers are printed so the measurement stays honest.
        val offsets = generateSequence(0) { it + 200_000 }.takeWhile { it < document.textLength }.toList()
        var warmupWorstMs = 0.0
        for (offset in offsets) {
            val started = System.nanoTime()
            highlighter.createIterator(offset).textAttributes
            warmupWorstMs = maxOf(warmupWorstMs, (System.nanoTime() - started) / 1e6)
        }
        highlighter.clearWindowsForTests()

        val times = DoubleArray(offsets.size)
        var worstMs = 0.0
        var worstOffset = 0
        for ((index, offset) in offsets.withIndex()) {
            val started = System.nanoTime()
            highlighter.createIterator(offset).textAttributes
            val elapsed = (System.nanoTime() - started) / 1e6
            times[index] = elapsed
            if (elapsed > worstMs) {
                worstMs = elapsed
                worstOffset = offset
            }
        }
        val sorted = times.sorted()
        val over = times.count { it > 50.0 }
        println(
            "PERF scroll: %d jumps, warm-up worst %.1f ms, then median %.1f / p90 %.1f / p99 %.1f / worst %.1f ms at offset %,d (%d over 50 ms), %d windows cached"
                .format(
                    offsets.size, warmupWorstMs, sorted[sorted.size / 2], sorted[(sorted.size * 9) / 10],
                    sorted[(sorted.size * 99) / 100], worstMs, worstOffset, over, highlighter.cachedWindowCount,
                ),
        )

        assertTrue("no window build may block the EDT for more than 50 ms (worst %.1f ms)".format(worstMs), worstMs < 50)
        assertTrue("the window cache must stay bounded", highlighter.cachedWindowCount <= LogSmithLazyHighlighter.MAX_CACHED_WINDOWS)
    }

    private fun logText(targetChars: Int): String {
        val levels = listOf("DEBUG", "INFO", "WARN", "INFO", "DEBUG", "ERROR", "INFO", "DEBUG")
        val builder = StringBuilder(targetChars + 256)
        var i = 0
        while (builder.length < targetChars) {
            builder.append(
                "2026-10-01 09:%02d:%02d.%03d %-5s [http-nio-8080-exec-%d] c.e.s.UserService - Request %d processed in %dms\n"
                    .format(i % 60, (i / 60) % 60, i % 1000, levels[i % levels.size], i % 16 + 1, i, i % 40),
            )
            i++
        }
        return builder.toString()
    }
}
