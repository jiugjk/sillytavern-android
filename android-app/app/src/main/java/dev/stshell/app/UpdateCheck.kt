// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONObject

/**
 * Update model. Everything here is pure: it turns "what is installed" plus
 * "what GitHub reports" into a report the panel can render, and it decides
 * nothing about the network. [UpdateChecker] owns the requests.
 */

enum class UpdateState { CURRENT, AVAILABLE, UNKNOWN }

enum class ComponentKind { SERVER, EXTENSION }

/** One installed, updatable component, as recorded by the on-device installer. */
data class InstalledComponent(
    val kind: ComponentKind,
    val name: String,
    val slug: String,
    val ref: String,
    val commit: String,
    val version: String = "",
)

/** An installed component paired with the newest commit GitHub reports for its ref. */
data class ComponentUpdate(
    val installed: InstalledComponent,
    val latestCommit: String = "",
    val error: String? = null,
) {
    val state: UpdateState
        get() = when {
            error != null || !UpdateCatalog.isCommit(latestCommit) -> UpdateState.UNKNOWN
            !UpdateCatalog.isCommit(installed.commit) -> UpdateState.UNKNOWN
            installed.commit.equals(latestCommit, ignoreCase = true) -> UpdateState.CURRENT
            else -> UpdateState.AVAILABLE
        }

    val installedShort: String get() = UpdateCatalog.shortCommit(installed.commit)
    val latestShort: String get() = UpdateCatalog.shortCommit(latestCommit)
}

/** The launcher APK itself, which the user installs manually from Releases. */
data class AppUpdate(
    val installedVersion: String,
    val latestVersion: String = "",
    val releaseUrl: String = "",
    val error: String? = null,
) {
    val state: UpdateState
        get() = when {
            error != null || latestVersion.isEmpty() -> UpdateState.UNKNOWN
            AppVersions.isNewer(latestVersion, installedVersion) -> UpdateState.AVAILABLE
            else -> UpdateState.CURRENT
        }
}

/** Everything one "check for updates" run found. */
data class UpdateReport(
    val components: List<ComponentUpdate> = emptyList(),
    val app: AppUpdate? = null,
) {
    /** Components the in-app reinstall can actually update. */
    val updatable: List<ComponentUpdate> get() = components.filter { it.state == UpdateState.AVAILABLE }

    /** True when "download and update" would change something. */
    val hasContentUpdate: Boolean get() = updatable.isNotEmpty()

    val appUpdateAvailable: Boolean get() = app?.state == UpdateState.AVAILABLE

    /** How many answers could not be obtained (network, rate limit, bad data). */
    val unknown: Int
        get() = components.count { it.state == UpdateState.UNKNOWN } +
            (if (app != null && app.state == UpdateState.UNKNOWN) 1 else 0)
}

/** Reads the installer's own records; never trusts a repository URL it did not validate. */
object UpdateCatalog {
    private val COMMIT = Regex("[0-9a-fA-F]{40}")
    private val REPOSITORY = Regex("https://github\\.com/([A-Za-z0-9_.-]{1,64})/([A-Za-z0-9_.-]{1,64})")

    fun isCommit(value: String?): Boolean = value != null && COMMIT.matches(value)

    fun shortCommit(value: String): String = if (isCommit(value)) value.substring(0, 7) else "—"

    /** "owner/repo" for a github.com HTTPS URL, or null for anything else. */
    fun slug(repository: String?): String? {
        val match = REPOSITORY.matchEntire(repository?.trim()?.removeSuffix("/") ?: return null) ?: return null
        val owner = match.groupValues[1]
        val name = match.groupValues[2].removeSuffix(".git")
        if (owner == "." || owner == ".." || name.isEmpty() || name == "." || name == "..") return null
        return "$owner/$name"
    }

    /**
     * Parses the components out of installation-info.json (or an installed
     * payload-manifest.json, which carries the same upstream/extensions shape).
     * Entries without a usable repository or commit are dropped rather than
     * reported as "unknown": they are not something this launcher installed.
     */
    fun installed(info: JSONObject): List<InstalledComponent> {
        val result = mutableListOf<InstalledComponent>()
        val upstream = info.optJSONObject("upstream")
        if (upstream != null) {
            component(ComponentKind.SERVER, "SillyTavern", upstream)?.let(result::add)
        }
        val extensions = info.optJSONArray("extensions")
        if (extensions != null) {
            for (index in 0 until extensions.length()) {
                val entry = extensions.optJSONObject(index) ?: continue
                val name = entry.optString("displayName").ifEmpty { entry.optString("name") }
                component(ComponentKind.EXTENSION, name, entry)?.let(result::add)
            }
        }
        return result
    }

    private fun component(kind: ComponentKind, name: String, entry: JSONObject): InstalledComponent? {
        val slug = slug(entry.optString("repository")) ?: return null
        val commit = entry.optString("commit")
        if (!isCommit(commit)) return null
        val ref = entry.optString("ref").ifEmpty { "HEAD" }
        if (ref.length > 128 || ref.any { it.isWhitespace() }) return null
        return InstalledComponent(kind, name.ifEmpty { slug }, slug, ref, commit, entry.optString("version"))
    }
}

/** Compares launcher versionNames and release tags (v0.5.0, 0.5.0-beta.2, …). */
object AppVersions {
    fun normalize(value: String?): String = (value ?: "").trim().removePrefix("v").removePrefix("V").trim()

    private fun parts(value: String): Pair<List<Int>, String> {
        val core = value.takeWhile { it != '-' && it != '+' }
        val suffix = value.removePrefix(core).removePrefix("-").removePrefix("+")
        val numbers = core.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: -1 }
        return numbers to suffix
    }

    /** True when [value] looks like a comparable MAJOR[.MINOR[.PATCH]] version. */
    fun isVersion(value: String?): Boolean {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return false
        val (numbers, _) = parts(normalized)
        return numbers.isNotEmpty() && numbers.all { it >= 0 }
    }

    /** Negative when [left] is older, positive when newer, 0 when equivalent. */
    fun compare(left: String, right: String): Int {
        val (leftNumbers, leftSuffix) = parts(normalize(left))
        val (rightNumbers, rightSuffix) = parts(normalize(right))
        for (index in 0 until maxOf(leftNumbers.size, rightNumbers.size)) {
            val a = leftNumbers.getOrElse(index) { 0 }.coerceAtLeast(0)
            val b = rightNumbers.getOrElse(index) { 0 }.coerceAtLeast(0)
            if (a != b) return a.compareTo(b)
        }
        // 1.2.0 is newer than 1.2.0-rc1; two pre-releases compare by their tail.
        if (leftSuffix.isEmpty() && rightSuffix.isEmpty()) return 0
        if (leftSuffix.isEmpty()) return 1
        if (rightSuffix.isEmpty()) return -1
        return leftSuffix.compareTo(rightSuffix)
    }

    /** Only claims an update when both versions are actually comparable. */
    fun isNewer(latest: String, installed: String): Boolean =
        isVersion(latest) && isVersion(installed) && compare(latest, installed) > 0
}
