package com.danilgorbunofff.logsmith.highlight

import com.danilgorbunofff.logsmith.ansi.AnsiColor
import com.danilgorbunofff.logsmith.ansi.AnsiStyle
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import kotlin.math.roundToInt

/**
 * ANSI SGR style to [TextAttributes] (charter §5.6 R9, Day 9). The colours are the xterm ones
 * the log itself asked for, taken literally: a log coloured for a dark terminal keeps those
 * colours on a light theme, because second-guessing the log is how a viewer starts lying.
 *
 * A run that colours nothing only decorates — it keeps [ramp]'s foreground, so `\u001b[1m`
 * around `ERROR` is still red, just bold. A run that paints a *background* but no foreground
 * takes the editor's default foreground instead, because the ramp colour can be the very
 * colour that background is made of (`\u001b[41m` on `ERROR` is red on red).
 *
 * Bold and italic map to the font, underline to the underscore effect, and `dim` — which has
 * no AWT equivalent — blends towards the background, the one direction that reads as "faint"
 * on both a light and a dark theme.
 */
object AnsiAttributes {

    private const val DIM_MIX = 0.45f

    /** The xterm 6x6x6 cube's channel values. */
    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    /** The 16 base colours, codes 30-37 then 90-97. */
    private val BASE = arrayOf(
        Color(0x000000), Color(0xCD0000), Color(0x00CD00), Color(0xCDCD00),
        Color(0x0000EE), Color(0xCD00CD), Color(0x00CDCD), Color(0xE5E5E5),
        Color(0x7F7F7F), Color(0xFF0000), Color(0x00FF00), Color(0xFFFF00),
        Color(0x5C5CFF), Color(0xFF00FF), Color(0x00FFFF), Color(0xFFFFFF),
    )

    fun of(style: AnsiStyle, scheme: EditorColorsScheme, ramp: TextAttributes): TextAttributes {
        val background = style.background?.let { colour(it) }
        val foreground = foreground(style, background, ramp, scheme)
        val fontType = (if (style.bold) Font.BOLD else Font.PLAIN) or (if (style.italic) Font.ITALIC else 0)
        return TextAttributes(
            foreground,
            background,
            null,
            if (style.underline) EffectType.LINE_UNDERSCORE else null,
            fontType,
        )
    }

    /**
     * The escape sequences themselves: text the document has to keep, and text that must show
     * no ink. They are painted in the editor's own background colour, so `\u001b[32m` never
     * renders as text. They do keep their columns — hiding text outright needs fold regions,
     * and one fold region per escape is not affordable in a windowed viewer — so a coloured
     * line shows a small gap where its sequences sit.
     */
    fun escape(scheme: EditorColorsScheme): TextAttributes =
        TextAttributes(scheme.defaultBackground, null, null, null, Font.PLAIN)

    private fun foreground(
        style: AnsiStyle,
        background: Color?,
        ramp: TextAttributes,
        scheme: EditorColorsScheme,
    ): Color? {
        val chosen = when {
            style.foreground != null -> colour(style.foreground)
            background != null -> null
            else -> ramp.foregroundColor
        }
        if (!style.dim) return chosen
        return blend(chosen ?: scheme.defaultForeground, background ?: scheme.defaultBackground, DIM_MIX)
    }

    /** One SGR colour as an AWT colour: the 16 base, the 256-colour cube, or direct colour. */
    fun colour(color: AnsiColor): Color = when (color) {
        is AnsiColor.Named -> BASE[(if (color.code >= 90) 8 else 0) + color.code % 10]
        is AnsiColor.Indexed -> indexed(color.index)
        is AnsiColor.Rgb -> Color(color.r, color.g, color.b)
    }

    private fun indexed(index: Int): Color = when {
        index < 16 -> BASE[index]
        index < 232 -> {
            val i = index - 16
            Color(CUBE[i / 36], CUBE[i / 6 % 6], CUBE[i % 6])
        }
        else -> {
            val grey = 8 + 10 * (index - 232)
            Color(grey, grey, grey)
        }
    }

    private fun blend(from: Color, to: Color, mix: Float): Color = Color(
        (from.red + (to.red - from.red) * mix).roundToInt().coerceIn(0, 255),
        (from.green + (to.green - from.green) * mix).roundToInt().coerceIn(0, 255),
        (from.blue + (to.blue - from.blue) * mix).roundToInt().coerceIn(0, 255),
    )
}
