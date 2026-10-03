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
 * Puts LogSmith highlighting over the platform's own once detection has something to add, and
 * puts a freshly built platform highlighter back when it has not or the user disabled the file
 * (charter §5.4, R6): highlighting is only ever an upgrade — a file that rendered fine before
 * LogSmith never turns grey.
 *
 * "Something to add" is either a claimed format (timestamps, threads and the level ramp) or, for
 * a file no format explains, ANSI escape sequences in it (charter §5.6 R9): their colours are
 * rendered and the escapes hidden, and nothing else is touched.
 *
 * The installed highlighter is [LogSmithLazyHighlighter], which colours the visible range only,
 * and it keeps the highlighter the platform would have used for the file underneath: every
 * character LogSmith does not colour keeps the platform's attributes (for `.log`, the bundled
 * TextMate log grammar's strings, numbers and URLs), so installing LogSmith never removes colour.
 */
class LogSmithHighlighterInstaller(
    private val project: Project,
    private val editor: EditorEx,
    private val file: VirtualFile,
) {

    /** The format name LogSmith highlights with, [ANSI_ONLY] for an unclaimed coloured file, or null. */
    var installedMode: String? = null
        private set

    val isInstalled: Boolean get() = installedMode != null

    /** Applies [result]: installs LogSmith highlighting when it adds something, restores otherwise. */
    fun update(result: DetectionResult?, disabled: Boolean) {
        val sniffer = (result as? DetectionResult.Matched)?.stats?.formatName?.let { BuiltinSniffers.byName[it] }
        val coloured = ((result as? DetectionResult.NoMatch)?.ansiLines ?: 0) > 0
        if (disabled || (sniffer == null && !coloured)) {
            restore()
            return
        }
        val mode = sniffer?.formatName ?: ANSI_ONLY
        if (installedMode == mode) return
        editor.highlighter = LogSmithLazyHighlighter(
            LogSmithSyntaxHighlighter(LineSegmenter(sniffer)),
            editor.colorsScheme,
            platformHighlighter(),
        )
        installedMode = mode
    }

    private fun restore() {
        if (installedMode == null) return
        editor.highlighter = platformHighlighter()
        installedMode = null
    }

    private fun platformHighlighter() = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)

    companion object {
        /** The mode for a file no format claimed but which carries ANSI colour codes. */
        const val ANSI_ONLY = "ANSI colours"
    }
}
