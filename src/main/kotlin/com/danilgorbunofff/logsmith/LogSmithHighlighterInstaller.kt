package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.highlight.LineSegmenter
import com.danilgorbunofff.logsmith.highlight.LogSmithLazyHighlighter
import com.danilgorbunofff.logsmith.highlight.LogSmithSyntaxHighlighter
import com.danilgorbunofff.logsmith.sniff.BuiltinSniffers
import com.danilgorbunofff.logsmith.sniff.DetectionResult
import com.intellij.openapi.editor.ex.EditorEx
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
 * The installed highlighter is [LogSmithLazyHighlighter], not the platform's
 * `LexerEditorHighlighter`: it colours the visible range only, so installing it costs the
 * same for a 4 KB file and a 4 GB one and there is no file-size cap left to explain
 * (charter §5.3 R5, §8 Day 7).
 */
class LogSmithHighlighterInstaller(
    private val project: Project,
    private val editor: EditorEx,
    private val file: VirtualFile,
) {

    private var installedFormat: String? = null

    val isInstalled: Boolean get() = installedFormat != null

    /** Applies [result]: installs LogSmith highlighting on a match, restores otherwise. */
    fun update(result: DetectionResult?, disabled: Boolean) {
        val formatName = (result as? DetectionResult.Matched)?.stats?.formatName
        val sniffer = formatName?.let { BuiltinSniffers.byName[it] }
        if (disabled || sniffer == null) {
            restore()
            return
        }
        if (installedFormat == formatName) return
        editor.highlighter = LogSmithLazyHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            editor.colorsScheme,
        )
        installedFormat = formatName
    }

    private fun restore() {
        if (installedFormat == null) return
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)
        installedFormat = null
    }
}
