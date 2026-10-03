package com.danilgorbunofff.logsmith.filter

import com.intellij.codeInsight.hint.HintManager
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
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseEvent
import java.util.regex.Pattern

/**
 * One clickable stack frame: a file path with a line number on a log line. Absolute paths are
 * looked up on the log's own file system first (R10: the same [VirtualFile] world the log lives
 * in, local or remote); then frames are resolved relative to the log's directory (build trees are
 * laid out that way), then by bare-name lookup across the project, preferring the file whose
 * directory matches the frame's package when the frame names one (`at com.example.Foo.bar(Foo.java:3)`).
 */
object LogSmithStackFrames {

    private const val EXTENSIONS =
        "java|kt|kts|scala|groovy|gradle|py|rb|js|cjs|mjs|ts|tsx|jsx|cs|go|rs|php|cpp|hpp|c|h|m|mm|swift|dart"

    /**
     * `path/to/Thing.kt:42[:7]` at a line start or after `(`, whitespace, a quote, `@` or `[`. The
     * path may be absolute (`/app/src/index.js`, `~/x.go`, `C:\src\App.cs`) — Node and Go print
     * absolute paths in their stack traces.
     */
    private val PATH_LINE = Pattern.compile(
        "(?:^|[(\\s\"@'\\[])((?:[A-Za-z]:[\\\\/]|~?/)?[A-Za-z0-9_.$\\-][A-Za-z0-9_.$/\\\\*\\-]*\\.(?:$EXTENSIONS)):(\\d+)(?::(\\d+))?"
    )

    /** PHP / Laravel frames: `#0 /var/www/app/Http/Kernel.php(42): …`. */
    private val PHP_FRAME = Pattern.compile("(?:^|\\s)((?:[A-Za-z]:[\\\\/]|/)?[^\\s(:]+\\.php)\\((\\d+)\\)")

    /** Python traceback `File "src/app.py", line 10, in handler`. */
    private val PYTHON_FILE = Pattern.compile("File \"([^\"]+)\", line (\\d+)(?:, in (\\S+))?")

    /** A JVM frame's qualified method, `at com.example.Foo.bar(` — the class's package is a lookup hint. */
    private val JVM_FRAME = Pattern.compile("\\bat\\s+([\\w$.]+)\\.[\\w$<>-]+\\(")

    /** [packageDir] is the frame's package as a directory (`com/example`), when the line names one. */
    data class FrameSpan(
        val start: Int,
        val end: Int,
        val path: String,
        val line: Int,
        val column: Int,
        val packageDir: String? = null,
    )

    fun spanAt(lineText: String, caretColumn: Int): FrameSpan? {
        val python = PYTHON_FILE.matcher(lineText)
        while (python.find()) {
            val start = python.start(1)
            val end = python.end(2)
            if (caretColumn in start until end) {
                return FrameSpan(start, end, python.group(1), python.group(2).toInt(), 0)
            }
        }
        val php = PHP_FRAME.matcher(lineText)
        while (php.find()) {
            val start = php.start(1)
            val end = php.end(2) + 1
            if (caretColumn in start until end) {
                return FrameSpan(start, end, php.group(1), php.group(2).toInt(), 0)
            }
        }
        val path = PATH_LINE.matcher(lineText)
        while (path.find()) {
            val start = path.start(1)
            val end = path.end(if (path.group(3) != null) 3 else 2)
            if (caretColumn in start until end) {
                val column = path.group(3)?.let { (it.toInt() - 1).coerceAtLeast(0) } ?: 0
                return FrameSpan(start, end, path.group(1), path.group(2).toInt(), column, packageDir(lineText, start))
            }
        }
        return null
    }

    /** The package of the JVM frame whose `(File.java:N)` starts at [pathStart], as a directory. */
    private fun packageDir(lineText: String, pathStart: Int): String? {
        if (pathStart == 0 || lineText[pathStart - 1] != '(') return null
        val jvm = JVM_FRAME.matcher(lineText)
        var found: String? = null
        while (jvm.find() && jvm.end() <= pathStart) found = jvm.group(1)
        val qualifiedClass = found ?: return null
        val dot = qualifiedClass.lastIndexOf('.')
        return if (dot > 0) qualifiedClass.substring(0, dot).replace('.', '/') else null
    }

