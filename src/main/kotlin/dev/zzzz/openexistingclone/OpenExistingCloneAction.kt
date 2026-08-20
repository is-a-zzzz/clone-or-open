package dev.zzzz.openexistingclone

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.checkout.CheckoutListener
import com.intellij.ui.DoubleClickListener
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * File | Open Existing Clone… — paste a repository URL and open the local clone directly,
 * without hunting for the directory manually.
 */
class OpenExistingCloneAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        OpenExistingCloneDialog(e.project).show()
    }
}

private class OpenExistingCloneDialog(private val contextProject: Project?) : DialogWrapper(contextProject, true) {

    private lateinit var panel: CloneSearchPanel

    init {
        title = "Open Existing Clone (打开本地已有克隆)"
        setOKButtonText("Open (打开)")
        init()
    }

    override fun createCenterPanel(): JComponent {
        if (!::panel.isInitialized) {
            // No cross-directory search: only the IDE's default project directory is checked,
            // the same parent directory the built-in clone form defaults to.
            val root = com.intellij.ide.GeneralSettings.getInstance().defaultProjectDirectory
                ?.let { runCatching { java.nio.file.Paths.get(it) }.getOrNull() }
            panel = CloneSearchPanel(ModalityStateHack.modalityFor(this), {
                isOKActionEnabled = true
                initValidation()
            }, root)
            panel.prefillFromClipboard()
            panel.requestRescan()

            object : DoubleClickListener() {
                override fun onDoubleClick(event: MouseEvent): Boolean {
                    if (panel.selectedMatch != null) {
                        doOKAction()
                        return true
                    }
                    return false
                }
            }.installOn(panel.matchList)
        }
        return panel.component
    }

    override fun getPreferredFocusedComponent(): JComponent = panel.urlField

    override fun doValidateAll(): List<ValidationInfo> =
        panel.validation()?.let { listOf(it) } ?: emptyList()

    override fun doOKAction() {
        val match = panel.selectedMatch
        if (match != null) {
            panel.rememberUrl()
            OpenExistingCloneOpener.open(contextProject, match)
        }
        super.doOKAction()
    }

    override fun dispose() {
        if (::panel.isInitialized) panel.dispose()
        super.dispose()
    }
}

object OpenExistingCloneOpener {

    /**
     * Reuses the platform's checkout-completion pipeline: it opens the directory as a project,
     * runs the trust dialog when needed and registers it in recent projects.
     * The pipeline asserts a non-EDT thread, so it is invoked from a pooled thread.
     */
    fun open(contextProject: Project?, match: LocalCloneMatch) {
        val project = when {
            contextProject == null || contextProject.isDisposed || contextProject.isDefault ->
                ProjectManager.getInstance().defaultProject
            else -> contextProject
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            CheckoutListener.EP_NAME.extensionList.any { it.processCheckedOutDirectory(project, match.path) }
        }
    }
}

/** Small shim so the dialog file does not import ModalityState for a single call. */
private object ModalityStateHack {
    fun modalityFor(dialog: DialogWrapper) =
        com.intellij.openapi.application.ModalityState.stateForComponent(dialog.contentPane)
}
