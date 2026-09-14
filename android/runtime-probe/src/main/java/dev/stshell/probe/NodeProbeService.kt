// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.probe

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class NodeProbeService : Service() {
    private val started = AtomicBoolean(false)
    private var activeNonce: String? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nonce = intent?.getStringExtra(EXTRA_NONCE) ?: return START_NOT_STICKY
        if (!NONCE.matches(nonce)) return START_NOT_STICKY
        if (intent.action == ACTION_CANCEL) {
            if (activeNonce == nonce) {
                // Diagnostic cancellation only. No invented "native exitCode":
                // the coordinator records cancellation/partial evidence instead.
                stopSelf(startId)
                Process.killProcess(Process.myPid())
            } else if (activeNonce == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY
        activeNonce = nonce
        Thread({
            val directory = File(filesDir, "probes/$nonce")
            var exitCode = -1
            var error: String? = null
            try {
                check(directory.mkdirs() || directory.isDirectory)
                writeJson(File(directory, "started.json"), JSONObject()
                    .put("nonce", nonce).put("pid", Process.myPid()))
                copyAssets("probe", File(directory, "source"))
                exitCode = NativeNode.start(arrayOf(
                    "node", File(directory, "source/probe.mjs").absolutePath,
                    File(directory, "report.json").absolutePath, nonce, "android"
                ))
            } catch (failure: Throwable) {
                error = failure.stackTraceToString()
            } finally {
                try {
                    writeJson(File(directory, "exit.json"), JSONObject()
                        .put("nonce", nonce).put("pid", Process.myPid())
                        .put("exitCode", exitCode).put("error", error ?: JSONObject.NULL))
                } finally {
                    // Never re-enter Node in this process. This is :node, not UI.
                    stopSelf(startId)
                    Process.killProcess(Process.myPid())
                }
            }
        }, "Node26Probe").start()
        return START_NOT_STICKY
    }

    private fun copyAssets(source: String, destination: File) {
        val names = assets.list(source) ?: error("Missing assets: $source")
        if (names.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(source).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            check(destination.mkdirs() || destination.isDirectory)
            for (name in names) copyAssets("$source/$name", File(destination, name))
        }
    }

    companion object {
        const val EXTRA_NONCE = "probeNonce"
        const val ACTION_CANCEL = "dev.stshell.probe.CANCEL_DIAGNOSTIC"
        val NONCE = Regex("[a-f0-9]{32}")
        fun processGone(pid: Int): Boolean {
            if (pid <= 0 || pid == Process.myPid()) return false
            return try { Os.kill(pid, 0); false }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.ESRCH) true else throw error
            }
        }
        fun writeJson(file: File, value: JSONObject) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temp).use { it.write(value.toString(2).toByteArray()); it.fd.sync() }
            check(temp.renameTo(file)) { "Cannot publish ${file.name}" }
        }
    }
}
