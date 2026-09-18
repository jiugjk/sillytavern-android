// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The always-available edge grip. It stays deliberately small so it never
 * competes with the page, but it is the only way into the control panel, so it
 * reads as a tappable pill: a soft accent gradient, and a brighter, slightly
 * wider state while pressed.
 */
open class EdgeHandleView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : View(context, attributes) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = RectF()
    private var gradient: Shader? = null
    private var gradientKey = 0
    var dockRight = true
        set(value) { if (field != value) { field = value; gradient = null; invalidate() } }

    init {
        // The glow is drawn by the paint's shadow layer, which needs software
        // rendering for this view only.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val pressed = isPressed
        val thickness = min(width.toFloat(), (if (pressed) 6f else 4.5f) * density)
        val length = min(height.toFloat(), (if (pressed) 56f else 48f) * density)
        val left = if (dockRight) width - thickness else 0f
        val top = (height - length) / 2f
        mark.set(left, top, left + thickness, top + length)
        val radius = thickness / 2f
        val accent = context.getColor(R.color.launcher_accent)
        val tip = context.getColor(if (pressed) R.color.launcher_handle_pressed else R.color.launcher_handle)
        val key = if (pressed) 1 else 0
        if (gradient == null || gradientKey != key) {
            gradientKey = key
            gradient = LinearGradient(mark.left, mark.top, mark.left, mark.bottom,
                intArrayOf(tip, accent, tip), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        paint.shader = gradient
        paint.alpha = if (pressed) 255 else 216
        paint.setShadowLayer(6f * density, 0f, 0f, accent)
        canvas.drawRoundRect(mark, radius, radius, paint)
        paint.clearShadowLayer()
        paint.shader = null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        gradient = null
    }
}
