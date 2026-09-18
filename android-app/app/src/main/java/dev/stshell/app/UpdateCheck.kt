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
    val installedCode: Long? = null,
) {
    val state: UpdateState
        get() {
            val version = AppVersions.releaseVersion(latestVersion)
            if (error != null || !AppVersions.isVersion(version) || !AppVersions.isVersion(installedVersion)) {
                return UpdateState.UNKNOWN
            }
            // CI's -buildN[-run] is an Android versionCode, not a prerelease.
            // Compare against the actual APK code, including equal/newer installs.
            val code = AppVersions.releaseCode(latestVersion)
            if (AppVersions.isBuildTag(latestVersion)) {
                if (code == null) return UpdateState.UNKNOWN
                val current = installedCode?.takeIf { it > 0 } ?: return UpdateState.UNKNOWN
                return if (code > current) UpdateState.AVAILABLE else UpdateState.CURRENT
            }
            return if (AppVersions.isNewer(version, installedVersion)) UpdateState.AVAILABLE else UpdateState.CURRENT
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

    private val versionPattern = Regex("(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*)){0,2}(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?")
    private val buildTag = Regex("([0-9]+\\.[0-9]+\\.[0-9]+)-build([1-9][0-9]*)(?:-[0-9]+)?")

    fun isBuildTag(tag: String): Boolean = buildTag.matches(normalize(tag))
    fun releaseCode(tag: String): Long? = buildTag.matchEntire(normalize(tag))?.groupValues?.get(2)?.toLongOrNull()
    fun releaseVersion(tag: String): String = buildTag.matchEntire(normalize(tag))?.groupValues?.get(1) ?: normalize(tag)

    private fun parts(value: String): Pair<List<String>, List<String>> {
        val withoutMetadata = normalize(value).substringBefore('+')
        return withoutMetadata.substringBefore('-').split('.') to
            withoutMetadata.substringAfter('-', "").let { if (it.isEmpty()) emptyList() else it.split('.') }
    }

    /** Strict numeric core and SemVer identifiers; build metadata has no precedence. */
    fun isVersion(value: String?): Boolean {
        val normalized = normalize(value)
        if (!versionPattern.matches(normalized)) return false
        return parts(normalized).second.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }
    }

    private fun numericCompare(left: String, right: String): Int =
        if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)

    /** Negative when [left] is older, positive when newer, 0 when equivalent. */
    fun compare(left: String, right: String): Int {
        require(isVersion(left) && isVersion(right)) { "Invalid version" }
        val (leftNumbers, leftSuffix) = parts(left)
        val (rightNumbers, rightSuffix) = parts(right)
        for (index in 0 until maxOf(leftNumbers.size, rightNumbers.size)) {
            val order = numericCompare(leftNumbers.getOrElse(index) { "0" }, rightNumbers.getOrElse(index) { "0" })
            if (order != 0) return order
        }
        if (leftSuffix.isEmpty() && rightSuffix.isEmpty()) return 0
        if (leftSuffix.isEmpty()) return 1
        if (rightSuffix.isEmpty()) return -1
        for (index in 0 until minOf(leftSuffix.size, rightSuffix.size)) {
            val a = leftSuffix[index]; val b = rightSuffix[index]
            val aNumeric = a.all(Char::isDigit); val bNumeric = b.all(Char::isDigit)
            val order = when {
                aNumeric && bNumeric -> numericCompare(a, b)
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return leftSuffix.size.compareTo(rightSuffix.size)
    }

    /** Only claims an update when both versions are actually comparable. */
    fun isNewer(latest: String, installed: String): Boolean =
        isVersion(latest) && isVersion(installed) && compare(latest, installed) > 0
}
