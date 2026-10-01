package com.danilgorbunofff.logsmith.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/** Bridges the LogSmith lexer and colour keys into the platform highlighting machinery. */
class LogSmithSyntaxHighlighter(private val segmenter: LineSegmenter) : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = LogSmithLexer(segmenter)

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        LogSmithColors.attributesFor(tokenType)
}
