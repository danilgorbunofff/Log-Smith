package com.danilgorbunofff.logsmith

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys

/**
 * One-click per-file switch (charter §5.4): toggles highlighting without
 * uninstalling or restarting, visible in the editor context menu.
 */
class LogSmithToggleHighlightAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        (e.getData(PlatformDataKeys.FILE_EDITOR) as? LogSmithTextEditor)?.onToggle()
    }

    override fun update(e: AnActionEvent) {
        val wrapper = e.getData(PlatformDataKeys.FILE_EDITOR) as? LogSmithTextEditor
        e.presentation.isEnabledAndVisible = wrapper != null
        val disabled = wrapper?.file?.getUserData(logSmithHighlightDisabled) == true
        e.presentation.text = if (disabled) "Enable LogSmith highlighting for this file"
        else "Disable LogSmith highlighting for this file"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
