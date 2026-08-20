package dev.zzzz.openexistingclone

import java.util.regex.Pattern

/**
 * Parses and normalizes Git remote URLs so that different spellings of the same
 * repository compare equal, e.g.
 *
 *   https://github.com/foo/bar.git
 *   git@github.com:foo/bar.git
 *   ssh://git@github.com:22/foo/bar
 *
 * all normalize to `github.com/foo/bar`.
 */
object GitUrls {

    private val SCP_LIKE = Pattern.compile("^[^/@\\s]+@([^:/\\s]+):(.+)$")
    private val BARE_HOST_PATH = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*/(.+)$")

    /** Lowercase `host/owner/repo` form, or null when the input is not a recognizable full remote URL. */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        // Drop query string / fragment, e.g. "...?tab=readme" or "...#readme"
        val cut = indexOfAny(s, '?', '#')
        if (cut >= 0) s = s.substring(0, cut)
        s = s.trim().trimEnd('/')
        if (s.isEmpty()) return null

        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) {
            val scheme = s.substring(0, schemeIdx).lowercase()
            if (scheme !in setOf("http", "https", "git", "ssh", "ftp", "ftps")) return null
            var rest = s.substring(schemeIdx + 3)
            if (rest.startsWith("@")) return null
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            var host = rest.substring(0, slash)
            val path = rest.substring(slash + 1)
            val at = host.indexOf('@')
            if (at >= 0) host = host.substring(at + 1)
            host = host.substringBefore(':')
            if (host.isBlank()) return null
            return join(host, path)
        }

        val scp = SCP_LIKE.matcher(s)
        if (scp.matches()) {
            return join(scp.group(1)!!, scp.group(2)!!)
        }

        // "github.com/foo/bar" pasted without a scheme
        if (s.substringBefore('/').contains('.')) {
            val m = BARE_HOST_PATH.matcher(s)
            if (m.matches()) {
                val host = s.substringBefore('/')
                return join(host, m.group(1)!!)
            }
        }
        return null
    }

    /** Lowercase `owner/repo` (last two segments) usable for suffix matching of short forms like "foo/bar". */
    fun ownerRepo(raw: String): String? {
        val norm = normalize(raw) ?: run {
            var s = raw.trim().trimEnd('/')
            val cut = indexOfAny(s, '?', '#')
            if (cut >= 0) s = s.substring(0, cut)
            s = s.trimEnd('/').removeSuffix(".git")
            val segs = s.split('/').filter { it.isNotBlank() }
            if (segs.size < 2) return null
            segs.subList(segs.size - 2, segs.size).joinToString("/")
        }
        val segs = norm.split('/')
        if (segs.size < 3) return norm
        return segs.subList(segs.size - 2, segs.size).joinToString("/")
    }

    /** Directory name the repository would be cloned into. */
    fun repoName(raw: String): String? {
        var s = raw.trim().trimEnd('/')
        val cut = indexOfAny(s, '?', '#')
        if (cut >= 0) s = s.substring(0, cut)
        s = s.trimEnd('/')
        val name = s.substringAfterLast('/')
        val cleaned = name.removeSuffix(".git").trim()
        return cleaned.ifBlank { null }
    }

    /** Heuristic for auto-filling from the clipboard: does this look like a Git remote reference? */
    fun looksLikeGitUrl(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length > 2048 || text.contains(' ') || text.contains('\n')) return false
        val t = text.trim()
        if (t.startsWith("git@")) return true
        val schemeIdx = t.indexOf("://")
        if (schemeIdx > 0) {
            val scheme = t.substring(0, schemeIdx).lowercase()
            if (scheme !in setOf("http", "https", "git", "ssh")) return false
            val host = t.substring(schemeIdx + 3).substringBefore('/')
            return host.contains('.') // an actual hostname, not a local file path
        }
        return false
    }

    private fun join(host: String, path: String): String? {
        val h = host.lowercase().trim()
        val p = path.trim().trimEnd('/').removeSuffix(".git")
        if (h.isBlank() || p.isBlank()) return null
        return "$h/$p".lowercase()
    }

    private fun indexOfAny(s: String, vararg chars: Char): Int {
        var best = -1
        for (c in chars) {
            val i = s.indexOf(c)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }
}
