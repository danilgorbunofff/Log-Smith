package com.danilgorbunofff.logsmith

import com.danilgorbunofff.logsmith.sniff.FormatStats
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The Day-1 deliverable itself: a status line under the editor.
 *
 * Charter §5.1: the failure state is visible from day one — `Format: <unknown>`
 * with the tooltip telling the user detection is on its way.
 */
class LogSmithStatusStrip {

    private val label = JLabel(FormatStats.UNKNOWN)
    private var lastText: String = FormatStats.UNKNOWN
    private var lastTooltip: String = "detection runs in the background after the file is opened."

    val root: JComponent = JPanel(BorderLayout()).apply {
        add(JBUI.Panels.simplePanel(label).apply { border = JBUI.Borders.empty(2, 10) })
        putClientProperty("strip", this)
    }

    init {
        label.text = lastText
        label.toolTipText = lastTooltip
    }

    fun show(stats: FormatStats?) {
        if (stats != null) {
            lastText = stats.toString()
            lastTooltip = "matched ${stats.matched} of ${stats.scanned} lines scanned" +
                (stats.note?.let { " — $it" } ?: "")
            label.text = lastText
            label.toolTipText = lastTooltip
        }
    }

    fun showDisabled() {
        label.text = "LogSmith highlighting disabled for this file"
        label.toolTipText = "Use the editor context menu to re-enable LogSmith for this file."
    }

    fun showEnabled() {
        label.text = lastText
        label.toolTipText = lastTooltip
    }
}
