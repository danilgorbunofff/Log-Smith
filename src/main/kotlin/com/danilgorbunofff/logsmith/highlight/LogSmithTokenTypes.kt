package com.danilgorbunofff.logsmith.highlight

import com.intellij.lang.Language
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.tree.IElementType
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/** Private language only so the token element types have a home. Never registered as a file type. */
class LogSmithLanguage private constructor() : Language("LogSmith") {
    companion object {
        val INSTANCE: LogSmithLanguage = LogSmithLanguage()
    }
}

/** Element types the LogSmith lexer emits. */
object LogSmithTokenTypes {
    val TIMESTAMP: IElementType = IElementType("LOGSMITH_TIMESTAMP", LogSmithLanguage.INSTANCE)
    val THREAD: IElementType = IElementType("LOGSMITH_THREAD", LogSmithLanguage.INSTANCE)
    val LOGGER: IElementType = IElementType("LOGSMITH_LOGGER", LogSmithLanguage.INSTANCE)
    val MESSAGE: IElementType = IElementType("LOGSMITH_MESSAGE", LogSmithLanguage.INSTANCE)
    val LEVEL_ERROR: IElementType = IElementType("LOGSMITH_LEVEL_ERROR", LogSmithLanguage.INSTANCE)
    val LEVEL_WARN: IElementType = IElementType("LOGSMITH_LEVEL_WARN", LogSmithLanguage.INSTANCE)
    val LEVEL_INFO: IElementType = IElementType("LOGSMITH_LEVEL_INFO", LogSmithLanguage.INSTANCE)
    val LEVEL_DEBUG: IElementType = IElementType("LOGSMITH_LEVEL_DEBUG", LogSmithLanguage.INSTANCE)
    val GENERIC: IElementType = IElementType("LOGSMITH_GENERIC", LogSmithLanguage.INSTANCE)
    val WS: IElementType = IElementType("LOGSMITH_WS", LogSmithLanguage.INSTANCE)

    /**
     * The escape sequences themselves: text the editor must keep, but must never show
     * ([AnsiAttributes.escape] is how). Deliberately no colour key: the attributes that hide
     * them are derived from the editor's own background, which a static key cannot express.
     */
    val ANSI_ESCAPE: IElementType = IElementType("LOGSMITH_ANSI_ESCAPE", LogSmithLanguage.INSTANCE)
}

/**
 * Level colour ramp (charter Day 5-6): ERROR red, WARN amber, INFO default,
 * DEBUG dim. Timestamp/thread are neutral greys; logger and message keep the
 * editor's default look so LogSmith is a strict improvement, never a downgrade.
 * JBColor keeps every colour readable on both light and dark themes.
 */
object LogSmithColors {

    private fun gray(light: Int, dark: Int) = JBColor(Color(light), Color(dark))

    private fun attrs(foreground: Color?, fontType: Int = Font.PLAIN): TextAttributes =
        TextAttributes(foreground, null, null, null, fontType)

    val TIMESTAMP: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LOGSMITH_TIMESTAMP", attrs(gray(0x8A8A8A, 0x9E9E9E))
    )
    val THREAD: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LOGSMITH_THREAD", attrs(gray(0x8A8A8A, 0x9E9E9E), Font.ITALIC)
    )
    val LOGGER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("LOGSMITH_LOGGER")
    val MESSAGE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("LOGSMITH_MESSAGE")
    val LEVEL_ERROR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LOGSMITH_LEVEL_ERROR", attrs(gray(0xC00000, 0xFF5C5C), Font.BOLD)
    )
    val LEVEL_WARN: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LOGSMITH_LEVEL_WARN", attrs(gray(0xC25E00, 0xFFAB40), Font.BOLD)
    )
    val LEVEL_INFO: TextAttributesKey = TextAttributesKey.createTextAttributesKey("LOGSMITH_LEVEL_INFO")
    val LEVEL_DEBUG: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LOGSMITH_LEVEL_DEBUG", attrs(gray(0x8A8A8A, 0x8A8A8A))
    )

    fun attributesFor(type: IElementType?): Array<TextAttributesKey> = when (type) {
        LogSmithTokenTypes.TIMESTAMP -> arrayOf(TIMESTAMP)
        LogSmithTokenTypes.THREAD -> arrayOf(THREAD)
        LogSmithTokenTypes.LOGGER -> arrayOf(LOGGER)
        LogSmithTokenTypes.MESSAGE -> arrayOf(MESSAGE)
        LogSmithTokenTypes.LEVEL_ERROR -> arrayOf(LEVEL_ERROR)
        LogSmithTokenTypes.LEVEL_WARN -> arrayOf(LEVEL_WARN)
        LogSmithTokenTypes.LEVEL_INFO -> arrayOf(LEVEL_INFO)
        LogSmithTokenTypes.LEVEL_DEBUG -> arrayOf(LEVEL_DEBUG)
        else -> emptyArray()
    }
}
