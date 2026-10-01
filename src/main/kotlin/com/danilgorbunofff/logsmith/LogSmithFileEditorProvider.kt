package com.danilgorbunofff.logsmith

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

/**
 * Day 1-2 charter rule: LogSmith *attaches* to the platform text editor rather than
 * replacing it — the file stays editable and every editor action keeps working (axis B).
 * The provider hides only the plain editor instance and re-offers it wrapped, with the
 * status strip at SOUTH.
 */
class LogSmithFileEditorProvider : FileEditorProvider, DumbAware {

    override fun getEditorTypeId(): String = "LogSmithEditor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun accept(project: Project, file: VirtualFile): Boolean = isSupported(file)

    internal fun isSupported(file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isValid) return false
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProviderHolder.create(project, file)
        return LogSmithTextEditor(project, file, textEditor)
    }

    override fun readState(sourceElement: Element, project: Project, file: VirtualFile): FileEditorState =
        TextEditorProviderHolder.readState(sourceElement, project, file)

    override fun writeState(state: FileEditorState, project: Project, targetElement: Element) {
        TextEditorProviderHolder.writeState(state, project, targetElement)
    }

    companion object {
        /** `.log` and `.out` ship by default; `.txt` stays opt-in until settings exist (Day 10). */
        private val SUPPORTED_EXTENSIONS = setOf("log", "out")
    }
}
