// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.WebView
import android.widget.*
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var launcher: LauncherLayout
    private var panelStatus: TextView? = null
    private var panelProgress: ProgressBar? = null
    private var latestState = JSONObject().put("phase", "idle")
    private var controlsOpen = false
    private var pendingProtection = false
    private val closePanelOnBack = OnBackInvokedCallback { closeControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherBranding.applyTaskIcon(this)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.setSystemBarsAppearance(0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        pendingProtection = savedInstanceState?.getBoolean("pendingProtection") ?: false
        val preferences = getSharedPreferences("launcher-ui", MODE_PRIVATE)
        val initialAnchor = try {
            LauncherGeometry.Anchor(
                if (savedInstanceState?.containsKey("dockRight") == true) savedInstanceState.getBoolean("dockRight") else preferences.getBoolean("dockRight", true),
                if (savedInstanceState?.containsKey("dockFraction") == true) savedInstanceState.getFloat("dockFraction") else preferences.getFloat("dockFraction", 0.65f)
            )
        } catch (_: ClassCastException) { LauncherGeometry.Anchor() }
        launcher = LauncherLayout(this, initialAnchor, { anchor ->
            preferences.edit().putBoolean("dockRight", anchor.right).putFloat("dockFraction", anchor.fraction).apply()
        }, { openControls() }, { closeControls() })
        setContentView(launcher)
        SessionController.attach(this)
        val restorePanel = savedInstanceState?.getBoolean("controlsOpen") == true
        if (restorePanel || (savedInstanceState == null && !launcher.hasBrowser() && latestState.optString("phase") == "idle")) {
            launcher.post { if (!isFinishing && !isDestroyed) openControls() }
        }
    }
    fun attachBrowser(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        launcher.browserHost.removeAllViews()
        launcher.browserHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        launcher.updateState(latestState.optString("phase"), latestState.optString("detail"), latestState.optInt("done"), latestState.optInt("total"))
    }
    fun openControls() {
        if (controlsOpen || isFinishing || isDestroyed) return
        window.insetsController?.hide(WindowInsets.Type.ime())
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val heading = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply { setText(R.string.launcher_panel_title) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(Button(this).apply { setText(R.string.launcher_panel_close); setOnClickListener { closeControls() } })
        column.addView(heading)
        panelStatus = TextView(this).apply { id = R.id.launcher_panel_status }
        panelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { id = R.id.launcher_panel_progress }
        column.addView(panelStatus); column.addView(panelProgress)
        fun action(label: String, callback: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    closeControls()
                    try { callback() } catch (error: Exception) { notice(error.message ?: "操作失败") }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        action("启动") { SessionController.start() }
        action("重启") { confirmServiceAction("重启服务") { SessionController.stop(restart = true) } }
        action("停止") { confirmServiceAction("停止服务") { SessionController.stop() } }
        action("后台保护") { toggleProtection() }
        action("电池设置") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        action("诊断") { copyDiagnostic() }
        action("启动日志") { showStartupLog() }
        action("说明") { showAbout() }
        val background = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.colorBackground, it, true) }.data
        val sheet = ScrollView(this).apply {
            id = R.id.launcher_controls_sheet
            setBackgroundColor(background); addView(column)
            accessibilityPaneTitle = getString(R.string.launcher_panel_title)
            isFocusableInTouchMode = true
        }
        controlsOpen = true
        launcher.browserHost.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        launcher.showPanel(sheet) { closeControls() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, closePanelOnBack)
        updatePanelState()
        sheet.requestFocus()
    }
    fun closeControls() {
        if (!controlsOpen) return
        controlsOpen = false
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closePanelOnBack)
        launcher.hidePanel()
        launcher.browserHost.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        panelStatus = null; panelProgress = null
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    fun showState(value: JSONObject) {
        latestState = JSONObject(value.toString())
        launcher.updateState(value.optString("phase", "idle"), value.optString("detail"), value.optInt("done"), value.optInt("total"))
        updatePanelState()
    }
    private fun updatePanelState() {
        val value = latestState
        val phase = value.optString("phase", "idle")
        val all = value.optInt("total"); val done = value.optInt("done")
        val guard = value.optJSONObject("background")
        val protection = if (guard?.optBoolean("enabled") == true) "保护已开 / CPU唤醒${if (guard.optBoolean("wakeHeld")) "中" else "已释放"}" else "保护未开启"
        val observer = if (value.optJSONObject("observer")?.optBoolean("observerReady") == true) "观察器就绪" else "观察器未就绪"
        panelStatus?.text = "$phase · ${value.optString("detail")} ${if (all > 0) "$done/$all" else ""}\n$protection · $observer"
        panelProgress?.apply {
            visibility = if (phase in setOf("connecting", "preparing", "copying", "unpacking", "verifying", "starting", "checking")) View.VISIBLE else View.GONE
            isIndeterminate = all <= 0
            if (all > 0) { max = all; progress = done }
        }
    }
    private fun toggleProtection() {
        if (BackgroundProtectionService.running) {
            AlertDialog.Builder(this).setTitle("关闭后台保护？")
                .setMessage("关闭后台保护并保留当前服务。")
                .setPositiveButton("关闭") { _, _ -> SessionController.disableProtection() }.setNegativeButton("取消", null).show()
            return
        }
        if (!SessionController.canProtect()) { notice("请等待ST页面及活动观察器准备好；必要时更新Android System WebView"); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingProtection = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
            return
        }
        if (!BackgroundProtectionService.notificationsAllowed(this)) {
            notice("通知或保护通知渠道已被禁用，请先在系统设置开启")
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            return
        }
        AlertDialog.Builder(this).setTitle("开启本机会话后台保护？")
            .setMessage("开启后显示常驻通知，在生成和保存时保持CPU运行，直到你关闭保护或停止服务。")
            .setPositiveButton("开启") { _, _ ->
                try { SessionController.enableProtection() } catch (error: Exception) { notice(error.message ?: "无法开启") }
            }.setNegativeButton("取消", null).show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION && pendingProtection) {
            pendingProtection = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleProtection()
            else notice("未获得通知权限，后台保护保持关闭；仍可前台使用")
        }
    }
    private fun confirmServiceAction(name: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(name)
            .setMessage("确认$name？")
            .setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).show()
    }
    fun chooseFiles(multiple: Boolean) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }, OPEN_FILE)
        } catch (error: Exception) { SessionController.fileCallback?.onReceiveValue(null); SessionController.fileCallback = null; notice("无法打开文件选择器") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != OPEN_FILE) return
        val callback = SessionController.fileCallback; SessionController.fileCallback = null
        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK) {
            data?.clipData?.let { clips -> if (clips.itemCount <= 256) repeat(clips.itemCount) { uris.add(clips.getItemAt(it).uri) } }
            if (uris.isEmpty()) data?.data?.let(uris::add)
        }
        callback?.onReceiveValue(uris.filter { it.scheme == "content" }.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    private fun copyDiagnostic() {
        val value = SessionController.diagnostic().toString(2)
        val clip = ClipData.newPlainText("ST Android diagnostics", value)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        notice("已复制诊断状态（不含密钥、Cookie、聊天或启动日志）")
    }
    private fun showStartupLog() {
        val file = java.io.File(AppFiles.state(this), "startup.log")
        val log = if (file.isFile && file.length() <= 65536) file.readText() else "暂无启动日志"
        val view = TextView(this).apply {
            setPadding(20, 20, 20, 20); setTextIsSelectable(true)
            text = log
        }
        AlertDialog.Builder(this).setTitle("私有启动日志")
            .setView(ScrollView(this).apply { addView(view) }).setPositiveButton("关闭", null).show()
    }
    private fun showAbout() {
        val text = TextView(this).apply {
            setTextIsSelectable(true); setPadding(20, 20, 20, 20)
            text = "SillyTavern Android 启动器\n" +
                "Node26 服务端与本机 WebView，配置和用户数据存储于应用目录。\n" +
                "后台保护显示常驻通知，在生成、保存及收尾时保持CPU运行。\n" +
                "源码与构建说明见工程README；许可证保存在assets/licenses及ST源码中。\n\n" +
                assets.open("licenses/AGPL-3.0.txt").bufferedReader().use { it.readText() }
        }
        AlertDialog.Builder(this).setTitle("关于 / 许可证")
            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("关闭", null).show()
    }
    fun notice(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onStart() { super.onStart(); SessionController.activityVisibility(true) }
    override fun onStop() { SessionController.activityVisibility(false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingProtection", pendingProtection)
        outState.putBoolean("controlsOpen", controlsOpen)
        val anchor = launcher.anchor()
        outState.putBoolean("dockRight", anchor.right); outState.putFloat("dockFraction", anchor.fraction)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { closeControls(); SessionController.detach(this); super.onDestroy() }
    companion object { private const val OPEN_FILE = 101; private const val NOTIFICATION_PERMISSION = 102 }
}
