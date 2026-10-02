package com.danilgorbunofff.logsmith.filter

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.awt.event.ItemListener
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The filter row above the editor (charter §5.6 group F): four level check-boxes and one
 * substring field, all feeding [listener] with a fresh [FilterState]. The bar never touches
 * the document; the session decides what a filter means.
 */
class LogSmithFilterBar(private val listener: (FilterState) -> Unit) {

    private val boxes: Map<LogLevel, JBCheckBox> =
        LogLevel.entries.associateWith { JBCheckBox(it.name.lowercase().replaceFirstChar { c -> c.titlecaseChar() }, true) }

    private val text = JBTextField(18).apply {
        emptyText.text = "text, logger or thread…"
        toolTipText = "Show only records whose line contains this text (ignoring case)"
    }

    private val fireTimer = Timer(400) { fire() }

    private var updating = false

    init {
        fireTimer.isRepeats = false
        val itemListener = ItemListener { if (!updating) fire() }
        boxes.values.forEach { it.addItemListener(itemListener) }
        text.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = restartTimer()
            override fun removeUpdate(e: DocumentEvent?) = restartTimer()
            override fun changedUpdate(e: DocumentEvent?) = restartTimer()
        })
    }

    val state: FilterState
        get() = FilterState(boxes.filterValues { it.isSelected }.keys, text.text)

    val root: JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JBLabel("Filter:"))
            LogLevel.entries.forEach { add(boxes[it] as Component) }
            add(text)
            add(JButton("Reset").apply { addActionListener { reset() } })
        }, BorderLayout.CENTER)
    }

    private fun reset() {
        updating = true
        try {
            boxes.values.forEach { it.isSelected = true }
            text.text = ""
        } finally {
            updating = false
        }
        fire()
    }

    private fun restartTimer() {
        if (!updating) {
            fireTimer.stop()
            fireTimer.start()
        }
    }

    private fun fire() {
        if (updating) return
        listener(state)
    }

    /** Test hook: skips the 400 ms debounce. */
    internal fun fireNow() {
        fireTimer.stop()
        fire()
    }
}
