package dev.zzzz.openexistingclone

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vcs.CheckoutProvider
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.ui.VcsCloneComponent
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtension
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TextFieldWithHistory
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.dialog.VcsDialogUtils
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import java.nio.file.Paths
import java.util.concurrent.Future
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Adds a "Local (本地已有)" source to the Get from Version Control dialog.
 *
 * The pane mirrors the built-in "Repository URL" pane (`RepositoryUrlCloneDialogExtension`)
 * piece by piece — same "Version control" combobox + "More via plugins…" link strip, same
 * per-VCS clone form with all its rows — so the two panes are visually indistinguishable.
 * The only difference is behavior: pasting a URL that was already cloned locally switches
 * the OK button to "Open (打开)" and opens the existing clone instead of cloning again;
 * otherwise the standard clone flow runs unchanged.
 */
class LocalCloneDialogExtension : VcsCloneDialogExtension {

    override fun getName(): String = "Local (本地已有)"

    override fun getIcon(): Icon = IconLoader.getIcon("/icons/openExistingClone.svg", javaClass)

    override fun getTooltip(): String =
        "Open a repository that was already cloned on this machine, or clone it normally (打开本机已有的克隆；未找到时正常克隆)"

    override fun createMainComponent(project: Project, modalityState: ModalityState): VcsCloneDialogExtensionComponent =
        LocalCloneExtensionComponent(project, modalityState)

    private class LocalCloneExtensionComponent(
        private val project: Project,
        private val modalityState: ModalityState,
    ) : VcsCloneDialogExtensionComponent() {

        private val alarmParent = Disposer.newDisposable()
        private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, alarmParent)
        private var searchJob: Future<*>? = null

        @Volatile
        private var matches: List<LocalCloneMatch> = emptyList()

        @Volatile
        private var searchedUrl: String? = null

        private var activeUrlEditor: TextFieldWithHistory? = null

        private var activeDirectoryField: TextFieldWithBrowseButton? = null

        private val vcsComponents = HashMap<CheckoutProvider, VcsCloneComponent>()
        private val centerPanel = Wrapper()
        private lateinit var comboBox: ComboBox<CheckoutProvider>

        private val mainPanel = JPanel(BorderLayout())

        init {
            Disposer.register(this, alarmParent)

            val providers = CheckoutProvider.EXTENSION_POINT_NAME.extensionList
                .sortedWith(CheckoutProvider.CheckoutProviderComparator())
            // Same as the built-in pane: preselect the first VCS (Git) so the form is visible right away
            val preselected = providers.firstOrNull()

            // Same North strip the default pane builds: "Version control:" combobox + More-via-plugins link.
            val northPanel = panel {
                row(VcsBundle.message("vcs.common.labels.version.control")) {
                    comboBox(
                        providers,
                        textListCellRenderer<CheckoutProvider>("") { UIUtil.removeMnemonic(it.vcsName) },
                    ).applyToComponent { selectedItem = null }
                        .apply { comboBox = component }
                    cell(VcsDialogUtils.getMorePluginsLink(mainPanel))
                }
            }
            northPanel.border = JBUI.Borders.empty(UIUtil.PANEL_REGULAR_INSETS)
            mainPanel.add(northPanel, BorderLayout.NORTH)
            mainPanel.add(centerPanel, BorderLayout.CENTER)

            comboBox.addItemListener { e ->
                if (e.stateChange == ItemEvent.SELECTED) {
                    val provider = e.item as CheckoutProvider
                    val component = vcsComponents.getOrPut(provider) {
                        provider.buildVcsCloneComponent(project, modalityState, dialogStateListener)
                            .also { built -> Disposer.register(this, built as Disposable) }
                    }
                    centerPanel.setContent(component.getView())
                    centerPanel.revalidate()
                    centerPanel.repaint()
                    watchUrlField(component)
                }
            }
            preselected?.let { comboBox.selectedItem = it }
        }

        override fun getView(): JComponent = mainPanel

        override fun getPreferredFocusedComponent(): JComponent =
            currentComponent()?.getPreferredFocusedComponent() ?: mainPanel

        override fun onComponentSelected() {
            dialogStateListener.onOkActionNameChanged(okButtonText())
            dialogStateListener.onOkActionEnabled(true)
            currentComponent()?.onComponentSelected(dialogStateListener)
        }

