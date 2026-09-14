// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.probe

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** UI and instrumentation share one process-wide coordinator; rotation does not restart Node. */
internal object ProbeCoordinator {
    private val active = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeNonce: String? = null
    @Volatile private var latestJson: String? = null
    @Volatile var requiresRestart = false
        private set
    @Volatile var executionId: String? = null
        private set
    val running: Boolean get() = active.get()

    fun reportFile(context: Context) = File(context.filesDir, "runtime-probe-device.json")

    fun readJson(file: File): JSONObject {
        check(file.length() <= 1024 * 1024) { "Report exceeds diagnostic size bound; run the updated self-test" }
        return JSONObject(file.readText())
    }

    fun currentReport(context: Context): JSONObject? {
        latestJson?.let { return JSONObject(it) }
        val file = reportFile(context)
        return if (file.isFile) readJson(file) else null
    }

    fun start(context: Context, source: String = "app-self-test"): String {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Start diagnostics from the visible activity" }
        check(!requiresRestart) { "Previous Node process exit is unconfirmed; force-stop and reopen this diagnostic app" }
        check(active.compareAndSet(false, true)) { "A diagnostic is already running" }
        val app = context.applicationContext
        cancelled.set(false)
        val id = UUID.randomUUID().toString().replace("-", "")
        executionId = id
        val report = JSONObject().put("schemaVersion", 1).put("executionId", id)
            .put("evidenceSource", source).put("packageName", app.packageName)
            .put("runnerPid", Process.myPid()).put("completed", false).put("passed", false)
            .put("status", "collecting-device-info").put("startedAt", Instant.now().toString())
            .put("runs", JSONArray())
        try {
            // Invalidate an older PASS before start() returns.
            publish(app, report)
            Thread({ execute(app, report) }, "RuntimeSelfTest").start()
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.stackTraceToString())
            latestJson = report.toString(2)
            active.set(false)
            throw error
        }
        return id
    }

    fun cancel(context: Context) {
        if (!running) return
        cancelled.set(true)
        activeNonce?.let { sendCancel(context.applicationContext, it) }
    }

    private fun execute(context: Context, report: JSONObject) {
        val started = SystemClock.elapsedRealtime()
        val runs = report.getJSONArray("runs")
        val serviceRequested = AtomicBoolean(false)
        try {
            report.put("device", JSONObject()
                .put("api", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put("is64Bit", Process.is64Bit())
                .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
                .put("fingerprint", Build.FINGERPRINT))
            val runtime = context.assets.open("runtime-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            val probe = context.assets.open("probe-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            // Keep reports small enough to copy in chat. These are copied values
            // from packaged manifests, not invented device measurements.
            report.put("runtimeManifest", JSONObject()
                .put("schemaVersion", runtime.getInt("schemaVersion"))
                .put("buildIdentity", runtime.getString("buildIdentity"))
                .put("nodeVersion", runtime.getString("nodeVersion"))
                .put("sourceSha256", runtime.getString("sourceSha256")))
            report.put("probeManifest", JSONObject()
                .put("schemaVersion", probe.getInt("schemaVersion"))
                .put("probeIdentity", probe.getString("probeIdentity")))
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appInfo = JSONObject().put("versionCode", packageInfo.longVersionCode)
                .put("versionName", packageInfo.versionName)
            // Optional additional provenance, not a new capability gate.
            try {
                val apk = File(context.applicationInfo.sourceDir)
                val digest = MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                appInfo.put("apkSha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                    .put("apkBytes", apk.length())
            } catch (error: Exception) { appInfo.put("apkHashError", error.javaClass.simpleName) }
            report.put("app", appInfo)
            publish(context, report)
            check(Build.VERSION.SDK_INT >= 34 && Process.is64Bit() && Build.SUPPORTED_ABIS[0] == "arm64-v8a") {
                "Android14+ arm64 is required"
            }
            check(report.getJSONObject("device").getLong("pageSize") in setOf(4096L, 16384L)) { "Unexpected OS page size" }
            val pids = mutableSetOf<Int>()
            repeat(2) { index ->
                checkNotCancelled()
                val nonce = UUID.randomUUID().toString().replace("-", "")
                activeNonce = nonce
                val directory = File(context.filesDir, "probes/$nonce")
                check(!directory.exists()) { "Refusing to reuse probe state" }
                report.put("status", "running-${index + 1}-of-2").put("activeNonce", nonce)
                publish(context, report)
                onMain {
                    checkNotCancelled()
                    check(executionId == report.getString("executionId")) { "Outdated diagnostic command" }
                    serviceRequested.set(true)
                    context.startService(Intent(context, NodeProbeService::class.java)
                        .putExtra(NodeProbeService.EXTRA_NONCE, nonce))
                }
                val exitFile = File(directory, "exit.json")
                val startedFile = File(directory, "started.json")
                waitFor(150_000) {
                    if (exitFile.exists()) true else {
                        if (startedFile.exists() && NodeProbeService.processGone(readJson(startedFile).getInt("pid"))) {
                            error("Node process exited without native completion; inspect the partial report/logcat")
                        }
                        false
                    }
                }
                val exit = readJson(exitFile)
                val nodeFile = File(directory, "report.json")
                val entry = JSONObject().put("exit", exit)
                if (nodeFile.isFile) entry.put("report", readJson(nodeFile))
                runs.put(entry)
                publish(context, report)
                check(nodeFile.isFile) { "Native runtime did not publish a JS report" }
                val pid = ProbeReportValidator.verifyRun(entry.getJSONObject("report"), exit, nonce,
                    runtime.getString("nodeVersion"), Process.myPid(), pids)
                waitFor(10_000) { NodeProbeService.processGone(pid) }
                pids.add(pid)
                serviceRequested.set(false)
                activeNonce = null
            }
            checkNotCancelled()
            onMain {
                checkNotCancelled()
                check(executionId == report.getString("executionId")) { "Outdated diagnostic" }
            }
            report.put("uiResponsiveAfterRuns", true)
                .put("completed", true).put("passed", true).put("status", "passed")
        } catch (error: Throwable) {
            report.put("passed", false).put("status", if (cancelled.get()) "cancelled" else "failed")
                .put("error", error.stackTraceToString())
            // Prevent a delayed UI command from starting Node after a failure.
            cancelled.set(true)
            activeNonce?.let { nonce ->
                try {
                    val directory = File(context.filesDir, "probes/$nonce")
                    val partial = File(directory, "report.json")
                    if (partial.isFile) report.put("partialReport", readJson(partial))
                    if (serviceRequested.get()) {
                        sendCancel(context, nonce)
                        val deadline = SystemClock.elapsedRealtime() + 5_000
                        var gone = false
                        while (!gone && SystemClock.elapsedRealtime() < deadline) {
                            val startedFile = File(directory, "started.json")
                            gone = startedFile.isFile && NodeProbeService.processGone(readJson(startedFile).getInt("pid"))
                            if (!gone) Thread.sleep(100)
                        }
                        requiresRestart = requiresRestart || !gone
                    }
                } catch (cleanup: Exception) {
                    requiresRestart = requiresRestart || serviceRequested.get()
                    report.put("cleanupError", cleanup.javaClass.simpleName)
                }
            }
            report.put("requiresRestart", requiresRestart)
        } finally {
            report.remove("activeNonce")
            report.put("finishedAt", Instant.now().toString())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            try { publish(context, report) }
            catch (error: Exception) {
                report.put("completed", false).put("passed", false).put("status", "failed")
                    .put("error", "Cannot persist diagnostic report: ${error.javaClass.simpleName}")
                latestJson = report.toString(2)
            } finally { activeNonce = null; active.set(false) }
        }
    }

    private fun checkNotCancelled() { check(!cancelled.get()) { "Diagnostic cancelled by user" } }
    private fun waitFor(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            checkNotCancelled()
            check(SystemClock.elapsedRealtime() < deadline) { "Diagnostic step timed out" }
            Thread.sleep(100)
        }
        checkNotCancelled()
    }

    private fun onMain(action: () -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        val command = Runnable {
            try { action() } catch (error: Throwable) { failure = error }
            finally { latch.countDown() }
        }
        mainHandler.post(command)
        if (!latch.await(5, TimeUnit.SECONDS)) {
            mainHandler.removeCallbacks(command)
            requiresRestart = true
            error("UI thread did not respond")
        }
        failure?.let { throw it }
    }

    private fun sendCancel(context: Context, nonce: String) {
        // Ask only our non-exported service to terminate itself if the nonce
        // matches. Never kill a PID from a stale file (PID reuse is possible).
        mainHandler.post {
            try {
                context.startService(Intent(context, NodeProbeService::class.java)
                    .setAction(NodeProbeService.ACTION_CANCEL)
                    .putExtra(NodeProbeService.EXTRA_NONCE, nonce))
            } catch (_: Exception) { /* OS may reject service commands; exit confirmation fails closed. */ }
        }
    }

    private fun publish(context: Context, report: JSONObject) {
        latestJson = report.toString(2)
        NodeProbeService.writeJson(reportFile(context), report)
    }
}
