package com.danilgorbunofff.logsmith

import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

/** Lazily declared so the provider itself never touches editor internals on class load. */
internal object TextEditorProviderHolder {

    private val provider: com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
        get() = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance()

    fun create(project: Project, file: VirtualFile): TextEditor = provider.createEditor(project, file) as TextEditor

    fun readState(element: Element, project: Project, file: VirtualFile): FileEditorState =
        provider.readState(element, project, file)

    fun writeState(state: FileEditorState, project: Project, element: Element) {
        provider.writeState(state, project, element)
    }
}