        override fun doValidateAll(): List<ValidationInfo> {
            val infos = currentComponent()?.doValidateAll() ?: return emptyList()
            // When a local clone is found, the embedded form's own "directory already exists
            // and not empty" rejection is exactly the situation we want to allow — drop it,
            // otherwise the dialog disables the OK button and Open can never fire.
            if (matches.isEmpty() || currentUrl() != searchedUrl) return infos
            val dir = activeDirectoryField ?: return infos
            return infos.filterNot { info ->
                val c = info.component ?: return@filterNot false
                c == dir || dir.isAncestorOf(c) || (c is java.awt.Container && c.isAncestorOf(dir))
            }
        }

        override fun doClone(listener: CheckoutProvider.Listener) {
            val url = currentUrl()
            if (url.isNotBlank() && url == searchedUrl && matches.isNotEmpty()) {
                val match = matches.first()
                UrlHistory.add(url)
                // The checkout-completion pipeline (trust dialog, project open) asserts a non-EDT thread
                ApplicationManager.getApplication().executeOnPooledThread {
                    listener.directoryCheckedOut(match.path.toFile(), null)
                    listener.checkoutCompleted()
                }
                return
            }
            currentComponent()?.doClone(listener)
        }

        private fun currentComponent(): VcsCloneComponent? =
            (comboBox.selectedItem as? CheckoutProvider)?.let { vcsComponents[it] }

        private fun currentUrl(): String = activeUrlEditor?.text?.trim().orEmpty()

        private fun okButtonText(): String =
            if (matches.isNotEmpty() && currentUrl() == searchedUrl) "Open (打开)"
            else currentComponent()?.getOkButtonText() ?: VcsBundle.message("clone.dialog.clone.button")

        /** Hooks the background local-clone search onto the form's URL field. */
        private fun watchUrlField(component: VcsCloneComponent) {
            val view = component.getView()
            val editor = findUrlEditor(view) ?: return
            activeUrlEditor = editor
            activeDirectoryField = findDirectoryField(view)
            editor.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = scheduleSearch()
            })
        }

        private fun findDirectoryField(c: java.awt.Component?): TextFieldWithBrowseButton? {
            if (c == null) return null
            if (c is TextFieldWithBrowseButton) return c
            for (child in (c as? java.awt.Container)?.components.orEmpty()) {
                findDirectoryField(child)?.let { return it }
            }
            return null
        }

        private fun scheduleSearch() {
            alarm.cancelAllRequests()
            alarm.addRequest({ runSearch() }, 300)
        }

        private fun runSearch() {
            val url = currentUrl()
            val repoName = GitUrls.repoName(url)
            val dirText = activeDirectoryField?.text?.trim().orEmpty()
            val token = "$url|$dirText"

            // Only the directory the form itself points at is considered — no cross-directory scan.
            // The target must carry the repository name, otherwise the field still shows just the parent.
            if (url.isBlank() || repoName == null || dirText.isBlank() ||
                Paths.get(dirText).fileName?.toString()?.equals(repoName, ignoreCase = true) != true
            ) {
                matches = emptyList()
                searchedUrl = null
                dialogStateListener.onOkActionNameChanged(okButtonText())
                return
            }

            val target = Paths.get(dirText)
            searchJob?.cancel(true)
            searchJob = ApplicationManager.getApplication().executeOnPooledThread {
                val existsNonEmpty = try {
                    java.nio.file.Files.isDirectory(target) &&
                            java.nio.file.Files.newDirectoryStream(target).use { it.iterator().hasNext() }
                } catch (_: Exception) {
                    false
                }
                ApplicationManager.getApplication().invokeLater({
                    if (token == "${currentUrl()}|${activeDirectoryField?.text?.trim().orEmpty()}") {
                        matches = if (existsNonEmpty) listOf(LocalCloneMatch(target, url, true)) else emptyList()
                        searchedUrl = currentUrl()
                        dialogStateListener.onOkActionNameChanged(okButtonText())
                        if (existsNonEmpty) dialogStateListener.onOkActionEnabled(true)
                    }
                }, modalityState)
            }
        }

        private fun findUrlEditor(c: java.awt.Component?): TextFieldWithHistory? {
            if (c == null) return null
            if (c is TextFieldWithHistory) return c
            for (child in (c as? java.awt.Container)?.components.orEmpty()) {
                findUrlEditor(child)?.let { return it }
            }
            return null
        }
    }
}
