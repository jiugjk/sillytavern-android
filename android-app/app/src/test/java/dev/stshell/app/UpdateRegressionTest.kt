// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.junit.Assert.*
import org.junit.Test

class UpdateRegressionTest {
    @Test fun ciBuildTagsCompareActualAndroidCodes() {
        for (tag in listOf("v0.4.9-build15", "v0.4.9-build15-123")) {
            assertEquals(UpdateState.AVAILABLE, AppUpdate("0.4.9", tag, installedCode = 14).state)
            assertEquals(UpdateState.CURRENT, AppUpdate("0.4.9", tag, installedCode = 15).state)
            assertEquals(UpdateState.CURRENT, AppUpdate("0.4.9", tag, installedCode = 16).state)
            assertEquals(UpdateState.UNKNOWN, AppUpdate("0.4.9", tag).state)
        }
        assertEquals(UpdateState.CURRENT, AppUpdate("0.5.0", "v0.4.9-build15", installedCode = 16).state)
        assertEquals(UpdateState.AVAILABLE, AppUpdate("0.4.8", "v0.4.9-build15", installedCode = 14).state)
        assertEquals(UpdateState.UNKNOWN, AppUpdate("0.4.9", "v0.4.9-build999999999999999999999", installedCode = 14).state)
    }

    @Test fun malformedVersionsNeverClaimCurrent() {
        for (bad in listOf("", "nightly", "1foo", "1.2x.3", "1.2.3.4", "01.2.3", "1.2.3-01", "1.2.3+")) {
            assertFalse(bad, AppVersions.isVersion(bad))
            assertEquals(bad, UpdateState.UNKNOWN, AppUpdate("0.4.9", bad).state)
            assertEquals(bad, UpdateState.UNKNOWN, AppUpdate(bad, "0.5.0").state)
        }
    }

    @Test fun prereleasesUseNumericIdentifiersAndIgnoreMetadata() {
        assertTrue(AppVersions.isNewer("0.5.0-beta.10", "0.5.0-beta.2"))
        assertFalse(AppVersions.isNewer("0.5.0-beta.2", "0.5.0-beta.10"))
        assertEquals(0, AppVersions.compare("0.5.0+build1", "0.5.0+build2"))
        assertEquals(0, AppVersions.compare("0.5.0", "0.5.0+build2"))
        assertTrue(AppVersions.isNewer("1.0.0-alpha.beta", "1.0.0-alpha.1"))
        assertTrue(AppVersions.isNewer("1.0.0-alpha.1", "1.0.0-alpha"))
    }

    @Test fun apkCheckWorksWithoutAnInstallation() {
        var appCalls = 0
        val report = collectUpdates(emptyList(), {
            appCalls++
            AppUpdate("0.4.9", "0.5.0")
        }, { error("No content requests should be made") })
        assertEquals(1, appCalls)
        assertTrue(report.appUpdateAvailable)
        assertFalse(report.hasContentUpdate)
        assertTrue(report.components.isEmpty())
    }

    @Test fun missingInstallationStillReportsApkNetworkFailure() {
        val report = collectUpdates(emptyList(), {
            AppUpdate("0.4.9", error = "offline")
        }, { error("Unexpected content request") })
        assertEquals(1, report.unknown)
        assertFalse(report.appUpdateAvailable)
    }

    @Test fun contentFailureDoesNotHideApkUpdate() {
        val item = InstalledComponent(ComponentKind.SERVER, "ST", "SillyTavern/SillyTavern", "staging", "a".repeat(40))
        val report = collectUpdates(listOf(item), { AppUpdate("0.4.9", "0.5.0") }, {
            ComponentUpdate(it, error = "offline")
        })
        assertTrue(report.appUpdateAvailable)
        assertEquals(1, report.unknown)
    }

    @Test fun dismissAndReopenJoinOneFlight() {
        val flight = UpdateFlight<String>()
        val deliveries = mutableListOf<String>()
        val first: (String) -> Unit = { deliveries.add("old:$it") }
        val second: (String) -> Unit = { deliveries.add("new:$it") }
        assertTrue(flight.observe(first))
        flight.detach(first)
        assertTrue(flight.running)
        assertFalse(flight.observe(second))
        flight.complete("ok")
        assertEquals(listOf("new:ok"), deliveries)
        assertFalse(flight.running)
    }

    @Test fun destroyedActivityCannotDetachItsReplacement() {
        val flight = UpdateFlight<String>()
        val old: (String) -> Unit = { fail("Destroyed Activity received result") }
        var result = ""
        val current: (String) -> Unit = { result = it }
        assertTrue(flight.observe(old))
        assertFalse(flight.observe(current))
        flight.detach(old)
        flight.complete("ok")
        assertEquals("ok", result)
    }

    @Test fun completionAfterDismissAllowsFreshRetry() {
        val flight = UpdateFlight<String>()
        val closed: (String) -> Unit = { fail("Closed dialog received result") }
        assertTrue(flight.observe(closed))
        flight.detach(closed)
        flight.complete("failure")
        assertFalse(flight.running)
        assertTrue(flight.observe { })
    }

    @Test fun callbackCanStartAnotherCheck() {
        val flight = UpdateFlight<String>()
        var restarted = false
        flight.observe { restarted = flight.observe { } }
        flight.complete("ok")
        assertTrue(restarted)
        assertTrue(flight.running)
    }
}
