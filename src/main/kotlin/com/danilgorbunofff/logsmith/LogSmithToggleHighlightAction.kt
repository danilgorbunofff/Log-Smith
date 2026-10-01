package com.danilgorbunofff.logsmith

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * One-click per-file switch (charter §5.4): toggles highlighting without
 * uninstalling or restarting, visible in the editor context menu of LogSmith files.
 */
class LogSmithToggleHighlightAction : AnAction() {

    private fun session(e: AnActionEvent): LogSmithEditorSession? =
        e.getData(CommonDataKeys.EDITOR)?.getUserData(LogSmithEditorSession.SESSION_KEY)

    override fun actionPerformed(e: AnActionEvent) {
        session(e)?.toggle()
    }

    override fun update(e: AnActionEvent) {
        val session = session(e)
        e.presentation.isEnabledAndVisible = session != null
        e.presentation.text = if (session?.isDisabled == true) "Enable LogSmith highlighting for this file"
        else "Disable LogSmith highlighting for this file"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
