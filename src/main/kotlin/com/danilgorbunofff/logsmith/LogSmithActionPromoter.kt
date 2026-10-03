package com.danilgorbunofff.logsmith

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext

/**
 * F2 / Shift+F2 are the platform's "Next/Previous Highlighted Error" keys too, and the platform
 * action is enabled in every editor and listed first in the keymap, so without a promoter the
 * keystroke never reached LogSmith (charter R12). In an editor LogSmith is attached to, the log's
 * error walk goes first; everywhere else the keys keep their platform meaning.
 */
class LogSmithActionPromoter : ActionPromoter {

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return emptyList()
        if (editor.getUserData(LogSmithEditorSession.SESSION_KEY) == null) return emptyList()
        return actions.filterIsInstance<LogSmithGotoErrorAction>()
    }
}
