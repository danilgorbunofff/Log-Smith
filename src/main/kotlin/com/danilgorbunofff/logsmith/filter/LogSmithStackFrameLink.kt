package com.danilgorbunofff.logsmith.filter

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseEvent
import java.util.regex.Pattern

/**
 * One clickable stack frame: a file path with a line number on a log line. Frames are
 * resolved relative to the log's directory first (build trees are laid out that way),
 * then by bare-name lookup across the project.
 */
object LogSmithStackFrames {

    /** `path/to/Thing.kt:42` inside a parenthesised, quoted, "at " or annotated frame. */
    private val PATH_LINE = Pattern.compile(
        "(?:\\(|\\s|\"|@)([A-Za-z][A-Za-z0-9_.$/\\\\*\\-]*\\.(?:java|kt|kts|scala|groovy|gradle|py|rb|js|cjs|mjs|ts|tsx|jsx|cs|go|rs|php|cpp|hpp|c|h|m|mm|swift|dart)):(\\d+)(?::(\\d+))?"
    )

    /** Python traceback `File "src/app.py", line 10, in handler`. */
    private val PYTHON_FILE = Pattern.compile("File \"([^\"]+)\", line (\\d+)(?:, in (\\S+))?")

    data class FrameSpan(val start: Int, val end: Int, val path: String, val line: Int, val column: Int)

    fun spanAt(lineText: String, caretColumn: Int): FrameSpan? {
        val python = PYTHON_FILE.matcher(lineText)
        while (python.find()) {
            val start = python.start(1)
            val end = python.end(2)
            if (caretColumn in start until end) {
                return FrameSpan(start, end, python.group(1), python.group(2).toInt(), 0)
            }
        }
        val path = PATH_LINE.matcher(lineText)
        while (path.find()) {
            val start = path.start(1)
            val end = path.end(2)
            if (caretColumn in start until end) {
                val column = path.group(3)?.let { (it.toInt() - 1).coerceAtLeast(0) } ?: 0
                return FrameSpan(start, end, path.group(1), path.group(2).toInt(), column)
            }
        }
        return null
    }

    /** Resolves [path] relative to the log's directory, walking up at most 12 ancestors. */
    fun resolve(project: Project, from: VirtualFile, path: String): VirtualFile? {
        val separators = if (path.contains('/') || path.contains('\\')) {
            path.split('/', '\\')
        } else {
            return byName(project, path.substringAfterLast('/').substringAfterLast('\\'))
        }
        var dir = from.parent
        var depth = 0
        while (dir != null && depth < 12) {
            walk(dir, separators)?.let { return it }
            dir = dir.parent
            depth++
        }
        val bare = separators.lastOrNull { it.isNotEmpty() && it != "." && it != ".." } ?: return null
        return byName(project, bare)
    }

    private fun walk(dir: VirtualFile, parts: List<String>): VirtualFile? {
        var current = dir
        for (raw in parts) {
            when (raw) {
                "", "." -> {}
                ".." -> current = current.parent ?: return null
                else -> {
                    current = current.findChild(raw) ?: return null
                    if (!current.isDirectory && raw != parts.last()) return null
                }
            }
        }
        return current.takeUnless { it.isDirectory }
    }

    private fun byName(project: Project, name: String): VirtualFile? {
        if (name.isBlank()) return null
        val all = FilenameIndex.getVirtualFilesByName(project, name, GlobalSearchScope.projectScope(project))
        if (all.isEmpty()) return null
        val clean = all.filter { !it.path.contains("/build/") && !it.path.contains("/out/") && !it.path.contains("/generated/") }
        return (clean.ifEmpty { all }).minByOrNull { it.path.length }
    }
}

/**
 * Ctrl+click on a stack frame opens the referenced file at the line (charter R11). Hovering
 * with Ctrl held underlines the frame; without Ctrl nothing is interactive, so ordinary
 * selection and copying in the log stays untouched.
 */
class LogSmithStackFrameLink : EditorMouseListener, EditorMouseMotionListener {

    private var hover: RangeHighlighter? = null

    override fun mousePressed(e: EditorMouseEvent) {
        val mouse = e.mouseEvent ?: return
        if (!(mouse.isControlDown || mouse.isMetaDown)) return
        val editor = e.editor ?: return
        val frame = frameAt(editor, mouse) ?: return
        e.consume()
        navigateTo(editor, frame)
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor ?: return
        val mouse = e.mouseEvent ?: return
        if (!(mouse.isControlDown || mouse.isMetaDown)) {
            clearHover(editor)
            return
        }
        val frame = frameAt(editor, mouse)
        if (frame == null) {
            clearHover(editor)
            return
        }
        val existing = hover
        if (existing != null && existing.isValid && existing.startOffset == frame.start && existing.endOffset == frame.end) return
        clearHover(editor)
        hover = editor.markupModel.addRangeHighlighter(
            frame.start, frame.end, HighlighterLayer.SELECTION - 1, HOVER, HighlighterTargetArea.EXACT_RANGE,
        )
    }

    internal fun frameAt(editor: Editor, mouse: MouseEvent): LocatedSpan? {
        val logical = editor.xyToLogicalPosition(Point(mouse.x, mouse.y))
        val offset = editor.logicalPositionToOffset(logical)
        if (offset < 0) return null
        val document = editor.document
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val text = document.immutableCharSequence.subSequence(lineStart, document.getLineEndOffset(line)).toString()
        val span = LogSmithStackFrames.spanAt(text, offset - lineStart) ?: return null
        return LocatedSpan(lineStart + span.start, lineStart + span.end, span)
    }

    private fun clearHover(editor: Editor) {
        hover?.let { editor.markupModel.removeHighlighter(it) }
        hover = null
    }

    private fun navigateTo(editor: Editor, frame: LocatedSpan) {
        val project = editor.project ?: return
        val document = editor.document
        val from = FileDocumentManager.getInstance().getFile(document) ?: return
        val resolved = LogSmithStackFrames.resolve(project, from, frame.span.path) ?: return
        OpenFileDescriptor(project, resolved, (frame.span.line - 1).coerceAtLeast(0), frame.span.column).navigate(true)
    }

    /** A frame span translated into document coordinates. */
    internal class LocatedSpan(val start: Int, val end: Int, val span: LogSmithStackFrames.FrameSpan)

    private companion object {
        val HOVER = TextAttributes(null, null, JBColor.BLUE, EffectType.LINE_UNDERSCORE, Font.PLAIN)
    }
}
