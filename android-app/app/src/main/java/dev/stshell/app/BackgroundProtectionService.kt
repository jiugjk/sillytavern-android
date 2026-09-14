// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Explicitly user-enabled interactive local session, not a data-sync batch or model engine. */
class BackgroundProtectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wake: PowerManager.WakeLock
    private var active = false
    private var acquisitions = 0
    private var releases = 0
    private var lastNotification = ""
    private val largeIcon by lazy { LauncherBranding.notificationLargeIcon(this) }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        ensureChannel(this)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dev.stshell.app:remote-generation")
        wake.setReferenceCounted(false)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DISABLE -> { stopSelf(); return START_NOT_STICKY }
            CANCEL_GENERATION -> {
                if (active) SessionController.cancelGenerationFromNotification() else stopSelf()
                return START_NOT_STICKY
            }
            ENABLE -> {
                if (active) return START_NOT_STICKY
                if (!notificationsAllowed(this) || !SessionController.canProtect()) {
                    publishFailure("notification_or_session_unavailable"); stopSelf(); return START_NOT_STICKY
                }
                try {
                    startForeground(NOTIFICATION_ID, notification("本机会话保护已开启，等待生成"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    active = true; running = true
                    main.post(tick)
                } catch (error: Exception) {
                    publishFailure(error.javaClass.simpleName); stopSelf()
                }
            }
            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                val snapshot = SessionController.backgroundSnapshot(true)
                if (!snapshot.getBoolean("serverReady")) { stopSelf(); return }
                val needed = snapshot.getBoolean("cpuNeeded")
                if (needed && !wake.isHeld) {
                    // No generation timeout. Release follows observed work, stop,
                    // renderer loss, disable or service destruction.
                    wake.acquire(); acquisitions++
                } else if (!needed && wake.isHeld) { wake.release(); releases++ }
                val power = getSystemService(PowerManager::class.java)
                snapshot.put("enabled", true).put("foreground", true).put("wakeHeld", wake.isHeld)
                    .put("wakeAcquisitions", acquisitions).put("wakeReleases", releases)
                    .put("deviceIdle", power.isDeviceIdleMode)
                    .put("batteryOptimizationExempt", power.isIgnoringBatteryOptimizations(packageName))
                    .put("keyguardLocked", getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                latest = snapshot.toString()
                val front = snapshot.getJSONObject("frontend")
                val text = when {
                    !front.optBoolean("documentPresent") -> "页面已中断，请返回应用"
                    needed -> "正在生成或保存，保持CPU运行；可停止生成"
                    else -> "本机会话保护已开启，空闲时不持有CPU唤醒锁"
                }
                if (text != lastNotification) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                    lastNotification = text
                }
                SessionController.protectionChanged()
            } catch (error: Exception) { publishFailure(error.javaClass.simpleName); stopSelf(); return }
            main.postDelayed(this, 1000)
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 80, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(code: Int, name: String) = PendingIntent.getService(this, code, Intent(this, BackgroundProtectionService::class.java)
            .setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return LauncherBranding.notificationBuilder(this, CHANNEL, largeIcon)
            .setContentTitle("ST 本机会话保护").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止生成", action(81, CANCEL_GENERATION)).build())
            .addAction(Notification.Action.Builder(null, "关闭保护", action(82, DISABLE)).build()).build()
    }
    private fun publishFailure(reason: String) {
        latest = JSONObject().put("enabled", false).put("foreground", false).put("wakeHeld", false).put("error", reason).toString()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        SessionController.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        main.removeCallbacks(tick)
        if (::wake.isInitialized && wake.isHeld) { wake.release(); releases++ }
        active = false; running = false
        if (instance?.get() === this) instance = null
        val snapshot = try { JSONObject(latest) } catch (_: Exception) { JSONObject() }
        latest = snapshot.put("enabled", false).put("foreground", false).put("wakeHeld", false)
            .put("wakeReleases", releases).toString()
        stopForeground(STOP_FOREGROUND_REMOVE)
        SessionController.protectionChanged()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "st-session-protection"
        private const val NOTIFICATION_ID = 10020
        private const val ENABLE = "dev.stshell.app.ENABLE_PROTECTION"
        private const val DISABLE = "dev.stshell.app.DISABLE_PROTECTION"
        private const val CANCEL_GENERATION = "dev.stshell.app.CANCEL_GENERATION"
        private var instance: WeakReference<BackgroundProtectionService>? = null
        fun refresh() {
            instance?.get()?.let { service ->
                service.main.removeCallbacks(service.tick)
                service.main.post(service.tick)
            }
        }
        @Volatile var running = false
            private set
        @Volatile private var latest = "{\"enabled\":false,\"foreground\":false,\"wakeHeld\":false}"
        fun diagnostic() = JSONObject(latest)
        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "ST本机会话保护", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "用户主动开启的本地WebView与Node会话；停止生成/关闭保护始终可见"
                })
        }
        fun notificationsAllowed(context: Context): Boolean {
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        }
        fun enable(context: Context) { context.startForegroundService(Intent(context, BackgroundProtectionService::class.java).setAction(ENABLE)) }
        fun disable(context: Context) { context.stopService(Intent(context, BackgroundProtectionService::class.java)) }
    }
}
