package dev.zzzz.openexistingclone

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vcs.checkout.CompositeCheckoutListener
import com.intellij.util.ui.cloneDialog.VcsCloneDialog

/**
 * Overrides the built-in "Get from Version Control" action so the clone dialog opens with the
 * Local (本地已有) source preselected: paste a repository URL and, when a local clone exists,
 * it is opened instead of cloned again; otherwise the standard clone flow runs unchanged.
 */
class SmartGetFromVcsAction : AnAction(
    "Get from Version Control…",
    "Get from Version Control, opening an existing local clone when possible (默认优先打开本地已有克隆)",
    IconLoader.getIcon("/icons/openExistingClone.svg", SmartGetFromVcsAction::class.java),
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: ProjectManager.getInstance().defaultProject
        val dialog = VcsCloneDialog.Builder(project).forExtension(LocalCloneDialogExtension::class.java)
        if (dialog.showAndGet()) {
            dialog.doClone(CompositeCheckoutListener(project))
        }
    }
}
