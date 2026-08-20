package dev.zzzz.openexistingclone

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** A local directory that appears to be a clone of the requested repository. */
data class LocalCloneMatch(
    val path: Path,
    val originUrl: String?,
    /** true when the `origin` remote matches the requested URL exactly (modulo spelling). */
    val exact: Boolean,
)

/**
 * Scans well-known project directories for an existing clone of a repository URL.
 *
 * Matching does not shell out to `git`; it reads `.git/config` directly, which is
 * fast and works even when `git` is not on PATH.
 */
class LocalCloneSearcher(private val settings: OpenExistingCloneSettings = OpenExistingCloneSettings.getInstance()) {

    /**
     * @param restrictToRoot when set, only this directory is scanned (no cross-directory search);
     *        when null, all configured roots are scanned.
     */
    fun search(url: String, restrictToRoot: Path? = null): List<LocalCloneMatch> {
        val target = GitUrls.normalize(url)
        val targetOwnerRepo = GitUrls.ownerRepo(url)
        val name = GitUrls.repoName(url)
        if (target == null && name == null) return emptyList()

        val exactMatches = LinkedHashMap<Path, LocalCloneMatch>()
        val nameMatches = LinkedHashMap<Path, LocalCloneMatch>()

        val rootsToScan = if (restrictToRoot != null) listOf(restrictToRoot) else roots()
        for (root in rootsToScan) {
            scanDir(root, 0, exactMatches, nameMatches, target, targetOwnerRepo, name)
        }

        val all = exactMatches.values + nameMatches.values
        return all.sortedWith(
            compareByDescending<LocalCloneMatch> { it.exact }
                .thenBy { it.path.nameCount }
                .thenBy { it.path.toString() }
        ).distinctBy { it.path }
    }

    /** Directories that are searched, in order. */
    fun roots(): List<Path> {
        val home = Paths.get(System.getProperty("user.home"))
        val defaults = DEFAULT_ROOT_NAMES.map { home.resolve(it) }
        val extra = settings.state.extraRoots.mapNotNull { raw ->            val expanded = if (raw.startsWith("~")) home.resolve(raw.removePrefix("~/")) else Paths.get(raw)
            expanded.toAbsolutePath().normalize()
        }
        return (defaults + extra)
            .filter { Files.isDirectory(it) }
            .distinct()
    }

    private fun scanDir(
        dir: Path,
        depth: Int,
        exactMatches: MutableMap<Path, LocalCloneMatch>,
        nameMatches: MutableMap<Path, LocalCloneMatch>,
        target: String?,
        targetOwnerRepo: String?,
        name: String?,
    ) {
        if (depth > settings.state.scanDepth) return
        val children = try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .limit(MAX_ENTRIES_PER_DIR)
                    .toList()
            }
        } catch (_: IOException) {
            return
        }

        for (child in children) {
            val fileName = child.fileName.toString()
            if (fileName.startsWith(".")) continue
            if (fileName in SKIPPED_NAMES) continue

            val origin = readOrigin(child)
            val originNorm = origin?.let { GitUrls.normalize(it) }
            if (target != null && originNorm != null && originNorm == target) {
                exactMatches[child] = LocalCloneMatch(child, origin, true)
            } else if (targetOwnerRepo != null && originNorm != null && originNorm.endsWith("/$targetOwnerRepo")) {
                exactMatches.putIfAbsent(child, LocalCloneMatch(child, origin, true))
            } else if (settings.state.matchByName && name != null && fileName.equals(name, ignoreCase = true)) {
                // The IDE refuses to clone into this directory because it exists — it is exactly
                // what the user is fighting with, so list it even if origin differs.
                nameMatches.putIfAbsent(child, LocalCloneMatch(child, origin, false))
            }

            if (depth + 1 <= settings.state.scanDepth) {
                scanDir(child, depth + 1, exactMatches, nameMatches, target, targetOwnerRepo, name)
            }
        }
    }

    /** Reads the `origin` remote URL from `<dir>/.git/config`; handles worktrees and submodules. */
    fun readOrigin(dir: Path): String? {
        val dotGit = dir.resolve(".git")
        val configPath = when {
            Files.isDirectory(dotGit) -> dotGit.resolve("config")
            Files.isRegularFile(dotGit) -> {
                val gitDir = runCatching {
                    Files.readAllLines(dotGit, StandardCharsets.UTF_8).firstOrNull()
                        ?.trim()?.removePrefix("gitdir:")?.trim()
                }.getOrNull() ?: return null
                if (gitDir.isBlank()) return null
                val p = Paths.get(gitDir)
                val resolved = if (p.isAbsolute) p else dir.resolve(p)
                resolved.resolve("config")
            }
            else -> return null
        }
        return parseOriginUrl(configPath)
    }

    private fun parseOriginUrl(config: Path): String? {
        if (!Files.isRegularFile(config)) return null
        val lines = try {
            Files.readAllLines(config, StandardCharsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        var inOrigin = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#")) continue
            if (line.startsWith("[")) {
                val normalized = line.replace('\'', '"')
                inOrigin = normalized.startsWith("[remote") && normalized.contains("\"origin\"")
                continue
            }
            if (inOrigin) {
                val lower = line.lowercase()
                if (lower.startsWith("url") && line.contains('=')) {
                    return line.substringAfter('=').trim().ifBlank { null }
                }
            }
        }
        return null
    }

    companion object {
        private const val MAX_ENTRIES_PER_DIR = 5_000L

        private val SKIPPED_NAMES = hashSetOf(
            "node_modules", "target", "build", "dist", "out", ".cache", "vendor",
            "Library", "Applications", "Movies", "Music", "Pictures", "Desktop", "Downloads", "Documents",
        )

        private val DEFAULT_ROOT_NAMES = listOf(
            // JetBrains per-IDE defaults
            "IdeaProjects", "IdeaProjects2", "PycharmProjects", "WebStormProjects", "WebstormProjects",
            "RustRoverProjects", "GoLandProjects", "CLionProjects", "DataGripProjects", "PhpStormProjects",
            "RubyMineProjects", "AndroidStudioProjects", "JetBrains",
            // common personal layout
            "dev", "Development", "projects", "Projects", "project", "code", "Code",
            "repos", "repositories", "src", "sources", "git", "github", "gitee", "work", "workspace", "clones",
        )
    }
}
