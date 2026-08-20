package dev.zzzz.openexistingclone

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TextFieldWithHistory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.panel
import java.util.concurrent.Future
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** URL history shared by the Local tab and the Git (Open Existing) checkout provider. */
internal object UrlHistory {
    private const val KEY = "dev.zzzz.open-existing-clone.history"
    private const val LIMIT = 15

    fun load(): List<String> =
        com.intellij.ide.util.PropertiesComponent.getInstance().getValues(KEY)?.toList() ?: emptyList()

    fun add(url: String) {
        if (url.isBlank()) return
        val pc = com.intellij.ide.util.PropertiesComponent.getInstance()
        val rest = load().filter { !it.equals(url, ignoreCase = true) }
        pc.setValues(KEY, (listOf(url) + rest).take(LIMIT).toTypedArray())
    }

    val historySize: Int get() = LIMIT
}

/**
 * Reusable panel: paste a repository URL, see matching local clones, pick one.
 * Used by the standalone File | Open Existing Clone… dialog. The Get from VCS "Local" tab
 * builds the platform's own clone form instead (see [LocalCloneDialogExtension]).
 */
class CloneSearchPanel(
    private val modality: ModalityState,
    private val onChanged: () -> Unit,
    private val restrictToRoot: java.nio.file.Path? = null,
) {
    private val searcher = LocalCloneSearcher()
    private val alarmDisposable = Disposer.newDisposable()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, alarmDisposable)
    private var scanJob: Future<*>? = null
    private var scanToken = 0
    private var disposed = false
    private var clipboardChecked = false

    var scanning = false
        private set

    // The same control the built-in "Repository URL" tab uses (DvcsCloneDialogComponent),
    // so the URL row looks identical in both tabs.
    val urlField: TextFieldWithHistory = TextFieldWithHistory().apply {
        isUsePreferredSizeAsMinimum = false
        setHistorySize(UrlHistory.historySize)
        history = UrlHistory.load()
    }

    private val matchesModel = CollectionListModel<LocalCloneMatch>()

    val matchList: JBList<LocalCloneMatch> = JBList(matchesModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 8
        cellRenderer = MatchRenderer()
        emptyText.setText("Paste a repository URL to find local clones (粘贴仓库地址以查找本地克隆)")
        addListSelectionListener { onChanged() }
    }

    val component: JComponent = panel {
        row(VcsBundle.message("vcs.common.labels.url")) {
            cell(urlField).align(AlignX.FILL)
        }
        row(VcsBundle.message("vcs.common.labels.directory")) {
            cell(JBScrollPane(matchList)).align(AlignX.FILL)
        }.bottomGap(BottomGap.SMALL)
    }

    val selectedMatch: LocalCloneMatch?
        get() = matchList.selectedValue

    val urlText: String
        get() = urlField.text.trim()

    init {
        urlField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = requestRescan()
        })
    }

    /** Fills the URL field from the clipboard once, if it looks like a Git URL. */
    fun prefillFromClipboard() {
        if (clipboardChecked || urlText.isNotBlank()) return
        clipboardChecked = true
        val text = try {
            com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
        } catch (_: Exception) {
            null
        }
        if (GitUrls.looksLikeGitUrl(text)) {
            urlField.text = text
            requestRescan(immediate = true)
        }
    }

    fun requestRescan(immediate: Boolean = false) {
        alarm.cancelAllRequests()
        if (immediate) rescanNow() else alarm.addRequest({ rescanNow() }, 250)
    }

    fun rememberUrl() {
        UrlHistory.add(urlText)
        urlField.history = UrlHistory.load()
    }

    fun validation(): com.intellij.openapi.ui.ValidationInfo? {
        if (urlText.isEmpty()) return com.intellij.openapi.ui.ValidationInfo("Paste a repository URL (请粘贴仓库地址)", urlField)
        if (scanning) return com.intellij.openapi.ui.ValidationInfo("Searching local directories… (正在搜索本地目录…)")
        if (matchesModel.items.isEmpty()) {
            return com.intellij.openapi.ui.ValidationInfo("No local clone found under the search roots (搜索根目录下未找到本地克隆；可改用 Repository URL 标签页重新克隆)")
        }
        if (selectedMatch == null) return com.intellij.openapi.ui.ValidationInfo("Select a directory to open (选择要打开的目录)")
        return null
    }

    fun dispose() {
        disposed = true
        alarm.cancelAllRequests()
        scanJob?.cancel(true)
        Disposer.dispose(alarmDisposable)
    }

    private fun rescanNow() {
        if (disposed) return
        val text = urlText
        if (text.isEmpty()) {
            scanning = false
            applyMatches(emptyList(), "Paste a repository URL to find local clones (粘贴仓库地址以查找本地克隆)")
            return
        }
        scanning = true
        setStatus("Searching local directories… (正在搜索本地目录…)")
        onChanged()
        val token = ++scanToken
        scanJob?.cancel(true)
        scanJob = ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                searcher.search(text, restrictToRoot)
            } catch (t: Throwable) {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && token == scanToken) {
                    scanning = false
                    if (result.isEmpty()) {
                        applyMatches(result, "No local clone found — switch to the Repository URL tab to clone (未找到本地克隆，可切换到 Repository URL 标签页正常克隆)")
                    } else {
                        applyMatches(result, "Found ${result.size} local clone${if (result.size == 1) "" else "s"} (找到 ${result.size} 个本地克隆)")
                    }
                }
            }, modality)
        }
    }

    private fun applyMatches(matches: List<LocalCloneMatch>, status: String) {
        matchesModel.replaceAll(matches)
        setStatus(status)
        if (matches.isNotEmpty()) matchList.selectedIndex = 0
        onChanged()
    }

    /** Status lives in the list's empty-text — the native way IDE lists show their state. */
    private fun setStatus(status: String) {
        matchList.emptyText.setText(status)
    }

    private class MatchRenderer : com.intellij.ui.ColoredListCellRenderer<LocalCloneMatch>() {
        override fun customizeCellRenderer(
            list: JList<out LocalCloneMatch>,
            value: LocalCloneMatch,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            append(value.path.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (value.exact) {
                append("  origin matched", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, MATCH_GREEN))
            } else {
                append("  same name", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            toolTipText = value.originUrl?.let { "origin: $it" } ?: "no origin remote found (未找到 origin 远程)"
        }
    }

    companion object {
        private val MATCH_GREEN = com.intellij.ui.JBColor(0x2E7D32, 0x81C784)
    }
}
