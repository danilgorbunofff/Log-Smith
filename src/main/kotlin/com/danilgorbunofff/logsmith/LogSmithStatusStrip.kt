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

    val root: JComponent = JPanel(BorderLayout()).apply {
        add(JBUI.Panels.simplePanel(label).apply { border = JBUI.Borders.empty(2, 10) })
        putClientProperty("strip", this)
    }

    init {
        label.text = FormatStats.UNKNOWN
        label.toolTipText = "detection runs after the first 200 lines are indexed."
    }

    fun show(stats: FormatStats?) {
        if (stats != null) {
            label.text = stats.toString()
            label.toolTipText = "matched ${stats.matched} of ${stats.scanned} lines scanned (first 200)"
        }
    }
}
