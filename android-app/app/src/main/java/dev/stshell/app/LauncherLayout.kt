// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Web content and controls share the same system-safe rectangle; controls overlay it. */
class LauncherLayout(
    context: Context,
    initialAnchor: LauncherGeometry.Anchor,
    private val saveAnchor: (LauncherGeometry.Anchor) -> Unit,
    private val requestControls: () -> Unit,
    private val imeShown: () -> Unit,
) : FrameLayout(context) {
    val browserHost = FrameLayout(context).apply { id = R.id.launcher_browser_host }
    private val emptyState = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    private val emptyText = TextView(context).apply {
        id = R.id.launcher_empty_text; gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.launcher_on_window))
    }
    private val startupProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val floating = object : EdgeHandleView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
        override fun performClick(): Boolean = super.performClick()
    }.apply {
        id = R.id.launcher_controls_button
        contentDescription = context.getString(R.string.launcher_controls)
        isClickable = true; isFocusable = true
        setOnClickListener { requestControls() }
    }
    private val shade = FrameLayout(context).apply {
        id = R.id.launcher_controls_overlay
        setBackgroundColor(0x66000000)
        isClickable = true
        visibility = GONE
    }
    private var sheet: View? = null
    private var panelVisible = false
    private var anchor = initialAnchor
    private var phase = "idle"
    private var detail = ""
    private var progressDone = 0
    private var progressTotal = 0
    private var imeVisible = false
    private var finalInsets: WindowInsets? = null
    private val imeAnimations = mutableSetOf<WindowInsetsAnimation>()
    private val handleWidth = dp(24)
    private val handleHeight = dp(72)
    private val edgeMargin = dp(8)
    private val swipeThreshold = dp(24)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downTop = 0
    private var dragging = false
    private var gestureAxis = LauncherGeometry.Axis.NONE

    init {
        id = R.id.launcher_root
        setBackgroundColor(context.getColor(R.color.launcher_window_background))
        addView(browserHost, LayoutParams(-1, -1))
        emptyState.addView(emptyText, LinearLayout.LayoutParams(-1, -2))
        emptyState.addView(startupProgress, LinearLayout.LayoutParams(-1, -2))
        addView(emptyState, LayoutParams(-1, -1))
        addView(floating, LayoutParams(handleWidth, handleHeight, Gravity.TOP or Gravity.LEFT))
        addView(shade, LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, value ->
            finalInsets = value
            if (imeAnimations.isEmpty()) applyInsets(value)
            else { updateImeVisibility(value); updateFloatingVisibility() }
            WindowInsets.CONSUMED
        }
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                    imeAnimations.add(animation); cancelGesture(); updateFloatingVisibility()
                }
            }
            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                applyInsets(insets)
                return insets
            }
            override fun onEnd(animation: WindowInsetsAnimation) {
                imeAnimations.remove(animation)
                if (imeAnimations.isEmpty()) finalInsets?.let(::applyInsets)
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun usableWidth() = max(0, width - paddingLeft - paddingRight)
    private fun usableHeight() = max(0, height - paddingTop - paddingBottom)
    private fun android.graphics.Insets.edges() = LauncherGeometry.Edges(left, top, right, bottom)
    private fun updateImeVisibility(value: WindowInsets) {
        val wasVisible = imeVisible
        val nextVisible = value.isVisible(WindowInsets.Type.ime())
        if (nextVisible && !wasVisible) cancelGesture()
        imeVisible = nextVisible
        if (imeVisible && !wasVisible) imeShown()
    }
    private fun applyInsets(value: WindowInsets) {
        updateImeVisibility(value)
        val padding = LauncherGeometry.insets(value.getInsets(WindowInsets.Type.systemBars()).edges(),
            value.getInsets(WindowInsets.Type.displayCutout()).edges(), value.getInsets(WindowInsets.Type.ime()).edges(), imeVisible || imeAnimations.isNotEmpty())
        if (paddingLeft != padding.left || paddingTop != padding.top || paddingRight != padding.right || paddingBottom != padding.bottom) {
            setPadding(padding.left, padding.top, padding.right, padding.bottom)
        }
        positionFloating(); resizeSheet(); updateFloatingVisibility()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets() }
    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!dragging) positionFloating()
        resizeSheet(); updateEmptyState(); updateFloatingVisibility()
    }

    fun anchor(): LauncherGeometry.Anchor {
        if (dragging) saveDock()
        return anchor
    }
    fun hasBrowser() = browserHost.childCount > 0
    fun updateState(value: String, message: String, done: Int, total: Int) {
        phase = value; detail = message; progressDone = done; progressTotal = total
        updateEmptyState()
    }
    private fun updateEmptyState() {
        emptyState.visibility = if (hasBrowser()) GONE else VISIBLE
        if (hasBrowser()) return
        val busy = phase in setOf("connecting", "preparing", "copying", "unpacking", "verifying", "starting", "checking")
        emptyText.text = if (busy || phase in setOf("failed", "browser-error")) {
            listOf(phase, detail, if (progressTotal > 0) "$progressDone/$progressTotal" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        } else context.getString(R.string.launcher_open_controls)
        startupProgress.visibility = if (busy) VISIBLE else GONE
        startupProgress.isIndeterminate = progressTotal <= 0
        if (progressTotal > 0) { startupProgress.max = progressTotal; startupProgress.progress = progressDone }
    }
    fun showPanel(panel: View, dismiss: () -> Unit) {
        cancelGesture()
        shade.removeAllViews()
        sheet = panel
        panel.isClickable = true
        shade.setOnClickListener { dismiss() }
        shade.addView(panel, LayoutParams(-1, dp(520), Gravity.BOTTOM))
        panelVisible = true; shade.visibility = VISIBLE
        resizeSheet(); updateFloatingVisibility()
    }
    fun hidePanel() {
        panelVisible = false; shade.visibility = GONE
        shade.removeAllViews(); sheet = null
        updateFloatingVisibility()
    }
    private fun resizeSheet() {
        val panel = sheet ?: return
        val target = min(dp(520), (usableHeight() * 0.8f).toInt()).coerceAtLeast(0)
        val params = panel.layoutParams as LayoutParams
        if (params.height != target) { params.height = target; panel.layoutParams = params }
    }
    private fun positionFloating() {
        if (dragging) return
        floating.dockRight = anchor.right
        val target = LauncherGeometry.position(usableWidth(), usableHeight(), handleWidth, handleHeight, edgeMargin, anchor)
        place(target.left, target.top)
    }
    private fun place(left: Int, top: Int) {
        val params = floating.layoutParams as LayoutParams
        val x = left.coerceIn(0, max(0, usableWidth() - handleWidth))
        val y = top.coerceIn(0, max(0, usableHeight() - handleHeight))
        if (params.leftMargin != x || params.topMargin != y) {
            params.leftMargin = x; params.topMargin = y; floating.layoutParams = params
        }
    }
    private fun updateFloatingVisibility() {
        val visible = !panelVisible && !imeVisible && imeAnimations.isEmpty() && usableWidth() >= handleWidth && usableHeight() >= handleHeight
        floating.visibility = if (visible) VISIBLE else GONE
        floating.systemGestureExclusionRects = if (visible) listOf(Rect(0, 0, handleWidth, handleHeight)) else emptyList()
    }
    private fun saveDock() {
        val params = floating.layoutParams as LayoutParams
        anchor = LauncherGeometry.fromVerticalDrag(usableHeight(), handleHeight, edgeMargin, anchor, params.topMargin.toFloat())
        saveAnchor(anchor)
    }
    private fun cancelGesture() {
        if (dragging) saveDock()
        pointerId = -1; dragging = false; gestureAxis = LauncherGeometry.Axis.NONE; floating.isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        positionFloating()
    }
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.rawX; downY = event.rawY
                val params = floating.layoutParams as LayoutParams
                downTop = params.topMargin
                dragging = false; gestureAxis = LauncherGeometry.Axis.NONE; floating.isPressed = true
                floating.drawableHotspotChanged(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0 || event.pointerCount != 1) { cancelGesture(); return true }
                val dx = event.getRawX(index) - downX; val dy = event.getRawY(index) - downY
                if (gestureAxis == LauncherGeometry.Axis.NONE) {
                    gestureAxis = LauncherGeometry.axis(dx, dy, touchSlop)
                    if (gestureAxis != LauncherGeometry.Axis.NONE) {
                        dragging = gestureAxis == LauncherGeometry.Axis.VERTICAL
                        floating.isPressed = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (dragging) {
                    val edge = if (anchor.right) max(0, usableWidth() - handleWidth) else 0
                    place(edge, (downTop + dy).toInt())
                }
            }
            MotionEvent.ACTION_UP -> {
                val click = pointerId >= 0 && gestureAxis == LauncherGeometry.Axis.NONE
                val swipe = pointerId >= 0 && gestureAxis == LauncherGeometry.Axis.HORIZONTAL &&
                    LauncherGeometry.opensPanel(anchor.right, event.rawX - downX, event.rawY - downY, swipeThreshold)
                if (dragging) saveDock()
                pointerId = -1; dragging = false; gestureAxis = LauncherGeometry.Axis.NONE; floating.isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                positionFloating()
                if (click || swipe) floating.performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancelGesture()
        }
        return true
    }
}
