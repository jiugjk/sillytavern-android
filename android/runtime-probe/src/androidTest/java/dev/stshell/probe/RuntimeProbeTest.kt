// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.probe

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeProbeTest {
    @Test fun node26RunsTwiceInSeparateServiceProcesses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, ProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProbeActivity
        try {
            var id = ""
            instrumentation.runOnMainSync { id = activity.startSelfTest("instrumentation") }
            val deadline = SystemClock.elapsedRealtime() + 360_000
            while (ProbeCoordinator.running) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    ProbeCoordinator.cancel(context)
                    fail("Runtime self-test coordinator timed out")
                }
                Thread.sleep(100)
            }
            val report = ProbeCoordinator.readJson(ProbeCoordinator.reportFile(context))
            assertEquals(id, report.getString("executionId"))
            assertTrue(report.toString(2), report.getBoolean("completed"))
            assertTrue(report.toString(2), report.getBoolean("passed"))
            assertTrue(report.getBoolean("uiResponsiveAfterRuns"))
            assertEquals(Process.myPid(), report.getInt("runnerPid"))
            assertTrue(report.getJSONObject("device").getInt("api") >= 34)
            assertEquals("arm64-v8a", report.getJSONObject("device").getString("abi"))
            assertTrue(report.getJSONObject("device").getInt("pageSize") in setOf(4096, 16384))
            val runs = report.getJSONArray("runs")
            assertEquals(2, runs.length())
            val pids = mutableSetOf<Int>()
            repeat(2) { index ->
                val entry = runs.getJSONObject(index)
                val probe = entry.getJSONObject("report")
                pids.add(ProbeReportValidator.verifyRun(probe, entry.getJSONObject("exit"), probe.getString("nonce"),
                    report.getJSONObject("runtimeManifest").getString("nodeVersion"), Process.myPid(), pids))
            }
            assertFalse(activity.isFinishing)
        } finally {
            if (ProbeCoordinator.running) ProbeCoordinator.cancel(context)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
