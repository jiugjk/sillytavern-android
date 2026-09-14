// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

open class EdgeHandleView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : View(context, attributes) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = RectF()
    var dockRight = true
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val thickness = min(width.toFloat(), 4 * density)
        val length = min(height.toFloat(), 48 * density)
        val left = if (dockRight) width - thickness else 0f
        val top = (height - length) / 2f
        mark.set(left, top, left + thickness, top + length)
        paint.color = context.getColor(if (isPressed) R.color.launcher_handle_pressed else R.color.launcher_handle)
        canvas.drawRoundRect(mark, 2 * density, 2 * density, paint)
    }
}
