package dev.zzzz.openexistingclone

import com.intellij.ide.util.PropertiesComponent
import java.nio.file.Path

/** A local directory that appears to be a clone of the requested repository. */
data class LocalCloneMatch(
    val path: Path,
    val originUrl: String?,
    /** true when the `origin` remote matches the requested URL exactly (modulo spelling). */
    val exact: Boolean,
)

/** URL history for the Local tab's repository combobox. */
internal object UrlHistory {
    private const val KEY = "dev.zzzz.open-existing-clone.history"
    private const val LIMIT = 15

    fun load(): List<String> =
        PropertiesComponent.getInstance().getValues(KEY)?.toList() ?: emptyList()

    fun add(url: String) {
        if (url.isBlank()) return
        val pc = PropertiesComponent.getInstance()
        val rest = load().filter { !it.equals(url, ignoreCase = true) }
        pc.setValues(KEY, (listOf(url) + rest).take(LIMIT).toTypedArray())
    }
}
