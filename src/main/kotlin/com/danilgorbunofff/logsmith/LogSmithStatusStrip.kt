package com.danilgorbunofff.logsmith

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * The status line under the editor, attached with `FileEditorManager.addBottomComponent`.
 * It renders [StatusText] and nothing else, so the text always reflects current state.
 */
class LogSmithStatusStrip {

    private val label = JLabel()

    val root: JComponent = JBUI.Panels.simplePanel(label).apply {
        border = JBUI.Borders.empty(2, 10)
    }

    init {
        render(StatusText.of(null, disabled = false))
    }

    val text: String get() = label.text

    fun render(status: StatusText) {
        label.text = status.text
        label.toolTipText = status.tooltip
        label.icon = if (status.warning) AllIcons.General.Warning else null
    }
}
