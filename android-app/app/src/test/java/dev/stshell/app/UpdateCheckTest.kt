// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckTest {
    private val serverCommit = "a".repeat(40)
    private val extensionCommit = "b".repeat(40)

    private fun installationInfo() = JSONObject("""
        {"schemaVersion":1,
         "payloadId":"${"c".repeat(64)}",
         "upstream":{"repository":"https://github.com/SillyTavern/SillyTavern","ref":"staging",
                     "commit":"$serverCommit","version":"1.13.5"},
         "extensions":[
           {"name":"ST-Prompt-Template","displayName":"Prompt Template",
            "repository":"https://github.com/zonde306/ST-Prompt-Template","ref":"HEAD","commit":"$extensionCommit"},
           {"name":"no-repo","repository":"","ref":"HEAD","commit":"$extensionCommit"}]}
    """.trimIndent())

    @Test fun onlyGithubHttpsRepositoriesAreAccepted() {
        assertEquals("SillyTavern/SillyTavern", UpdateCatalog.slug("https://github.com/SillyTavern/SillyTavern"))
        assertEquals("a/b", UpdateCatalog.slug("https://github.com/a/b/"))
        assertEquals("a/b", UpdateCatalog.slug("https://github.com/a/b.git"))
        for (value in listOf(null, "", "http://github.com/a/b", "https://evil.invalid/a/b",
            "https://github.com/a/b/../c", "https://user@github.com/a/b", "https://github.com/a",
            "https://github.com.evil.invalid/a/b")) {
            assertNull(value, UpdateCatalog.slug(value))
        }
    }

    @Test fun installedComponentsComeFromTheInstallerRecord() {
        val components = UpdateCatalog.installed(installationInfo())
        // The entry without a usable repository is dropped, not reported unknown.
        assertEquals(2, components.size)
        val server = components.first()
        assertEquals(ComponentKind.SERVER, server.kind)
        assertEquals("SillyTavern/SillyTavern", server.slug)
        assertEquals("staging", server.ref)
        assertEquals(serverCommit, server.commit)
        assertEquals("1.13.5", server.version)
        val extension = components[1]
        assertEquals(ComponentKind.EXTENSION, extension.kind)
        assertEquals("Prompt Template", extension.name)
        assertEquals("HEAD", extension.ref)
        assertTrue(UpdateCatalog.installed(JSONObject("{}")).isEmpty())
    }

    @Test fun componentStateNeedsBothCommits() {
        val installed = UpdateCatalog.installed(installationInfo()).first()
        assertEquals(UpdateState.CURRENT, ComponentUpdate(installed, serverCommit).state)
        assertEquals(UpdateState.CURRENT, ComponentUpdate(installed, serverCommit.uppercase()).state)
        assertEquals(UpdateState.AVAILABLE, ComponentUpdate(installed, "d".repeat(40)).state)
        assertEquals(UpdateState.UNKNOWN, ComponentUpdate(installed, "not-a-commit").state)
        assertEquals(UpdateState.UNKNOWN, ComponentUpdate(installed, error = "offline").state)
        assertEquals(UpdateState.UNKNOWN, ComponentUpdate(installed.copy(commit = ""), serverCommit).state)
        assertEquals("aaaaaaa", ComponentUpdate(installed, serverCommit).installedShort)
        assertEquals("—", ComponentUpdate(installed, "").latestShort)
    }

    @Test fun reportCountsWhatCanBeUpdatedAndWhatFailed() {
        val components = UpdateCatalog.installed(installationInfo())
        val report = UpdateReport(
            listOf(ComponentUpdate(components[0], "d".repeat(40)),
                ComponentUpdate(components[1], error = "offline")),
            AppUpdate("0.4.9", "0.5.0", "https://github.com/jiugjk/sillytavern-android/releases/tag/v0.5.0"))
        assertTrue(report.hasContentUpdate)
        assertEquals(1, report.updatable.size)
        assertEquals(1, report.unknown)
        assertTrue(report.appUpdateAvailable)

        val quiet = UpdateReport(listOf(ComponentUpdate(components[0], serverCommit)), AppUpdate("0.4.9", "0.4.9"))
        assertFalse(quiet.hasContentUpdate)
        assertFalse(quiet.appUpdateAvailable)
        assertEquals(0, quiet.unknown)
        assertEquals(UpdateState.CURRENT, quiet.app?.state)
    }

    @Test fun releaseTagsCompareNumericallyNotAlphabetically() {
        assertTrue(AppVersions.isNewer("v0.5.0", "0.4.9"))
        assertTrue(AppVersions.isNewer("0.10.0", "0.9.9"))
        assertTrue(AppVersions.isNewer("1.0", "0.99.99"))
        assertFalse(AppVersions.isNewer("0.4.9", "0.4.9"))
        assertFalse(AppVersions.isNewer("0.4.8", "0.4.9"))
        // A pre-release is older than the release it leads to.
        assertTrue(AppVersions.isNewer("0.5.0", "0.5.0-beta.1"))
        assertFalse(AppVersions.isNewer("0.5.0-beta.1", "0.5.0"))
        assertEquals(0, AppVersions.compare("v1.2.3", "1.2.3"))
        assertEquals(0, AppVersions.compare("1.2", "1.2.0"))
        // Nothing is "newer" when either side is not a version at all.
        assertFalse(AppVersions.isNewer("nightly", "0.4.9"))
        assertFalse(AppVersions.isNewer("0.5.0", ""))
        assertFalse(AppVersions.isVersion("nightly"))
        assertTrue(AppVersions.isVersion("v0.4.9"))
    }

    @Test fun appUpdateIsUnknownWithoutAnAnswer() {
        assertEquals(UpdateState.UNKNOWN, AppUpdate("0.4.9").state)
        assertEquals(UpdateState.UNKNOWN, AppUpdate("0.4.9", "0.5.0", error = "rate limit").state)
        assertEquals(UpdateState.AVAILABLE, AppUpdate("0.4.9", "0.5.0").state)
    }
}
