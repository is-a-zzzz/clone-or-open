package dev.zzzz.openexistingclone

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "OpenExistingCloneSettings", storages = [Storage("open-existing-clone.xml")])
class OpenExistingCloneSettings : PersistentStateComponent<OpenExistingCloneSettings.PluginState> {

    data class PluginState(
        /** Extra directories to scan for existing clones, in addition to the built-in defaults. */
        var extraRoots: MutableList<String> = mutableListOf(),
        /** Also list directories whose name equals the repository name when the origin remote differs or is missing. */
        var matchByName: Boolean = true,
        /** How many directory levels below each root to scan. */
        var scanDepth: Int = 2,
    )

    private var myState = PluginState()

    override fun getState(): PluginState = myState

    override fun loadState(loaded: PluginState) {
        myState = loaded
    }

    companion object {
        @JvmStatic
        fun getInstance(): OpenExistingCloneSettings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(OpenExistingCloneSettings::class.java)
    }
}
