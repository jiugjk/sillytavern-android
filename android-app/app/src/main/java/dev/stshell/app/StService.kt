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
import java.util.concurrent.atomic.AtomicReference

/** Runs the bundled Node server in a private bound service process. */
class StService : Service() {
    /**
     * Lifecycle of the native launch, as a single atomic state. "Is a stop
     * requested?" and "has the native start been claimed?" must be decided in
     * one transition: with two separate flags a stop could observe
     * nativeStarted == false, skip both the signal and the fallback kill, and
     * then the start thread would enter NativeNode.start() anyway — the UI shows
     * "stopping" while Node keeps running with nothing scheduled to terminate it.
     */
    private enum class Launch {
        /** Nothing claimed yet; preparing the payload and state directory. */
        PREPARING,
        /** The start thread has claimed the native launch and will enter Node. */
        NATIVE_CLAIMED,
        /** A stop was requested before the native launch was claimed. */
        CANCELLED_EARLY,
        /** A stop was requested after the native launch was claimed. */
        CANCELLED_NATIVE,
    }

    private val main = Handler(Looper.getMainLooper())
    private val launch = AtomicReference(Launch.PREPARING)
    private val checking = AtomicBoolean(false)
    private val progressReading = AtomicBoolean(false)
    private var lastProgress = ""
    private var reply: Messenger? = null
    internal val stateMachine = ServiceStateMachine()
    private val phase get() = stateMachine.phase
    private val detail get() = stateMachine.detail
    private val done get() = stateMachine.done
    private val total get() = stateMachine.total
    @Volatile private var serverStarted = false
    private lateinit var state: File
    private lateinit var contract: JSONObject
    private lateinit var payload: File
    private var port = 0

    /** True once a stop has been requested, whatever stage it arrived in. */
    private fun cancelled() = launch.get() == Launch.CANCELLED_EARLY || launch.get() == Launch.CANCELLED_NATIVE

