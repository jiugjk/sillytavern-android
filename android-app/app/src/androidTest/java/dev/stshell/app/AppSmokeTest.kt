// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test fun officialServerAndWebViewReachTheChatUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var latest = JSONObject()
        try {
            instrumentation.runOnMainSync { activity.closeControls(); SessionController.start() }
            val deadline = SystemClock.elapsedRealtime() + 240_000
            while (true) {
                instrumentation.runOnMainSync { latest = SessionController.diagnostic() }
                val ui = latest.getJSONObject("ui")
                assertFalse(latest.toString(2), ui.optString("phase") in setOf("failed", "browser-error"))
                if (ui.optString("phase") == "ready" && ui.optJSONObject("dom")?.optBoolean("chatInput") == true && ui.optJSONObject("dom")?.optBoolean("publicApi") == true) break
                assertTrue("App readiness timed out: $latest", SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(250)
            }
            val server = latest.getJSONObject("app-diagnostic.json")
            assertTrue(server.getJSONObject("health").getBoolean("passed"))
            assertEquals(5, server.getJSONObject("health").getJSONArray("tests").length())
            assertEquals("android", latest.getJSONObject("node-info.json").getString("platform"))
            assertEquals("arm64", latest.getJSONObject("node-info.json").getString("arch"))
            val previousActivity = activity
            var previousBrowser: android.view.View? = null
            val previousPid = latest.getJSONObject("node-info.json").getInt("pid")
            instrumentation.runOnMainSync {
                previousBrowser = activity.findViewById<LauncherLayout>(R.id.launcher_root).browserHost.getChildAt(0)
                activity.recreate()
            }
            val recreateDeadline = SystemClock.elapsedRealtime() + 15000
            while (activity === previousActivity) {
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull { it !== previousActivity }?.let { activity = it }
                }
                assertTrue("Activity recreation timed out", SystemClock.elapsedRealtime() < recreateDeadline)
                Thread.sleep(100)
            }
            instrumentation.runOnMainSync {
                val layout = activity.findViewById<LauncherLayout>(R.id.launcher_root)
                assertSame(previousBrowser, layout.browserHost.getChildAt(0))
                latest = SessionController.diagnostic()
            }
            assertEquals(previousPid, latest.getJSONObject("node-info.json").getInt("pid"))
            latest.put("webViewReusedAfterRecreate", true)
            // Check notification permission, foreground transitions and idle CPU state.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            ).use { it.readBytes() }
            var bridgeReady = false
            val bridgeDeadline = SystemClock.elapsedRealtime() + 15000
            while (!bridgeReady) {
                instrumentation.runOnMainSync { bridgeReady = SessionController.canProtect() }
                assertTrue("Activity observer did not become ready", SystemClock.elapsedRealtime() < bridgeDeadline)
                Thread.sleep(100)
            }
            instrumentation.runOnMainSync { SessionController.enableProtection() }
            val guardDeadline = SystemClock.elapsedRealtime() + 10000
            while (!BackgroundProtectionService.diagnostic().optBoolean("foreground")) {
                assertTrue("FGS did not start: ${BackgroundProtectionService.diagnostic()}", SystemClock.elapsedRealtime() < guardDeadline)
                Thread.sleep(100)
            }
            assertFalse(BackgroundProtectionService.diagnostic().optBoolean("wakeHeld"))
            instrumentation.runOnMainSync { SessionController.disableProtection() }
            val stopDeadline = SystemClock.elapsedRealtime() + 5000
            while (BackgroundProtectionService.running) {
                assertTrue(SystemClock.elapsedRealtime() < stopDeadline); Thread.sleep(100)
            }
            latest.put("backgroundControlsPassed", true).put("smokePassed", true)
        } catch (error: Throwable) {
            latest.put("smokePassed", false).put("smokeError", error.stackTraceToString())
            throw error
        } finally {
            AppFiles.writeJson(File(context.filesDir, "foreground-smoke.json"), latest)
            instrumentation.runOnMainSync { SessionController.disableProtection(); SessionController.stop(); activity.finish() }
        }
    }
}
