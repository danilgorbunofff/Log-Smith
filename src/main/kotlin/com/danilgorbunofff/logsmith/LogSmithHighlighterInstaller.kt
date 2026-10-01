package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/** Per-file switch set by the "Disable LogSmith highlighting" action. */
val logSmithHighlightDisabled: Key<Boolean> = Key.create("LOGSMITH_HIGHLIGHT_DISABLED")

/**
 * Swaps the platform highlighter for the LogSmith one once detection has a confident
 * answer, and puts a freshly built platform highlighter back when detection fails or
 * the user disabled the file (charter §5.4, R6): highlighting is only ever an upgrade —
 * a file that rendered fine before LogSmith never turns grey.
 *
 * The highlighter lexes the whole document on install, on the EDT. Until lazy,
 * visible-range highlighting lands (charter Day 7) documents above [MAX_HIGHLIGHT_CHARS]
 * stay uncoloured, and the status line says so (§5.3.5: degrade and *say so*).
 */
class LogSmithHighlighterInstaller(
    private val project: Project,
    private val editor: EditorEx,
    private val file: VirtualFile,
) {

    private var installedFormat: String? = null

    val isInstalled: Boolean get() = installedFormat != null

    /** Applies [result]; returns a note for the status line when colouring is withheld, else null. */
    fun update(result: DetectionResult?, disabled: Boolean): String? {
        val formatName = (result as? DetectionResult.Matched)?.stats?.formatName
        val sniffer = formatName?.let { BuiltinSniffers.byName[it] }
        if (disabled || sniffer == null) {
            restore()
            return null
        }
        if (editor.document.textLength > MAX_HIGHLIGHT_CHARS) {
            restore()
            return "colouring off for files over ${MAX_HIGHLIGHT_CHARS / (1024 * 1024)} MB"
        }
        if (installedFormat == formatName) return null
        editor.highlighter = LexerEditorHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            editor.colorsScheme,
        )
        installedFormat = formatName
        return null
    }

    private fun restore() {
        if (installedFormat == null) return
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)
        installedFormat = null
    }

    companion object {
        /**
         * Temporary cap until lazy highlighting; see the class comment. Measured 2026-10-01
         * (Apple Silicon, IDEA 2025.2): a full lex costs ~19 ms/MB — 5 MB ≈ 100 ms, 20 MB ≈ 380 ms.
         */
        const val MAX_HIGHLIGHT_CHARS = 5 * 1024 * 1024
    }
}
