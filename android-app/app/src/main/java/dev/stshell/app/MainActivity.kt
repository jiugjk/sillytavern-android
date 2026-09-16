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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.WebView
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var launcher: LauncherLayout
    private var panelStatus: TextView? = null
    private var panelProgress: ProgressBar? = null
    private var latestState = LauncherUiState()
    private var controlsOpen = false
    private var pendingProtection = false
    private val closePanelOnBack = OnBackInvokedCallback { closeControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherBranding.applyTaskIcon(this)
        // WindowCompat.enableEdgeToEdge() installs the decor before touching the
        // window attributes, which is what keeps the insets controller reachable:
        // PhoneWindow.getInsetsController() is a bare mDecor.getWindowInsetsController()
        // and throws inside the getter while mDecor is still null. It also applies
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS and transparent system bars for us.
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
        if (restorePanel || (savedInstanceState == null && !launcher.hasBrowser() && latestState.displayPhase == "idle")) {
            launcher.post { if (!isFinishing && !isDestroyed) openControls() }
        }
    }
    fun attachBrowser(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        launcher.browserHost.removeAllViews()
        launcher.browserHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        launcher.updateState(latestState.displayPhase, latestState.displayDetail, latestState.progress.done, latestState.progress.total)
    }
    fun openControls() {
        if (controlsOpen || isFinishing || isDestroyed) return
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val heading = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply { setText(R.string.launcher_panel_title) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(Button(this).apply { setText(R.string.launcher_panel_close); setOnClickListener { closeControls() } })
        column.addView(heading)
        panelStatus = TextView(this).apply { id = R.id.launcher_panel_status }
        panelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { id = R.id.launcher_panel_progress }
        column.addView(panelStatus); column.addView(panelProgress)
        fun action(labelRes: Int, callback: () -> Unit) {
            column.addView(Button(this).apply {
                setText(labelRes)
                setOnClickListener {
                    closeControls()
                    try { callback() } catch (error: Exception) { notice(error.message ?: getString(R.string.action_failed)) }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        action(R.string.action_start) { SessionController.start() }
        // Page-only recovery: rebuild the WebView while Node keeps running.
        // Offered only when the service is healthy and just the page is broken.
        if (latestState.canReopenBrowser) {
            action(R.string.action_reopen_page) { SessionController.reopenBrowser() }
        }
        action(R.string.action_restart) { confirmServiceAction(getString(R.string.confirm_restart_title)) { SessionController.stop(restart = true) } }
        action(R.string.action_stop) { confirmServiceAction(getString(R.string.confirm_stop_title)) { SessionController.stop() } }
        action(R.string.action_redownload) {
            AlertDialog.Builder(this).setTitle(R.string.redownload_title)
                .setMessage(R.string.redownload_message)
                .setPositiveButton(R.string.confirm_positive) { _, _ -> SessionController.redownloadSources() }
                .setNegativeButton(R.string.confirm_cancel, null).show()
        }
        action(R.string.action_background_protection) { toggleProtection() }
        action(R.string.action_battery_settings) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        action(R.string.action_diagnostics) { copyDiagnostic() }
        action(R.string.action_startup_log) { showStartupLog() }
        action(R.string.action_about) { showAbout() }
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
    fun showState(state: LauncherUiState) {
        latestState = state
        launcher.updateState(state.displayPhase, state.displayDetail, state.progress.done, state.progress.total)
        updatePanelState()
    }
    private fun updatePanelState() {
        val state = latestState
        val phase = state.displayPhase
        val all = state.progress.total; val done = state.progress.done
        val protection = if (state.protection.enabled) {
            getString(R.string.status_protection_on,
                getString(if (state.protection.wakeHeld) R.string.status_wake_held else R.string.status_wake_released))
        } else getString(R.string.status_protection_off)
        val observer = getString(if (state.observer.observerReady)
            R.string.status_observer_ready else R.string.status_observer_waiting)
        panelStatus?.text = getString(R.string.status_line, phase, state.displayDetail,
            if (all > 0) "$done/$all" else "", protection, observer)
        panelProgress?.apply {
            // ServerPhase owns "is startup in progress", not a string list here.
            visibility = if (state.session.serverPhase.busy) View.VISIBLE else View.GONE
            isIndeterminate = all <= 0
            if (all > 0) { max = all; progress = done }
        }
    }
    private fun toggleProtection() {
        if (BackgroundProtectionService.running) {
            AlertDialog.Builder(this).setTitle(R.string.protection_disable_title)
                .setMessage(R.string.protection_disable_message)
                .setPositiveButton(R.string.protection_disable_confirm) { _, _ -> SessionController.disableProtection() }
                .setNegativeButton(R.string.confirm_cancel, null).show()
            return
        }
        if (!SessionController.canProtect()) { notice(getString(R.string.protection_not_ready)); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingProtection = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
            return
        }
        if (!BackgroundProtectionService.notificationsAllowed(this)) {
            notice(getString(R.string.protection_notifications_blocked))
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.protection_enable_title)
            .setMessage(R.string.protection_enable_message)
            .setPositiveButton(R.string.protection_enable_confirm) { _, _ ->
                try { SessionController.enableProtection() } catch (error: Exception) { notice(error.message ?: getString(R.string.protection_enable_failed)) }
            }.setNegativeButton(R.string.confirm_cancel, null).show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION && pendingProtection) {
            pendingProtection = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleProtection()
            else notice(getString(R.string.protection_permission_denied))
        }
    }
    private fun confirmServiceAction(name: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(name)
            .setMessage(getString(R.string.confirm_message, name))
            .setPositiveButton(R.string.confirm_positive) { _, _ -> action() }
            .setNegativeButton(R.string.confirm_cancel, null).show()
    }
    fun chooseFiles(multiple: Boolean) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }, OPEN_FILE)
        } catch (error: Exception) { SessionController.fileCallback?.onReceiveValue(null); SessionController.fileCallback = null; notice(getString(R.string.file_chooser_unavailable)) }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != OPEN_FILE) return
        val callback = SessionController.fileCallback; SessionController.fileCallback = null
        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clips = data.clipData
            if (clips != null) {
                if (clips.itemCount <= 256) {
                    for (i in 0 until clips.itemCount) {
                        clips.getItemAt(i)?.uri?.takeIf { it.scheme == "content" }?.let(uris::add)
                    }
                }
            } else {
                data.data?.takeIf { it.scheme == "content" }?.let(uris::add)
            }
        }
        callback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    private fun copyDiagnostic() {
        val value = SessionController.diagnostic().toString(2)
        val clip = ClipData.newPlainText(getString(R.string.diagnostics_clip_label), value)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        notice(getString(R.string.diagnostics_copied))
    }
    private fun showStartupLog() {
        val file = java.io.File(AppFiles.state(this), "startup.log")
        val log = if (file.isFile && file.length() <= 65536) file.readText() else getString(R.string.startup_log_empty)
        val view = TextView(this).apply {
            setPadding(20, 20, 20, 20); setTextIsSelectable(true)
            text = log
        }
        AlertDialog.Builder(this).setTitle(R.string.startup_log_title)
            .setView(ScrollView(this).apply { addView(view) }).setPositiveButton(R.string.dialog_close, null).show()
    }
    private fun showAbout() {
        val text = TextView(this).apply {
            setTextIsSelectable(true); setPadding(20, 20, 20, 20)
            text = getString(R.string.about_body) +
                assets.open("licenses/AGPL-3.0.txt").bufferedReader().use { it.readText() }
        }
        AlertDialog.Builder(this).setTitle(R.string.about_title)
            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton(R.string.dialog_close, null).show()
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
