// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.app.Service
import android.content.Intent
import android.os.*
import android.system.Os
import android.system.OsConstants
import dev.stshell.probe.NativeNode
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the bundled Node server in a private bound service process. */
class StService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private var reply: Messenger? = null
    private var phase = "idle"
    private var detail = ""
    private var done = 0
    private var total = 0
    @Volatile private var nativeStarted = false
    @Volatile private var serverStarted = false
    private lateinit var state: File
    private lateinit var contract: JSONObject
    private lateinit var payload: File
    private var port = 0
    private val diagnostic = JSONObject().put("schemaVersion", 1).put("scope", "android-launcher")
    private fun record(change: (JSONObject) -> Unit) { synchronized(diagnostic) { change(diagnostic) } }
    private fun saveDiagnostic(value: String) { synchronized(diagnostic) { AppFiles.writeJson(File(state, "app-diagnostic.json"), diagnostic.put("phase", value)) } }
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            CONNECT -> { reply = message.replyTo; publish(); begin() }
            STOP -> requestStop()
        }
        true
    })
    override fun onBind(intent: Intent?) = incoming.binder

    private fun begin() {
        if (phase != "idle") return
        phase = "preparing"
        publish()
        if (!processClaimed.compareAndSet(false, true)) { status("failed", "服务进程正在退出，请稍后重试"); return }
        state = AppFiles.state(this)
        Thread({
            var lock: FileLock? = null
            var lockFile: RandomAccessFile? = null
            try {
                lockFile = RandomAccessFile(File(state, "native.lock"), "rw")
                lock = lockFile.channel.tryLock() ?: error("已有Node服务持有状态目录")
                // Kernel lock release proves a previous cooperating service is
                // gone. Never delete a JS lock merely because a PID looks stale.
                for (name in listOf("bootstrap.lock", "ready.json", "node-exit.json", "node-info.json", "startup.log", "request-activity.json")) {
                    val file = File(state, name)
                    require(!Files.isSymbolicLink(file.toPath()))
                    if (file.exists()) check(file.delete())
                }
                contract = assets.open("app-contract.json").bufferedReader().use { JSONObject(it.readText()) }
                record { it.put("appIdentity", contract.getString("appIdentity"))
                    .put("payloadId", contract.getJSONObject("payload").getString("payloadId"))
                    .put("api", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
                    .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE)).put("servicePid", Process.myPid()) }
                payload = AppFiles.preparePayload(this, contract) { step, n, all ->
                    check(!cancelled.get()) { "启动已取消" }
                    status(step, "", n, all)
                }
                check(!cancelled.get())
                port = AppFiles.port(state)
                val config = File(state, "config.yaml")
                if (!config.exists()) AppFiles.writeJson(config, JSONObject().put("logging", JSONObject()
                    .put("minLogLevel", 2).put("enableAccessLog", false)))
                val launch = File(state, "app-launch.json")
                AppFiles.writeJson(launch, JSONObject().put("schemaVersion", 1).put("payloadRoot", payload.absolutePath)
                    .put("stateRoot", state.absolutePath).put("port", port)
                    .put("manifestSha256", contract.getJSONObject("payload").getString("manifestSha256")))
                for (name in listOf("entry.mjs", "request-observer.mjs")) {
                    val target = File(state, name)
                    val script = assets.open("app/$name").use { it.readBytes() }
                    require(PayloadArchive.hash(script) == contract.getJSONObject("inputs").getString("runtime/android/$name"))
                    require(!Files.isSymbolicLink(target.toPath()))
                    target.writeBytes(script)
                    Os.chmod(target.absolutePath, 0x180)
                }
                val entry = File(state, "entry.mjs")
                // Sanitize before Node sees environment-driven preload options.
                for (key in System.getenv().keys) if (key.startsWith("SILLYTAVERN_") || key in setOf("NODE_OPTIONS", "NODE_PATH", "NODE_TLS_REJECT_UNAUTHORIZED")) Os.unsetenv(key)
                Os.setenv("HOME", state.absolutePath, true)
                Os.setenv("TMPDIR", cacheDir.absolutePath, true)
                Os.setenv("NODE_ENV", "production", true)
                check(!cancelled.get())
                status("starting", "启动Node26与SillyTavern；首次会运行Webpack")
                nativeStarted = true
                main.post(monitor)
                val code = NativeNode.start(arrayOf("node", entry.absolutePath, launch.absolutePath))
                record { it.put("nativeReturnCode", code) }
                status(if (cancelled.get()) "stopped" else "failed", "Node已退出（$code）")
            } catch (error: Throwable) {
                record { it.put("error", error.javaClass.simpleName + ": " + (error.message ?: "")) }
                status(if (cancelled.get()) "stopped" else "failed", error.message ?: error.javaClass.simpleName)
            } finally {
                try { saveDiagnostic(phase) } catch (_: Exception) { }
                try { lock?.release(); lockFile?.close() } catch (_: Exception) { }
                main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
            }
        }, "SillyTavernNode").start()
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (cancelled.get()) return
            val readyFile = File(state, "ready.json")
            if (readyFile.exists() && checking.compareAndSet(false, true)) {
                serverStarted = true
                Thread({
                    try {
                        val ready = AppFiles.readJson(readyFile)
                        check(ready.getInt("pid") == Process.myPid())
                        check(ready.getString("payloadId") == contract.getJSONObject("payload").getString("payloadId"))
                        val origin = LocalOrigin(port)
                        check(ready.getString("origin") == origin.value && ready.getString("nodeVersion") == contract.getString("nodeVersion"))
                        status("checking", "检查本机认证、CSRF与tokenizer")
                        val transport = AppFiles.readJson(File(state, "transport.json"), 4096)
                        check(transport.getInt("port") == port)
                        val health = ServerHealth.check(origin, transport.getString("username"), transport.getString("password"), ready.getString("sillytavernVersion"))
                        record { it.put("health", health).put("origin", origin.value) }
                        val info = File(state, "node-info.json")
                        if (info.exists()) record { it.put("node", AppFiles.readJson(info)) }
                        saveDiagnostic("ready")
                        if (!cancelled.get()) status("ready", origin.value)
                    } catch (error: Exception) {
                        record { it.put("healthError", error.javaClass.simpleName + ": " + (error.message ?: "")) }
                        try { saveDiagnostic("failed") } catch (_: Exception) { }
                        status("failed", "启动后检查失败：${error.message}")
                        main.post { requestStop(preserveFailure = true) }
                    }
                }, "SillyTavernHealth").start()
            } else if (!checking.get()) main.postDelayed(this, 300)
        }
    }

    private fun status(value: String, message: String = "", n: Int = 0, all: Int = 0) {
        main.post { phase = value; detail = message; done = n; total = all; publish() }
    }
    private fun publish() {
        try {
            reply?.send(Message.obtain(null, STATUS).apply {
                data = Bundle().apply { putString("phase", phase); putString("detail", detail); putInt("done", done); putInt("total", total); putInt("port", port); putInt("servicePid", Process.myPid()) }
            })
        } catch (_: RemoteException) { reply = null }
    }
    private fun requestStop(preserveFailure: Boolean = false) {
        if (cancelled.getAndSet(true)) return
        if (!preserveFailure) status("stopping", "正在停止服务")
        if (nativeStarted) {
            if (serverStarted) Process.sendSignal(Process.myPid(), OsConstants.SIGTERM)
            else Process.killProcess(Process.myPid())
            main.postDelayed({ Process.killProcess(Process.myPid()) }, 10_000)
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { requestStop(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { requestStop(); super.onDestroy() }

    companion object {
        const val CONNECT = 1; const val STOP = 2; const val STATUS = 3
        private val processClaimed = AtomicBoolean(false)
    }
}
