package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.FormatStats
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/** Per-file switch set by the "Toggle LogSmith highlighting" action. */
val logSmithHighlightDisabled: Key<Boolean> = Key.create("LOGSMITH_HIGHLIGHT_DISABLED")

/**
 * Swaps the platform's plain-text highlighter for the LogSmith one once
 * detection has a confident answer, and restores the original highlighter
 * when detection fails or the user disabled the file (charter §5.4, R6):
 * highlighting is only ever an upgrade — a file that rendered fine before
 * LogSmith never turns grey.
 */
class LogSmithHighlighterInstaller(val editor: EditorEx, private val file: VirtualFile) {

    private val original: EditorHighlighter? = editor.highlighter
    private var installed: LexerEditorHighlighter? = null
    private var installedFormat: String? = null
    private var lastStats: FormatStats? = null

    fun update(stats: FormatStats?) {
        if (stats != null) lastStats = stats
        val formatName = lastStats?.formatName
        val disabled = file.getUserData(logSmithHighlightDisabled) == true
        if (disabled || formatName == null) {
            restore()
            return
        }
        val sniffer = BuiltinSniffers.byName[formatName]
        if (sniffer == null) {
            restore()
            return
        }
        if (installed != null && installedFormat == formatName) return
        val highlighter = LexerEditorHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            EditorColorsManager.getInstance().globalScheme,
        )
        installed = highlighter
        installedFormat = formatName
        editor.highlighter = highlighter
    }

    private fun restore() {
        if (installed != null && original != null) {
            editor.highlighter = original
        }
        installed = null
        installedFormat = null
    }
}
