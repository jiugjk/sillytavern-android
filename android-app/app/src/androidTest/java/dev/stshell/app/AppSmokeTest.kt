// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var latest = JSONObject()
        try {
            instrumentation.runOnMainSync { SessionController.start() }
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
            latest.put("smokePassed", true)
        } catch (error: Throwable) {
            latest.put("smokePassed", false).put("smokeError", error.stackTraceToString())
            throw error
        } finally {
            AppFiles.writeJson(File(context.filesDir, "foreground-smoke.json"), latest)
            instrumentation.runOnMainSync { SessionController.stop(); activity.finish() }
        }
    }
}
