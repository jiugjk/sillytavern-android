// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.app

import org.junit.Assert.*
import org.junit.Test

class LauncherGeometryTest {
    @Test fun systemBarsAndCutoutUsePerEdgeMaximum() {
        val result = LauncherGeometry.insets(LauncherGeometry.Edges(0, 70, 0, 48),
            LauncherGeometry.Edges(90, 110, 5, 0), LauncherGeometry.Edges(), false)
        assertEquals(LauncherGeometry.Edges(90, 110, 5, 48), result)
    }
    @Test fun keyboardReplacesRatherThanAddsNavigationInset() {
        val result = LauncherGeometry.insets(LauncherGeometry.Edges(bottom = 48),
            LauncherGeometry.Edges(), LauncherGeometry.Edges(bottom = 600), true)
        assertEquals(600, result.bottom)
    }
    @Test fun hiddenImeDoesNotLeaveOldBottomSpace() {
        assertEquals(48, LauncherGeometry.insets(LauncherGeometry.Edges(bottom = 48),
            LauncherGeometry.Edges(), LauncherGeometry.Edges(bottom = 600), false).bottom)
    }
    @Test fun handleTouchesEitherContentEdgeExactly() {
        val right = LauncherGeometry.position(1080, 1920, 72, 216, 24, LauncherGeometry.Anchor())
        val left = LauncherGeometry.position(1080, 1920, 72, 216, 24, LauncherGeometry.Anchor(right = false))
        assertEquals(1080, right.left + 72); assertEquals(0, left.left)
        assertEquals(right.top, left.top)
    }
    @Test fun verticalMovePreservesSideAndRelativeHeightAcrossRotation() {
        val anchor = LauncherGeometry.fromVerticalDrag(1920, 216, 24, LauncherGeometry.Anchor(right = false), 1000f)
        assertFalse(anchor.right)
        val portrait = LauncherGeometry.position(1080, 1920, 72, 216, 24, anchor)
        val landscape = LauncherGeometry.position(1920, 1080, 72, 216, 24, anchor)
        assertEquals(0, portrait.left); assertEquals(0, landscape.left)
        assertEquals(1000f, portrait.top.toFloat(), 1f)
        assertEquals(anchor.fraction, (landscape.top - 24f) / (1080 - 216 - 48), 0.002f)
    }
    @Test fun outOfRangeVerticalPositionAndStoredFractionAreClamped() {
        val top = LauncherGeometry.fromVerticalDrag(800, 72, 8, LauncherGeometry.Anchor(), -900f)
        val bottom = LauncherGeometry.fromVerticalDrag(800, 72, 8, LauncherGeometry.Anchor(), 9000f)
        assertEquals(0f, top.fraction); assertEquals(1f, bottom.fraction)
        assertEquals(8, LauncherGeometry.position(400, 800, 24, 72, 8, top).top)
        assertEquals(720, LauncherGeometry.position(400, 800, 24, 72, 8, bottom).top)
        val fallback = LauncherGeometry.position(400, 800, 24, 72, 8, LauncherGeometry.Anchor(fraction = Float.NaN))
        assertEquals(LauncherGeometry.position(400, 800, 24, 72, 8, LauncherGeometry.Anchor()), fallback)
    }
    @Test fun smallOrTransientViewportNeverProducesNegativeOffsets() {
        for (size in listOf(0, 1, 24, 48, 72)) {
            val position = LauncherGeometry.position(size, size, 24, 72, 8, LauncherGeometry.Anchor())
            assertTrue(position.left >= 0 && position.top >= 0)
        }
    }
    @Test fun inwardSwipeWorksOnBothEdges() {
        assertTrue(LauncherGeometry.opensPanel(true, -80f, 4f, 24))
        assertTrue(LauncherGeometry.opensPanel(false, 80f, -4f, 24))
        assertFalse(LauncherGeometry.opensPanel(true, 80f, 0f, 24))
        assertFalse(LauncherGeometry.opensPanel(false, -80f, 0f, 24))
    }
    @Test fun shortOrVerticalMotionsDoNotOpenPanel() {
        assertFalse(LauncherGeometry.opensPanel(true, -12f, 0f, 24))
        assertFalse(LauncherGeometry.opensPanel(true, -40f, 80f, 24))
    }
    @Test fun gestureAxisSeparatesTapVerticalAndHorizontalIntent() {
        assertEquals(LauncherGeometry.Axis.NONE, LauncherGeometry.axis(3f, 4f, 8))
        assertEquals(LauncherGeometry.Axis.VERTICAL, LauncherGeometry.axis(3f, 80f, 8))
        assertEquals(LauncherGeometry.Axis.HORIZONTAL, LauncherGeometry.axis(-80f, 3f, 8))
    }
}
