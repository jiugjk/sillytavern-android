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
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.WebView
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {
    /** Views inside the control panel that track live session state. */
    private class Panel(
        val root: ScrollView,
        val phase: TextView,
        val detail: TextView,
        val measure: TextView,
        val step: TextView,
        val progress: ProgressBar,
        val protection: TextView,
        val observer: TextView,
        val reopen: View,
    )

    private lateinit var launcher: LauncherLayout
    private var panel: Panel? = null
    private var latestState = LauncherUiState()
    private var renderedState: LauncherUiState? = null
    private var controlsOpen = false
    private var pendingProtection = false
    private var checkingDialog: AlertDialog? = null
    private val closePanelOnBack = OnBackInvokedCallback { closeControls() }

    /** Keeps asset and log reads off the main thread; the UI only gets results. */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LauncherUi").apply { isDaemon = true }
    }

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
        launcher.updateState(latestState)
    }
    fun openControls() {
        if (controlsOpen || isFinishing || isDestroyed) return
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        // The panel is built once per Activity and reused: reopening it should
        // not re-inflate a dozen views and re-allocate their drawables.
        val sheet = panel ?: buildPanel().also { panel = it }
        controlsOpen = true
        launcher.browserHost.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        launcher.showPanel(sheet.root) { closeControls() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, closePanelOnBack)
        renderedState = null
        updatePanelState()
        sheet.root.requestFocus()
    }
    fun closeControls() {
        if (!controlsOpen) return
        controlsOpen = false
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closePanelOnBack)
        launcher.hidePanel()
        launcher.browserHost.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /** Assembles the styled control panel; see LauncherStyle for the vocabulary. */
    private fun buildPanel(): Panel {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }
        column.addView(LauncherStyle.grabber(this), LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
        })
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(LauncherStyle.title(this, R.string.launcher_panel_title),
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(LauncherStyle.textAction(this, R.string.launcher_panel_close) { closeControls() })
        column.addView(heading)

        // Status card: phase, detail, progress and the two live capability chips.
        val card = LauncherStyle.card(this)
        val phaseRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val phase = LauncherStyle.phaseLabel(this).apply { id = R.id.launcher_panel_phase }
        val step = LauncherStyle.body(this, 12f).apply { id = R.id.launcher_panel_step }
        phaseRow.addView(phase, LinearLayout.LayoutParams(0, -2, 1f))
        phaseRow.addView(step)
        card.addView(phaseRow)
        val detail = LauncherStyle.body(this, 13f).apply {
            id = R.id.launcher_panel_status
            maxLines = 3
        }
        card.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val progress = LauncherStyle.progressBar(this).apply { id = R.id.launcher_panel_progress }
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
        val measure = LauncherStyle.body(this, 12f).apply { id = R.id.launcher_panel_percent }
        card.addView(measure, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val chips = LinearLayout(this).apply {
            id = R.id.launcher_panel_chips
            orientation = LinearLayout.HORIZONTAL
        }
        val protection = LauncherStyle.chip(this, "", getColor(R.color.launcher_text_secondary))
        val observer = LauncherStyle.chip(this, "", getColor(R.color.launcher_text_secondary))
        chips.addView(protection)
        chips.addView(observer, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        fun action(labelRes: Int, kind: LauncherStyle.ButtonKind = LauncherStyle.ButtonKind.SECONDARY, callback: () -> Unit) =
            LauncherStyle.button(this, labelRes, kind) {
                closeControls()
                try { callback() } catch (error: Exception) { notice(error.message ?: getString(R.string.action_failed)) }
            }
        fun section(titleRes: Int, vararg rows: View) {
            column.addView(LauncherStyle.sectionTitle(this, titleRes))
            val group = LauncherStyle.card(this)
            for ((index, row) in rows.withIndex()) {
                group.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
            }
            column.addView(group, LinearLayout.LayoutParams(-1, -2))
        }

        // Page-only recovery: rebuild the WebView while Node keeps running.
        // The whole row is hidden unless the service is healthy and just the
        // page is broken, so a hidden action leaves no gap behind.
        val reopen = LauncherStyle.actionRow(this,
            action(R.string.action_reopen_page) { SessionController.reopenBrowser() }
                .apply { id = R.id.launcher_action_reopen_page }).apply { visibility = View.GONE }
        section(R.string.section_service,
            LauncherStyle.actionRow(this, action(R.string.action_start, LauncherStyle.ButtonKind.PRIMARY) { SessionController.start() }),
            reopen,
            LauncherStyle.actionRow(this,
                action(R.string.action_restart) { confirmServiceAction(getString(R.string.confirm_restart_title)) { SessionController.stop(restart = true) } },
                action(R.string.action_stop, LauncherStyle.ButtonKind.DANGER) { confirmServiceAction(getString(R.string.confirm_stop_title)) { SessionController.stop() } }))
        section(R.string.section_update,
            LauncherStyle.actionRow(this, action(R.string.action_check_update, LauncherStyle.ButtonKind.TONAL) { checkForUpdates() }),
            LauncherStyle.actionRow(this, action(R.string.action_redownload) { confirmRedownload() }))
        section(R.string.section_system,
            LauncherStyle.actionRow(this,
                action(R.string.action_background_protection) { toggleProtection() },
                action(R.string.action_battery_settings) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }))
        section(R.string.section_diagnostics,
            LauncherStyle.actionRow(this,
                action(R.string.action_diagnostics) { copyDiagnostic() },
                action(R.string.action_startup_log) { showStartupLog() }),
            LauncherStyle.actionRow(this, action(R.string.action_about) { showAbout() }))

        val sheet = ScrollView(this).apply {
            id = R.id.launcher_controls_sheet
            background = LauncherStyle.sheetBackground(this@MainActivity)
            addView(column)
            isVerticalScrollBarEnabled = false
            clipToOutline = true
            accessibilityPaneTitle = getString(R.string.launcher_panel_title)
            isFocusableInTouchMode = true
        }
        return Panel(sheet, phase, detail, measure, step, progress, protection, observer, reopen)
    }

    private fun dp(value: Int) = LauncherStyle.dp(this, value.toFloat())

    fun showState(state: LauncherUiState) {
        latestState = state
        launcher.updateState(state)
        updatePanelState()
    }
    private fun updatePanelState() {
        val sheet = panel ?: return
        if (!controlsOpen) return
        val state = latestState
        if (state == renderedState) return
        renderedState = state
        val phase = state.displayPhase
        sheet.phase.text = getString(InstallProgress.labelRes(phase))
        LauncherStyle.applyDot(sheet.phase, getColor(InstallProgress.toneRes(phase)))
        val stepIndex = state.step
        sheet.step.text = if (stepIndex > 0) getString(R.string.progress_step, stepIndex, InstallProgress.STEP_COUNT) else ""
        sheet.step.visibility = if (stepIndex > 0) View.VISIBLE else View.GONE
        sheet.detail.text = state.displayDetail.ifEmpty { getString(R.string.progress_detail_empty) }
        val measure = InstallProgress.measure(this, state.progress)
        // ServerPhase owns "is startup in progress", not a string list here.
        val busy = state.session.serverPhase.busy
        sheet.progress.visibility = if (busy) View.VISIBLE else View.GONE
        sheet.measure.visibility = if (busy) View.VISIBLE else View.GONE
        // Work with no countable total still says something: "进行中…".
        sheet.measure.text = measure.ifEmpty { getString(R.string.progress_working) }
        val percent = state.progress.percent
        sheet.progress.isIndeterminate = percent == null
        if (percent != null) { sheet.progress.max = 100; sheet.progress.progress = percent }
        chip(sheet.protection, getString(when {
            state.protection.enabled && state.protection.wakeHeld -> R.string.chip_wake_held
            state.protection.enabled -> R.string.chip_protection_on
            else -> R.string.chip_protection_off
        }),
            if (state.protection.enabled) R.color.launcher_success else R.color.launcher_text_secondary)
        chip(sheet.observer, getString(if (state.observer.observerReady) R.string.chip_observer_ready else R.string.chip_observer_waiting),
            if (state.observer.observerReady) R.color.launcher_success else R.color.launcher_warning)
        sheet.reopen.visibility = if (state.canReopenBrowser) View.VISIBLE else View.GONE
    }
    private fun chip(view: TextView, text: String, colorRes: Int) {
        if (view.text != text) view.text = text
        LauncherStyle.applyDot(view, getColor(colorRes))
    }


    /** Dark alert styling so dialogs match the immersive launcher chrome. */
    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun confirmRedownload() {
        dialog().setTitle(R.string.redownload_title)
            .setMessage(R.string.redownload_message)
            .setPositiveButton(R.string.confirm_positive) { _, _ -> SessionController.redownloadSources() }
            .setNegativeButton(R.string.confirm_cancel, null).show()
    }

    // --- Update check ------------------------------------------------------

    /** Asks GitHub what the installed components' refs point at now. */
    private val updateCallback: (Pair<UpdateReport?, String?>) -> Unit = { (report, failure) ->
        onUpdateResult(report, failure)
    }

    private fun checkForUpdates() {
        if (checkingDialog != null) return
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        row.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(LauncherStyle.body(this, 14f, R.color.launcher_text_primary).apply {
            setText(R.string.update_checking)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(14) })
        checkingDialog = dialog().setTitle(R.string.update_title).setView(row)
            .setNegativeButton(R.string.update_hide, null)
            .setOnDismissListener {
                checkingDialog = null
                UpdateChecker.detach(updateCallback)
            }
            .show()
        UpdateChecker.check(this, updateCallback)
    }
    private fun onUpdateResult(report: UpdateReport?, failure: String?) {
        val pending = checkingDialog
        checkingDialog = null
        // A dismissed dialog means the user walked away from this check.
        if (pending == null || isFinishing || isDestroyed) { pending?.dismiss(); return }
        pending.setOnDismissListener(null)
        pending.dismiss()
        if (report == null) { notice(getString(R.string.update_failed, failure ?: getString(R.string.action_failed))); return }
        showUpdateReport(report)
    }
    private fun showUpdateReport(report: UpdateReport) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val summary = when {
            report.hasContentUpdate || report.appUpdateAvailable ->
                getString(R.string.update_available_summary,
                    report.updatable.size + if (report.appUpdateAvailable) 1 else 0)
            report.unknown > 0 -> getString(R.string.update_unavailable_summary, report.unknown)
            report.components.isEmpty() -> getString(R.string.update_app_current_only)
            else -> getString(R.string.update_none)
        }
        column.addView(LauncherStyle.body(this, 14f, R.color.launcher_text_primary).apply {
            text = summary
            typeface = Typeface.DEFAULT_BOLD
        })
        if (report.components.isEmpty()) {
            column.addView(LauncherStyle.body(this, 13f).apply { setText(R.string.update_content_missing) })
        }
        report.app?.let { app ->
            column.addView(updateRow(getString(R.string.update_component_app),
                if (app.state == UpdateState.AVAILABLE)
                    getString(R.string.update_revision, app.installedVersion, app.latestVersion)
                else getString(R.string.update_revision_same, app.installedVersion.ifEmpty { "—" }),
                app.state))
        }
        for (component in report.components) {
            val kind = getString(if (component.installed.kind == ComponentKind.SERVER)
                R.string.update_component_server else R.string.update_component_extension)
            val revision = if (component.state == UpdateState.AVAILABLE)
                getString(R.string.update_revision, component.installedShort, component.latestShort)
            else getString(R.string.update_revision_same, component.installedShort)
            column.addView(updateRow("${component.installed.name} · $kind",
                "${component.installed.ref} · $revision", component.state))
        }
        if (report.appUpdateAvailable) {
            column.addView(LauncherStyle.body(this, 12f).apply {
                setText(R.string.update_app_hint)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val builder = dialog().setTitle(R.string.update_title)
            .setView(ScrollView(this).apply { addView(column) })
            .setNegativeButton(R.string.dialog_close, null)
        if (report.hasContentUpdate) {
            builder.setPositiveButton(R.string.update_action_download) { _, _ ->
                dialog().setTitle(R.string.update_confirm_title)
                    .setMessage(R.string.redownload_message)
                    .setPositiveButton(R.string.confirm_positive) { _, _ -> SessionController.redownloadSources() }
                    .setNegativeButton(R.string.confirm_cancel, null).show()
            }
        }
        val app = report.app
        if (report.appUpdateAvailable && app != null) {
            builder.setNeutralButton(R.string.update_action_release) { _, _ -> openLink(app.releaseUrl) }
        }
        builder.show()
    }
    private fun updateRow(name: String, revision: String, state: UpdateState): View {
        val row = LauncherStyle.card(this)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LauncherStyle.body(this, 14f, R.color.launcher_text_primary).apply { text = name },
            LinearLayout.LayoutParams(0, -2, 1f))
        val badge = when (state) {
            UpdateState.AVAILABLE -> R.string.update_state_available to R.color.launcher_warning
            UpdateState.CURRENT -> R.string.update_state_current to R.color.launcher_success
            UpdateState.UNKNOWN -> R.string.update_state_unknown to R.color.launcher_danger
        }
        header.addView(LauncherStyle.chip(this, getString(badge.first), getColor(badge.second)))
        row.addView(header)
        row.addView(LauncherStyle.body(this, 12f).apply { text = revision },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        return row
    }

    // --- Background protection --------------------------------------------

    private fun toggleProtection() {
        if (BackgroundProtectionService.running) {
            dialog().setTitle(R.string.protection_disable_title)
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
        dialog().setTitle(R.string.protection_enable_title)
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
        dialog().setTitle(name)
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

    // --- Diagnostics, logs, about -----------------------------------------

    private fun copyDiagnostic() {
        notice(getString(R.string.diagnostics_preparing))
        // Collecting reads several private JSON files; only the clipboard write
        // has to happen here on the main thread.
        SessionController.diagnosticAsync { value ->
            if (isFinishing || isDestroyed) return@diagnosticAsync
            val clip = ClipData.newPlainText(getString(R.string.diagnostics_clip_label), value)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            notice(getString(R.string.diagnostics_copied))
        }
    }
    private fun showStartupLog() {
        notice(getString(R.string.startup_log_loading))
        readInBackground({
            val file = java.io.File(AppFiles.state(this), "startup.log")
            if (file.isFile && file.length() <= 65536) file.readText() else getString(R.string.startup_log_empty)
        }) { log -> showTextDialog(R.string.startup_log_title, log, monospace = true) }
    }
    private fun showAbout() {
        val version = UpdateChecker.installedVersion(this).ifEmpty { "—" }
        val repository = getString(R.string.about_repository_url)
        val body = getString(R.string.about_version, version) + "\n" +
            getString(R.string.about_repository_label, repository) + "\n\n" +
            getString(R.string.about_body).trimEnd()
        val text = LauncherStyle.body(this, 13f, R.color.launcher_text_primary).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setText(body)
        }
        dialog().setTitle(R.string.about_title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.dialog_close, null)
            .setNeutralButton(R.string.action_open_repository) { _, _ -> openLink(repository) }
            .setNegativeButton(R.string.about_license_button) { _, _ -> showLicense() }
            .show()
    }
    private fun showLicense() {
        notice(getString(R.string.about_license_loading))
        readInBackground({ assets.open("licenses/AGPL-3.0.txt").bufferedReader().use { it.readText() } }) { license ->
            showTextDialog(R.string.about_title, license, monospace = true)
        }
    }
    private fun showTextDialog(titleRes: Int, body: String, monospace: Boolean) {
        val text = LauncherStyle.body(this, 12f, R.color.launcher_text_primary).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            if (monospace) typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setText(body)
        }
        dialog().setTitle(titleRes)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.dialog_close, null).show()
    }
    /** Runs [work] off the main thread and delivers the result back to the UI. */
    private fun <T> readInBackground(work: () -> T, deliver: (T) -> Unit) {
        background.execute {
            val result = try { work() } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) notice(getString(R.string.action_failed)) else deliver(result)
            }
        }
    }
    /** Hands a vetted https link to the system browser; never a page we render. */
    private fun openLink(url: String) {
        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "https" || uri.host != "github.com") {
            notice(getString(R.string.open_link_failed)); return
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { notice(getString(R.string.open_link_failed)) }
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
    override fun onDestroy() {
        UpdateChecker.detach(updateCallback)
        checkingDialog?.dismiss(); checkingDialog = null
        closeControls()
        panel = null
        background.shutdownNow()
        SessionController.detach(this)
        super.onDestroy()
    }
    companion object { private const val OPEN_FILE = 101; private const val NOTIFICATION_PERMISSION = 102 }
}
