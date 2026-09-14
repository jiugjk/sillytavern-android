// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Intent
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LauncherLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun activity() = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun waitUntil(message: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!test()) { assertTrue(message, SystemClock.elapsedRealtime() < deadline); Thread.sleep(100) }
    }
    private fun resumed(): MainActivity? {
        var result: MainActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().lastOrNull()
        }
        return result
    }

    @Test fun contentFillsInsetAreaAndPanelDoesNotResizeIt() {
        val activity = activity()
        try {
            instrumentation.waitForIdleSync()
            lateinit var root: LauncherLayout
            lateinit var button: EdgeHandleView
            instrumentation.runOnMainSync {
                activity.closeControls()
                root = activity.findViewById(R.id.launcher_root)
                button = activity.findViewById(R.id.launcher_controls_button)
                root.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 40))
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(70, 80, 0, 0))
                    .setInsets(WindowInsets.Type.ime(), Insets.NONE).setVisible(WindowInsets.Type.ime(), false).build())
            }
            instrumentation.waitForIdleSync()
            var beforeWidth = 0; var beforeHeight = 0
            instrumentation.runOnMainSync {
                assertEquals(activity.getColor(R.color.launcher_window_background), (root.background as ColorDrawable).color)
                assertEquals(0, activity.window.insetsController!!.systemBarsAppearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                assertEquals(70, root.browserHost.left)
                assertEquals(80, root.browserHost.top)
                assertEquals(root.width - root.paddingLeft - root.paddingRight, root.browserHost.width)
                assertEquals(root.height - root.paddingTop - root.paddingBottom, root.browserHost.height)
                beforeWidth = root.browserHost.width; beforeHeight = root.browserHost.height
                assertEquals(View.VISIBLE, button.visibility)
                activity.openControls()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(beforeWidth, root.browserHost.width)
                assertEquals(beforeHeight, root.browserHost.height)
                assertEquals(View.GONE, button.visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.launcher_controls_overlay).visibility)
                root.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 40))
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 400))
                    .setVisible(WindowInsets.Type.ime(), true).build())
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(400, root.paddingBottom)
                assertEquals(root.height - 60 - 400, root.browserHost.height)
                assertEquals(View.GONE, button.visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.launcher_controls_overlay).visibility)
                root.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 40))
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 400))
                    .setVisible(WindowInsets.Type.ime(), false).build())
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(40, root.paddingBottom); assertEquals(View.VISIBLE, button.visibility) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun verticalHandleDragSurvivesActivityRecreation() {
        var activity = activity()
        val preferences = instrumentation.targetContext.getSharedPreferences("launcher-ui", 0)
        val hadSide = preferences.contains("dockRight"); val oldSide = preferences.getBoolean("dockRight", true)
        val hadFraction = preferences.contains("dockFraction"); val oldFraction = preferences.getFloat("dockFraction", 0.65f)
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { activity.closeControls() }
            instrumentation.waitForIdleSync()
            val location = IntArray(2); val target = IntArray(2)
            var handleWidth = 0; var handleHeight = 0; var originalSide = true
            instrumentation.runOnMainSync {
                val root = activity.findViewById<LauncherLayout>(R.id.launcher_root)
                val button = activity.findViewById<EdgeHandleView>(R.id.launcher_controls_button)
                button.getLocationOnScreen(location); root.browserHost.getLocationOnScreen(target)
                handleWidth = button.width; handleHeight = button.height; originalSide = root.anchor().right
                val upper = location[1] + button.height / 2 < target[1] + root.browserHost.height / 2
                target[0] = location[0] + button.width / 2
                target[1] += (root.browserHost.height * if (upper) 0.75f else 0.25f).toInt()
            }
            val startX = location[0] + handleWidth / 2f; val startY = location[1] + handleHeight / 2f
            val time = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, startX, startY)
            send(MotionEvent.ACTION_MOVE, target[0].toFloat(), target[1].toFloat())
            send(MotionEvent.ACTION_UP, target[0].toFloat(), target[1].toFloat())
            instrumentation.waitForIdleSync()
            lateinit var anchor: LauncherGeometry.Anchor
            instrumentation.runOnMainSync {
                val root = activity.findViewById<LauncherLayout>(R.id.launcher_root)
                anchor = root.anchor(); assertEquals(originalSide, anchor.right)
                val handle = activity.findViewById<View>(R.id.launcher_controls_button)
                assertEquals(if (originalSide) root.width - root.paddingRight - handle.width else root.paddingLeft, handle.left)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.launcher_controls_overlay).visibility)
                activity.recreate()
            }
            val oldActivity = activity
            waitUntil("Activity did not recreate") { val current = resumed(); current != null && current !== oldActivity }
            activity = requireNotNull(resumed())
            instrumentation.runOnMainSync { assertEquals(anchor, activity.findViewById<LauncherLayout>(R.id.launcher_root).anchor()) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            preferences.edit().apply {
                if (hadSide) putBoolean("dockRight", oldSide) else remove("dockRight")
                if (hadFraction) putFloat("dockFraction", oldFraction) else remove("dockFraction")
            }.commit()
        }
    }

    @Test fun inwardSwipeFromHandleOpensPanel() {
        val activity = activity()
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { activity.closeControls() }
            instrumentation.waitForIdleSync()
            val location = IntArray(2)
            var startX = 0f; var startY = 0f; var endX = 0f
            instrumentation.runOnMainSync {
                val root = activity.findViewById<LauncherLayout>(R.id.launcher_root)
                val handle = activity.findViewById<EdgeHandleView>(R.id.launcher_controls_button)
                handle.getLocationOnScreen(location)
                startX = location[0] + handle.width / 2f; startY = location[1] + handle.height / 2f
                val distance = 64 * activity.resources.displayMetrics.density
                endX = startX + if (root.anchor().right) -distance else distance
            }
            val time = SystemClock.uptimeMillis()
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to startX, MotionEvent.ACTION_MOVE to endX, MotionEvent.ACTION_UP to endX)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, startY, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.launcher_controls_overlay).visibility) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun realKeyboardResizesWebContentAndHidesControlButton() {
        val activity = activity()
        var browser: WebView? = null
        try {
            instrumentation.waitForIdleSync()
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                activity.closeControls()
                browser = WebView(activity).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                }
                activity.attachBrowser(requireNotNull(browser))
                browser.loadDataWithBaseURL("https://layout.test/", "<meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0'><input style='position:fixed;bottom:0;left:0;box-sizing:border-box;width:100%;height:48px;font-size:18px' aria-label='Keyboard test'></body>", "text/html", "UTF-8", null)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val location = IntArray(2); var x = 0f; var y = 0f
            instrumentation.runOnMainSync {
                val view = requireNotNull(browser); view.getLocationOnScreen(location)
                x = location[0] + view.width / 2f
                y = location[1] + view.height - 24 * activity.resources.displayMetrics.density
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            waitUntil("Soft keyboard did not appear") {
                var visible = false
                instrumentation.runOnMainSync { visible = activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
                visible
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val root = activity.findViewById<LauncherLayout>(R.id.launcher_root)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.launcher_controls_button).visibility)
                assertEquals(root.height - root.paddingTop - root.paddingBottom, requireNotNull(browser).height)
                activity.window.insetsController?.hide(WindowInsets.Type.ime())
            }
            waitUntil("Control button did not return after keyboard hide") {
                var shown = false
                instrumentation.runOnMainSync { shown = activity.findViewById<View>(R.id.launcher_controls_button).visibility == View.VISIBLE }
                shown
            }
        } finally {
            instrumentation.runOnMainSync { browser?.destroy(); activity.finish() }
        }
    }
}
