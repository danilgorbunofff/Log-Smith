package com.danilgorbunofff.logsmith

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * F2 / Shift+F2 jump to the next (previous) ERROR record in the file, wrapping at the
 * ends (charter R12). The navigator works over the filter service's cached line facts,
 * so pressing the key without a filter applied does not re-scan the whole document.
 */
abstract class LogSmithGotoErrorAction(private val forward: Boolean) : AnAction() {

    private fun session(e: AnActionEvent): LogSmithEditorSession? =
        e.getData(CommonDataKeys.EDITOR)?.getUserData(LogSmithEditorSession.SESSION_KEY)

    override fun actionPerformed(e: AnActionEvent) {
        session(e)?.gotoError(forward)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = session(e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class LogSmithGotoNextErrorAction : LogSmithGotoErrorAction(true)

class LogSmithGotoPrevErrorAction : LogSmithGotoErrorAction(false)
