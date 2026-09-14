// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object LauncherGeometry {
    data class Edges(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)
    data class Anchor(val right: Boolean = true, val fraction: Float = 0.65f)
    data class Position(val left: Int, val top: Int)
    enum class Axis { NONE, HORIZONTAL, VERTICAL }

    fun insets(bars: Edges, cutout: Edges, ime: Edges, imeVisible: Boolean) = Edges(
        max(bars.left, cutout.left), max(bars.top, cutout.top), max(bars.right, cutout.right),
        max(max(bars.bottom, cutout.bottom), if (imeVisible) ime.bottom else 0)
    )
    private fun fraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.65f
    fun position(width: Int, height: Int, handleWidth: Int, handleHeight: Int, margin: Int, anchor: Anchor): Position {
        val freeX = max(0, width - handleWidth); val freeY = max(0, height - handleHeight)
        val my = min(max(0, margin), freeY / 2)
        return Position(if (anchor.right) freeX else 0, my + ((freeY - 2 * my) * fraction(anchor.fraction)).toInt())
    }
    fun fromVerticalDrag(height: Int, handleHeight: Int, margin: Int, anchor: Anchor, top: Float): Anchor {
        val freeY = max(0, height - handleHeight); val my = min(max(0, margin), freeY / 2)
        val travel = freeY - 2 * my
        return anchor.copy(fraction = if (travel > 0) fraction((top - my) / travel) else 0.65f)
    }
    fun axis(dx: Float, dy: Float, slop: Int): Axis = when {
        max(abs(dx), abs(dy)) <= slop -> Axis.NONE
        abs(dy) > abs(dx) -> Axis.VERTICAL
        else -> Axis.HORIZONTAL
    }
    fun opensPanel(right: Boolean, dx: Float, dy: Float, threshold: Int): Boolean {
        val inward = if (right) -dx else dx
        return inward >= threshold && abs(dx) >= abs(dy)
    }
}
