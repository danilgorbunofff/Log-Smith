package com.danilgorbunofff.logsmith.sniff

import java.util.Locale
import kotlin.math.roundToInt

/** The result of scoring one file: winning format plus how much of the scan it explained. */
data class FormatStats(val formatName: String, val matched: Int, val scanned: Int) {

    val ratio: Double
        get() = if (scanned == 0) 0.0 else matched.toDouble() / scanned

    override fun toString(): String {
        val percent = (ratio * 1000).roundToInt() / 10.0
        return "Format: $formatName — matched $matched / $scanned lines (" +
            String.format(Locale.US, "%.1f", percent) + "%)"
    }

    companion object {
        /** No sniffer reached a believable match ratio. */
        @JvmStatic
        val UNKNOWN = "Format: <unknown>"
    }
}
