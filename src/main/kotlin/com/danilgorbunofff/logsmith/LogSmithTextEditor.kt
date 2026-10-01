package com.danilgorbunofff.logsmith

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.util.ui.UIUtil
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Wraps the platform text editor and adds the status strip at SOUTH.
 * Everything is delegated; the editor surface itself is untouched, which is
 * what keeps axis B (file stays fully editable) true.
 */
class LogSmithTextEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val delegate: TextEditor,
) : UserDataHolderBase(), TextEditor {

    private val strip = LogSmithStatusStrip()
    private val installer: LogSmithHighlighterInstaller? =
        (delegate.editor as? com.intellij.openapi.editor.ex.EditorEx)
            ?.let { LogSmithHighlighterInstaller(it, file) }
    private val panel: JComponent = strip.root.also {
        it.add(delegate.component, java.awt.BorderLayout.CENTER)
    }

    init {
        project.getService(LogSmithDetectionService::class.java).detect(file, ::onDetected)
    }

    private fun onDetected(stats: com.danilgorbunofff.logsmith.sniff.FormatStats?) {
        strip.show(stats)
        installer?.update(stats)
    }

    /** Called by the toggle action; keeps the strip and highlighter in step. */
    fun onToggle() {
        val nowDisabled = file.getUserData(logSmithHighlightDisabled) != true
        file.putUserData(logSmithHighlightDisabled, nowDisabled)
        installer?.update(null) // update(null) refreshes with the installer's last known stats
        if (nowDisabled) strip.showDisabled() else strip.showEnabled()
    }

    override fun getEditor(): Editor = delegate.editor

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = delegate.preferredFocusedComponent ?: delegate.component

    override fun getName(): String = delegate.name

    override fun isValid(): Boolean = delegate.isValid

    override fun isModified(): Boolean = delegate.isModified

    override fun getState(level: FileEditorStateLevel): FileEditorState = delegate.getState(level)

    override fun setState(state: FileEditorState) = delegate.setState(state)

    override fun selectNotify() = delegate.selectNotify()

    override fun deselectNotify() = delegate.deselectNotify()

    override fun canNavigateTo(request: Navigatable): Boolean = delegate.canNavigateTo(request)

    override fun navigateTo(request: Navigatable) = delegate.navigateTo(request)

    override fun getBackgroundHighlighter(): com.intellij.codeHighlighting.BackgroundEditorHighlighter? =
        delegate.backgroundHighlighter

    override fun getCurrentLocation(): FileEditorLocation? = delegate.currentLocation

    override fun getFile(): VirtualFile = file

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        delegate.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        delegate.removePropertyChangeListener(listener)
    }

    override fun dispose() {
        UIUtil.dispose(panel)
        delegate.dispose()
    }
}
