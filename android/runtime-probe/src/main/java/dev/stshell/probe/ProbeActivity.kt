// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.probe

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.system.Os
import android.system.OsConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private lateinit var summary: TextView
    private lateinit var runButton: Button
    private lateinit var cancelButton: Button
    private lateinit var copyButton: Button
    private lateinit var exportButton: Button
    private var currentJson: String? = null
    private var pendingExport: String? = null
    private var expectedProbeIdentity: String? = null
    private var currentPageSize = 0L
    private val poll = object : Runnable {
        override fun run() {
            renderReport()
            if (ProbeCoordinator.running) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("pendingExport")
        expectedProbeIdentity = assets.open("probe-manifest.json").bufferedReader().use {
            JSONObject(it.readText()).getString("probeIdentity")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(24, 48, 24, 24)
        }
        layout.addView(TextView(this).apply { setText(R.string.probe_title); textSize = 20f })
        layout.addView(TextView(this).apply { setText(R.string.probe_notice) })
        layout.addView(TextView(this).apply {
            text = try {
                currentPageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
                getString(R.string.device_info, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                    Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown", currentPageSize / 1024)
            } catch (error: Exception) { getString(R.string.device_unknown, error.javaClass.simpleName) }
        })
        runButton = Button(this).apply {
            setText(R.string.run_self_test)
            setOnClickListener { safely { startSelfTest() } }
        }
        cancelButton = Button(this).apply {
            setText(R.string.cancel_diagnostic)
            setOnClickListener { ProbeCoordinator.cancel(this@ProbeActivity); toast(R.string.cancel_requested) }
        }
        layout.addView(runButton)
        layout.addView(cancelButton)
        val reportActions = LinearLayout(this)
        copyButton = Button(this).apply {
            setText(R.string.copy_report)
            setOnClickListener { safely { copyReport() } }
        }
        exportButton = Button(this).apply {
            setText(R.string.export_report)
            setOnClickListener { safely { exportReport() } }
        }
        reportActions.addView(copyButton, LinearLayout.LayoutParams(0, -2, 1f))
        reportActions.addView(exportButton, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(reportActions)
        layout.addView(Button(this).apply {
            setText(R.string.licenses_button)
            setOnClickListener {
                safely {
                    val license = TextView(this@ProbeActivity).apply {
                        setPadding(24, 24, 24, 24)
                        setTextIsSelectable(true)
                        text = getString(R.string.licenses_notice) +
                            assets.open("licenses/AGPL-3.0.txt").bufferedReader().use { it.readText() }
                    }
                    AlertDialog.Builder(this@ProbeActivity).setTitle(R.string.licenses_title)
                        .setView(ScrollView(this@ProbeActivity).apply { addView(license) })
                        .setPositiveButton(R.string.close, null).show()
                }
            }
        })
        summary = TextView(this)
        layout.addView(summary)
        layout.addView(TextView(this).apply { setText(R.string.coverage_notice) })
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }
        layout.addView(output, LinearLayout.LayoutParams(-1, -2))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(layout)
        })
        handler.post(poll)
    }

    fun startSelfTest(source: String = "app-self-test"): String {
        val id = ProbeCoordinator.start(this, source)
        currentJson = null
        copyButton.isEnabled = false
        exportButton.isEnabled = false
        runButton.isEnabled = false
        handler.removeCallbacks(poll)
        handler.post(poll)
        return id
    }

    private fun renderReport() {
        val active = ProbeCoordinator.running
        runButton.isEnabled = !active && !ProbeCoordinator.requiresRestart
        cancelButton.visibility = if (active) View.VISIBLE else View.GONE
        copyButton.isEnabled = false
        exportButton.isEnabled = false
        currentJson = null
        try {
            val report = ProbeCoordinator.currentReport(this)
            if (report == null) {
                summary.setText(R.string.ready_to_start)
                output.text = ""
                return
            }
            val status = report.optString("status")
            val stale = !active && report.optJSONObject("probeManifest")?.optString("probeIdentity") != expectedProbeIdentity
            val device = report.optJSONObject("device")
            val deviceChanged = !active && device != null &&
                (device.optInt("api") != Build.VERSION.SDK_INT || device.optLong("pageSize") != currentPageSize ||
                    device.optString("fingerprint") != Build.FINGERPRINT)
            val interrupted = !active && (status.startsWith("running-") || status == "collecting-device-info")
            summary.text = when {
                ProbeCoordinator.requiresRestart -> getString(R.string.restart_required)
                interrupted -> getString(R.string.interrupted_status)
                deviceChanged -> getString(R.string.device_changed)
                stale && status !in setOf("failed", "cancelled") -> getString(R.string.stale_status)
                status == "passed" && active -> getString(R.string.finishing_status)
                status == "passed" -> getString(R.string.passed_status)
                status == "failed" -> getString(R.string.failed_status)
                status == "cancelled" -> getString(R.string.cancelled_status)
                status.startsWith("running-") -> getString(R.string.running_status, if (status == "running-1-of-2") 1 else 2)
                else -> getString(R.string.collecting)
            }
            currentJson = report.toString(2)
            output.text = currentJson
            val terminal = !active && (interrupted || status in setOf("passed", "failed", "cancelled") || report.optBoolean("completed"))
            copyButton.isEnabled = terminal
            exportButton.isEnabled = terminal
        } catch (error: Exception) {
            summary.text = getString(R.string.read_error, error.javaClass.simpleName)
            output.text = ""
        }
    }

    private fun copyReport() {
        check(!ProbeCoordinator.running) { "Diagnostic is still running" }
        val text = currentJson ?: error(getString(R.string.no_report))
        val clip = ClipData.newPlainText(getString(R.string.clipboard_label), text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toast(R.string.copied)
    }

    // Activity is deliberately platform-only in this diagnostic app. The legacy
    // result callback remains supported on API34+ and avoids a new UI framework.
    private fun exportReport() {
        check(!ProbeCoordinator.running) { "Diagnostic is still running" }
        val text = currentJson ?: error(getString(R.string.no_report))
        val report = JSONObject(text)
        val pages = report.optJSONObject("device")?.optLong("pageSize", 0) ?: 0
        val id = report.optString("executionId", "report").take(8)
        pendingExport = text
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "device-probe-${pages}-$id.json")
        }, EXPORT_REPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REPORT) return
        val text = pendingExport
        pendingExport = null
        if (resultCode != RESULT_OK || text == null) return
        safely {
            val uri = data?.data ?: error("No destination URI")
            check(uri.scheme == "content") { "A Storage Access Framework document is required" }
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            toast(R.string.saved)
        }
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            Toast.makeText(this, getString(R.string.action_error, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            handler.post(poll)
        }
    }
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExport", pendingExport)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        handler.removeCallbacks(poll)
        // Coordinator holds only application context. Rotation does not cancel
        // the test, leak this Activity, or start a second copy of the runtime.
        super.onDestroy()
    }

    companion object { private const val EXPORT_REPORT = 301 }
}