    /**
     * Atomically claims the native launch. Returns false when a stop already
     * won the race, in which case Node must never be entered.
     */
    private fun claimNativeLaunch() = launch.compareAndSet(Launch.PREPARING, Launch.NATIVE_CLAIMED)

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
        val session = ++stateMachine.currentSession
        stateMachine.phase = "preparing"
        publish()
        if (!processClaimed.compareAndSet(false, true)) { status("failed", "服务进程正在退出，请稍后重试", session = session); return }
        state = AppFiles.state(this)
        Thread({
            var lock: FileLock? = null
            var lockFile: RandomAccessFile? = null
            // Reported and persisted from the same value, so a diagnostic can
            // never record a stale phase (see saveDiagnostic below).
            var terminalPhase = "failed"
            var terminalDetail = ""
            try {
                lockFile = RandomAccessFile(File(state, "native.lock"), "rw")
                lock = lockFile.channel.tryLock() ?: error("已有Node服务持有状态目录")
                // Kernel lock release proves a previous cooperating service is
                // gone. Never delete a JS lock merely because a PID looks stale.
                for (name in listOf("bootstrap.lock", "ready.json", "node-exit.json", "node-info.json", "startup.log", "request-activity.json", "install-progress.json", "installed-launch.json", "installation-info.json")) {
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
                    check(!cancelled()) { "启动已取消" }
                    status(step, "", n, all, isProgress = true, session = session)
                }
                check(!cancelled())
                port = AppFiles.port(state)
                val config = File(state, "config.yaml")
                if (!config.exists()) AppFiles.writeJson(config, JSONObject().put("logging", JSONObject()
                    .put("minLogLevel", 2).put("enableAccessLog", false)))
                val launchFile = File(state, "app-launch.json")
                AppFiles.writeJson(launchFile, JSONObject().put("schemaVersion", 1).put("payloadRoot", payload.absolutePath)
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
                // Single atomic transition: either we claim the native launch, or
                // a stop already won and Node must not be entered at all. The
                // blocking NativeNode.start() call below holds no lock.
                if (!claimNativeLaunch()) {
                    terminalPhase = "stopped"; terminalDetail = "启动已取消"
                    status(terminalPhase, terminalDetail, session = session)
                    return@Thread
                }
                status("starting", "准备本地安装；首次联网下载 ST Staging、插件和依赖", session = session)
                main.post(monitor)
                val code = NativeNode.start(arrayOf("node", entry.absolutePath, launchFile.absolutePath))
                record { it.put("nativeReturnCode", code) }
                terminalPhase = if (cancelled()) "stopped" else "failed"
                terminalDetail = "Node已退出（$code）"
                status(terminalPhase, terminalDetail, session = session)
            } catch (error: Throwable) {
                record { it.put("error", error.javaClass.simpleName + ": " + (error.message ?: "")) }
                terminalPhase = if (cancelled()) "stopped" else "failed"
                terminalDetail = error.message ?: error.javaClass.simpleName
                status(terminalPhase, terminalDetail, session = session)
            } finally {
                // Persist the terminal phase this thread decided. Reading the
                // shared `phase` here would race with the main-thread post in
                // status() and could save a stale "starting"/"ready".
                try { saveDiagnostic(terminalPhase) } catch (_: Exception) { }
                try { lock?.release(); lockFile?.close() } catch (_: Exception) { }
                main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
            }
        }, "SillyTavernNode").start()
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (cancelled()) return
            val session = stateMachine.currentSession
            val readyFile = File(state, "ready.json")
            if (readyFile.exists() && checking.compareAndSet(false, true)) {
                serverStarted = true
                Thread({
                    try {
                        val ready = AppFiles.readJson(readyFile)
                        check(ready.getInt("pid") == Process.myPid())
                        val installed = AppFiles.readJson(File(state, "installation-info.json"), 65536)
                        check(installed.getString("installerSha256") == contract.getJSONObject("payload").getString("manifestSha256"))
                        check(ready.getString("payloadId") == installed.getString("payloadId"))
                        record { it.put("installation", installed).put("payloadId", installed.getString("payloadId")) }
                        val origin = LocalOrigin(port)
                        check(ready.getString("origin") == origin.value && ready.getString("nodeVersion") == contract.getString("nodeVersion"))
                        status("checking", "检查本机认证、CSRF与tokenizer", session = session)
                        val transport = AppFiles.readJson(File(state, "transport.json"), 4096)
                        check(transport.getInt("port") == port)
                        val health = ServerHealth.check(origin, transport.getString("username"), transport.getString("password"), ready.getString("sillytavernVersion"))
                        record { it.put("health", health).put("origin", origin.value) }
                        val info = File(state, "node-info.json")
                        if (info.exists()) record { it.put("node", AppFiles.readJson(info)) }
                        saveDiagnostic("ready")
                        if (!cancelled()) status("ready", origin.value, session = session)
                    } catch (error: Exception) {
                        record { it.put("healthError", error.javaClass.simpleName + ": " + (error.message ?: "")) }
                        try { saveDiagnostic("failed") } catch (_: Exception) { }
                        status("failed", "启动后检查失败：${error.message}", session = session)
                        main.post { requestStop(preserveFailure = true) }
                    }
                }, "SillyTavernHealth").start()
            } else if (!checking.get()) {
                if (progressReading.compareAndSet(false, true)) Thread({
                    try {
                        val file = File(state, "install-progress.json")
                        if (file.exists()) {
                            val value = AppFiles.readJson(file, 65536)
                            val text = value.toString()
                            if (text != lastProgress && !cancelled() && !checking.get()) {
                                lastProgress = text
                                status(value.getString("phase"), value.getString("detail"), value.optInt("done"), value.optInt("total"), isProgress = true, session = session)
                            }
                        }
                    } catch (_: Exception) { /* Atomic writer may not have published yet. */ }
                    finally { progressReading.set(false) }
                }, "InstallProgress").start()
                main.postDelayed(this, 500)
            }
        }
    }

    internal fun applyStatus(
        session: Long,
        value: String,
        message: String = "",
        n: Int = 0,
        all: Int = 0,
        isProgress: Boolean = false
    ): Boolean {
        val changed = stateMachine.transition(session, value, message, n, all, isProgress, cancelled())
        if (changed) publish()
        return changed
    }

    private fun status(
        value: String,
        message: String = "",
        n: Int = 0,
        all: Int = 0,
        isProgress: Boolean = false,
        session: Long = stateMachine.currentSession
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyStatus(session, value, message, n, all, isProgress)
        } else {
            main.post { applyStatus(session, value, message, n, all, isProgress) }
        }
    }
    private fun publish() {
        try {
            reply?.send(Message.obtain(null, STATUS).apply {
                data = Bundle().apply { putString("phase", phase); putString("detail", detail); putInt("done", done); putInt("total", total); putInt("port", port); putInt("servicePid", Process.myPid()) }
            })
        } catch (_: RemoteException) { reply = null }
    }

    private fun requestStop(preserveFailure: Boolean = false) {
        // One atomic transition decides, from the same state the start thread
        // uses, whether Node was already claimed. Whichever thread wins, exactly
        // one of these outcomes happens:
        //  - stop first  -> CANCELLED_EARLY, the start thread refuses to enter Node
        //  - start first -> CANCELLED_NATIVE, we signal and schedule the fallback kill
        var claimedNative = false
        while (true) {
            val current = launch.get()
            if (current == Launch.CANCELLED_EARLY || current == Launch.CANCELLED_NATIVE) return  // already stopping
            val next = if (current == Launch.NATIVE_CLAIMED) Launch.CANCELLED_NATIVE else Launch.CANCELLED_EARLY
            if (launch.compareAndSet(current, next)) { claimedNative = current == Launch.NATIVE_CLAIMED; break }
        }
        if (!preserveFailure) status("stopping", "正在停止服务", session = stateMachine.currentSession)
        if (claimedNative) {
            if (serverStarted) Process.sendSignal(Process.myPid(), OsConstants.SIGTERM)
            else Process.killProcess(Process.myPid())
        }
        // A stop during preparation must be enforced too: the prepare loop may be
        // inside a long verify/unpack step and only notices `cancelled()` between
        // progress callbacks. Always schedule the bounded fallback termination.
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 10_000)
    }
    override fun onTaskRemoved(rootIntent: Intent?) { requestStop(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { requestStop(); super.onDestroy() }

    companion object {
        const val CONNECT = 1; const val STOP = 2; const val STATUS = 3
        private val processClaimed = AtomicBoolean(false)
    }
}
