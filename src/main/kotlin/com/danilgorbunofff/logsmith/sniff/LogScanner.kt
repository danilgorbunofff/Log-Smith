package com.danilgorbunofff.logsmith.sniff

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.CancellationException

/** One bounded scan: the outcome plus whether the whole file was covered. */
data class ScanResult(val result: DetectionResult, val complete: Boolean)

/**
 * Bounded, platform-free scan of a log stream (charter §5.1, §5.3):
 * - honours a byte cap and a line cap, and only reports a cap when content really
 *   continues past it,
 * - never builds a line longer than [MAX_LINE_CHARS] (bounded memory on one-line files),
 * - drops a line cut in half by the byte cap instead of scoring the fragment,
 * - decodes with the BOM's charset when present (and skips the BOM), else [fallback],
 * - checks [isCancelled] every [CANCEL_CHECK_LINES] lines and throws [CancellationException].
 */
object LogScanner {

    const val MAX_LINE_CHARS = 64 * 1024
    private const val CANCEL_CHECK_LINES = 1024

    fun scan(
        input: InputStream,
        fallback: Charset,
        maxLines: Int,
        maxBytes: Long,
        sniffers: List<LogFormatSniffer> = BuiltinSniffers.all,
        isCancelled: () -> Boolean = { false },
    ): ScanResult {
        val bounded = BoundedInputStream(input, maxBytes)
        val buffered = bounded.buffered()
        val charset = consumeBom(buffered) ?: fallback
        val lines = LineSource(InputStreamReader(buffered, charset))
        val scorer = FormatScorer(sniffers)
        var counted = 0
        var complete = true
        while (true) {
            if (counted % CANCEL_CHECK_LINES == 0 && isCancelled()) throw CancellationException()
            val line = lines.next() ?: break
            if (!lines.lastTerminated && bounded.hitLimit) {
                // The byte cap cut this line; scoring the fragment would distort the ratio.
                break
            }
            if (counted >= maxLines) {
                complete = false
                scorer.markCapped("scan covered the first ${grouped(maxLines.toLong())} lines")
                break
            }
            scorer.onLine(line)
            counted++
        }
        if (complete && bounded.hitLimit) {
            complete = false
            scorer.markCapped("scan stopped at the ${formatBytes(maxBytes)} byte cap")
        }
        return ScanResult(scorer.result(), complete)
    }

    /** Detects and skips a UTF-8/UTF-16 byte-order mark; returns its charset, or null when absent. */
    private fun consumeBom(stream: InputStream): Charset? {
        stream.mark(4)
        val b0 = stream.read()
        val b1 = stream.read()
        val b2 = stream.read()
        stream.reset()
        val charset: Charset?
        val skip: Long
        when {
            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> { charset = Charsets.UTF_8; skip = 3 }
            b0 == 0xFF && b1 == 0xFE -> { charset = Charsets.UTF_16LE; skip = 2 }
            b0 == 0xFE && b1 == 0xFF -> { charset = Charsets.UTF_16BE; skip = 2 }
            else -> { charset = null; skip = 0 }
        }
        var remaining = skip
        while (remaining > 0) remaining -= stream.skip(remaining).coerceAtLeast(1)
        return charset
    }

    internal fun formatBytes(bytes: Long): String {
        val mb = 1024L * 1024
        return when {
            bytes >= mb && bytes % mb == 0L -> "${grouped(bytes / mb)} MB"
            bytes >= 1024 -> "${grouped(bytes / 1024)} KB"
            else -> "${grouped(bytes)}-byte"
        }
    }

    private fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

    /**
     * Line reader with a hard per-line length cap. `\n` terminates a line; a trailing
     * `\r` is left for [FormatScorer] to trim, as before.
     */
    private class LineSource(private val reader: Reader) {
        private val buffer = CharArray(64 * 1024)
        private var pos = 0
        private var limit = 0
        private val line = StringBuilder()

        /** Whether the line most recently returned ended with `\n` (false at EOF). */
        var lastTerminated: Boolean = false
            private set

        fun next(): String? {
            line.setLength(0)
            var sawAny = false
            while (true) {
                if (pos >= limit) {
                    limit = reader.read(buffer, 0, buffer.size)
                    pos = 0
                    if (limit <= 0) {
                        limit = 0
                        lastTerminated = false
                        return if (sawAny) line.toString() else null
                    }
                }
                sawAny = true
                var i = pos
                while (i < limit && buffer[i] != '\n') i++
                val room = MAX_LINE_CHARS - line.length
                if (room > 0) line.appendRange(buffer, pos, pos + minOf(i - pos, room))
                if (i < limit) {
                    pos = i + 1
                    lastTerminated = true
                    return line.toString()
                }
                pos = limit
            }
        }
    }

    /**
     * InputStream that reports EOF at [limit] bytes. [hitLimit] is true only when
     * the underlying stream really has more data past the cap.
     */
    private class BoundedInputStream(private val delegate: InputStream, limit: Long) : InputStream() {

        private var remaining = limit

        var hitLimit: Boolean = false
            private set

        private fun atCap(): Int {
            if (!hitLimit && delegate.read() >= 0) hitLimit = true
            return -1
        }

        override fun read(): Int {
            if (remaining <= 0) return atCap()
            val value = delegate.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return atCap()
            val read = delegate.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }
    }
}
