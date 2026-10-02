package com.danilgorbunofff.logsmith.index

import com.danilgorbunofff.logsmith.sniff.LogScanner
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * The line-offset index for one file (charter §5.3, §8): the character offset of every
 * line start, 8 bytes per line and nothing else. A 500 MB log therefore costs its line
 * count in memory, never its size; the text itself is read by `(offset, length)` on
 * demand. The index is built off the EDT in [CHUNK_CHARS]-sized chunks and can be
 * cancelled at any point.
 *
 * Line semantics match [com.intellij.openapi.editor.Document] so painted ranges land on
 * the lines the platform paints: `""` is one empty line, `"a"` is one, `"a\n"` and
 * `"a\nb"` are two, `"a\n\n"` is three. Only `'\n'` starts a line, so CRLF files work
 * (the `'\r'` stays a normal character) while a lone `'\r'` — an old-Mac line break the
 * platform itself reads inconsistently — does not.
 */
class LineOffsetIndex(private val maxLines: Int = DEFAULT_MAX_LINES) {

    private var starts = LongArray(INITIAL_CAPACITY)
    private var count = 1
    private var scanned = 0L
    private var isCapped = false

    init {
        require(maxLines > 0) { "maxLines must be positive" }
        starts[0] = 0L
    }

    /** Number of lines found so far; 1 for an empty file. */
    val lineCount: Int get() = count

    /** Characters consumed so far, i.e. the length of the indexed prefix. */
    val scannedChars: Long get() = scanned

    /** True when the file has more than [maxLines] lines and indexing stopped at the cap. */
    val capped: Boolean get() = isCapped

    /**
     * Bytes the index holds: the offset array's capacity at 8 bytes a slot. This is the whole
     * per-file cost — the text is never retained — so it grows with the line count, not with
     * the file size, and stays within 2x the line count because the array doubles.
     */
    val retainedBytes: Long get() = starts.size.toLong() * Long.SIZE_BYTES

    /**
     * Scans `chunk[from until to]` for line breaks. Every `'\n'` starts a line, including
     * one in the final position, so [lineCount] needs no "does the file end with a newline"
     * special case. Stops early — setting [capped] — once the line cap is reached.
     */
    fun accept(chunk: CharSequence, from: Int = 0, to: Int = chunk.length) {
        if (isCapped || from >= to) return
        val base = scanned
        var i = from
        while (i < to) {
            if (chunk[i] == '\n') {
                val next = base + (i - from) + 1
                if (count >= maxLines) {
                    isCapped = true
                    scanned = next
                    return
                }
                addStart(next)
            }
            i++
        }
        scanned = base + (to - from)
    }

    /** Offset of the start of line [line]; `startOfLine(lineCount)` is end of scanned content. */
    fun startOfLine(line: Int): Long = when {
        line <= 0 -> 0L
        line >= count -> scanned
        else -> starts[line]
    }

    /** Line containing [offset]; the last indexed line for offsets past [scannedChars]. */
    fun lineOf(offset: Long): Int {
        if (offset <= 0L) return 0
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    private fun addStart(offset: Long) {
        if (count == starts.size) starts = starts.copyOf(count * 2)
        starts[count++] = offset
    }

    override fun toString(): String = "LineOffsetIndex(lines=${grouped(count.toLong())}${if (isCapped) "+" else ""})"

    companion object {
        /** 16 M lines is ~128 MB of offsets — past that the index itself becomes the problem. */
        const val DEFAULT_MAX_LINES = 16_000_000

        /** ~8 MB of text per read, per charter §8's chunked build. */
        const val CHUNK_CHARS = 1 shl 22

        private const val INITIAL_CAPACITY = 4096
        private const val INPUT_BUFFER_BYTES = 64 * 1024

        /**
         * Builds an index from a character stream, reading [chunkChars] at a time. Throws
         * [CancellationException] as soon as [isCancelled] says so; [onProgress] reports the
         * indexed character count after each chunk.
         */
        fun build(
            reader: Reader,
            chunkChars: Int = CHUNK_CHARS,
            maxLines: Int = DEFAULT_MAX_LINES,
            isCancelled: () -> Boolean = { false },
            onProgress: (Long) -> Unit = {},
        ): LineOffsetIndex {
            require(chunkChars > 0) { "chunkChars must be positive" }
            val index = LineOffsetIndex(maxLines)
            val buffer = CharArray(chunkChars)
            while (true) {
                if (isCancelled()) throw CancellationException("line index build cancelled")
                val read = reader.read(buffer)
                if (read <= 0) break
                index.accept(String(buffer, 0, read))
                onProgress(index.scannedChars)
                if (index.capped) break
            }
            return index
        }

        /**
         * Builds an index from raw bytes, decoding with the BOM's charset when present and
         * [fallback] otherwise — the same rule [LogScanner] applies, so the line count and the
         * detected format always describe the same text.
         */
        fun build(
            input: InputStream,
            fallback: Charset,
            chunkChars: Int = CHUNK_CHARS,
            maxLines: Int = DEFAULT_MAX_LINES,
            isCancelled: () -> Boolean = { false },
            onProgress: (Long) -> Unit = {},
        ): LineOffsetIndex {
            val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, INPUT_BUFFER_BYTES)
            val charset = LogScanner.consumeBom(buffered) ?: fallback
            return InputStreamReader(buffered, charset).use { reader ->
                build(reader, chunkChars, maxLines, isCancelled, onProgress)
            }
        }

        private fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)
    }
}