    /** Resolves [path]: absolute on the log's file system, relative to the log's directory, then by name. */
    fun resolve(project: Project, from: VirtualFile, path: String, packageDir: String? = null): VirtualFile? {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(normalized)) {
            from.fileSystem.findFileByPath(normalized)?.takeUnless { it.isDirectory }?.let { return it }
        }
        if (!normalized.contains('/')) return byName(project, normalized, packageDir)
        val parts = normalized.split('/')
        var dir = from.parent
        var depth = 0
        while (dir != null && depth < 12) {
            walk(dir, parts)?.let { return it }
            dir = dir.parent
            depth++
        }
        val bare = parts.lastOrNull { it.isNotEmpty() && it != "." && it != ".." } ?: return null
        return byName(project, bare, packageDir)
    }

    private fun walk(dir: VirtualFile, parts: List<String>): VirtualFile? {
        var current = dir
        for ((index, raw) in parts.withIndex()) {
            when (raw) {
                "", "." -> {}
                ".." -> current = current.parent ?: return null
                else -> {
                    current = current.findChild(raw) ?: return null
                    if (!current.isDirectory && index != parts.lastIndex) return null
                }
            }
        }
        return current.takeUnless { it.isDirectory }
    }

    private fun byName(project: Project, name: String, packageDir: String?): VirtualFile? {
        if (name.isBlank()) return null
        val all = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        if (all.isEmpty()) return null
        if (packageDir != null) {
            all.filter { it.path.endsWith("/$packageDir/$name") }.minByOrNull { it.path.length }?.let { return it }
        }
        val clean = all.filter { !it.path.contains("/build/") && !it.path.contains("/out/") && !it.path.contains("/generated/") }
        return (clean.ifEmpty { all }).minByOrNull { it.path.length }
    }
}

/**
 * Ctrl+click (Cmd+click on macOS, where Ctrl+click opens the context menu) on a stack frame opens
 * the referenced file at the line (charter R11). Hovering with the modifier held underlines the
 * frame; without it nothing is interactive, so ordinary selection and copying stay untouched. A
 * frame that cannot be resolved says so in a hint rather than doing nothing.
 */
class LogSmithStackFrameLink : EditorMouseListener, EditorMouseMotionListener {

    private var hover: RangeHighlighter? = null

    private fun modifierDown(mouse: MouseEvent): Boolean =
        if (SystemInfo.isMac) mouse.isMetaDown else mouse.isControlDown

    override fun mousePressed(e: EditorMouseEvent) {
        val mouse = e.mouseEvent
        if (!modifierDown(mouse)) return
        val editor = e.editor
        val frame = frameAt(editor, mouse) ?: return
        e.consume()
        navigateTo(editor, frame)
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        val mouse = e.mouseEvent
        if (!modifierDown(mouse)) {
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
        val from = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val resolved = try {
            LogSmithStackFrames.resolve(project, from, frame.span.path, frame.span.packageDir)
        } catch (e: IndexNotReadyException) {
            HintManager.getInstance().showInformationHint(editor, "LogSmith can open ${frame.span.path} once indexing has finished")
            return
        }
        if (resolved == null) {
            HintManager.getInstance().showErrorHint(editor, "LogSmith could not find ${frame.span.path} in this project")
            return
        }
        OpenFileDescriptor(project, resolved, (frame.span.line - 1).coerceAtLeast(0), frame.span.column).navigate(true)
    }

    /** A frame span translated into document coordinates. */
    internal class LocatedSpan(val start: Int, val end: Int, val span: LogSmithStackFrames.FrameSpan)

    private companion object {
        val HOVER = TextAttributes(null, null, JBColor.BLUE, EffectType.LINE_UNDERSCORE, Font.PLAIN)
    }
}
