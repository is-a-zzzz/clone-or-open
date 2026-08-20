package dev.zzzz.openexistingclone

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class OpenExistingCloneConfigurable : Configurable {

    private val settings = OpenExistingCloneSettings.getInstance()

    private val extraRootsArea = JBTextArea(8, 46)
    private val matchByNameCheckBox = JBCheckBox("Also list directories that only match by name (同名目录也算匹配)")
    private val depthSpinner = JSpinner(SpinnerNumberModel(2, 1, 4, 1))

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(
            JBLabel(
                "Extra directories to search for existing clones — one absolute path per line, ~ allowed (额外搜索根目录，每行一个绝对路径，支持 ~):",
                com.intellij.util.ui.UIUtil.ComponentStyle.SMALL,
                com.intellij.util.ui.UIUtil.FontColor.BRIGHTER,
            )
        )
        .addComponent(JBScrollPane(extraRootsArea))
        .addComponent(matchByNameCheckBox)
        .addLabeledComponent("Directory scan depth (目录扫描深度):", depthSpinner)
        .addComponent(
            JBLabel(
                "Built-in roots are always searched: ~/IdeaProjects, ~/PycharmProjects, ~/WebStormProjects, ~/RustRoverProjects and similar.",
                com.intellij.util.ui.UIUtil.ComponentStyle.SMALL,
                com.intellij.util.ui.UIUtil.FontColor.BRIGHTER,
            )
        )
        .panel

    override fun isModified(): Boolean {
        val state = settings.state
        return extraRootsArea.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() } != state.extraRoots ||
                matchByNameCheckBox.isSelected != state.matchByName ||
                (depthSpinner.value as Int) != state.scanDepth
    }

    override fun reset() {
        val state = settings.state
        extraRootsArea.text = state.extraRoots.joinToString("\n")
        matchByNameCheckBox.isSelected = state.matchByName
        depthSpinner.value = state.scanDepth
    }

    override fun apply() {
        val state = settings.state
        state.extraRoots = extraRootsArea.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        state.matchByName = matchByNameCheckBox.isSelected
        state.scanDepth = depthSpinner.value as Int
    }

    override fun getDisplayName(): String = "Open Existing Clone"
}
