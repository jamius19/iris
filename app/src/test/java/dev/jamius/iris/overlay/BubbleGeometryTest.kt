/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import dev.jamius.iris.data.settings.BubbleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGeometryTest {

    private val area = Area(left = 0, top = 100, right = 1000, bottom = 2100)
    private val size = 100

    @Test
    fun movementWithinTouchSlopIsATap() {
        assertFalse(isDrag(3f, 4f, touchSlop = 5))
        assertTrue(isDrag(4f, 4f, touchSlop = 5))
    }

    @Test
    fun clampKeepsViewInsideRange() {
        assertEquals(100, clamp(50, 100, 2100, size))
        assertEquals(2000, clamp(2050, 100, 2100, size))
        assertEquals(500, clamp(500, 100, 2100, size))
    }

    @Test
    fun clampPinsToMinWhenRangeIsSmallerThanView() {
        assertEquals(100, clamp(300, 100, 150, size))
    }

    @Test
    fun snapsToNearestEdge() {
        assertEquals(BubbleSide.LEFT, nearestSide(bubbleX = 300, size = size, area = area))
        assertEquals(BubbleSide.RIGHT, nearestSide(bubbleX = 500, size = size, area = area))
        assertEquals(0, snappedX(BubbleSide.LEFT, size, area))
        assertEquals(900, snappedX(BubbleSide.RIGHT, size, area))
    }

    @Test
    fun yFractionRoundTrips() {
        assertEquals(100, yForFraction(0f, size, area))
        assertEquals(2000, yForFraction(1f, size, area))
        assertEquals(0.5f, fractionForY(yForFraction(0.5f, size, area), size, area))
    }

    @Test
    fun fractionIsClampedAndSurvivesAreaChange() {
        assertEquals(0f, fractionForY(0, size, area))
        assertEquals(1f, fractionForY(5000, size, area))
        // Rotated to landscape: the same fraction maps into the new, shorter range.
        val landscape = Area(left = 0, top = 50, right = 2100, bottom = 1000)
        assertEquals(475, yForFraction(0.5f, size, landscape))
    }

    @Test
    fun panelFacesScreenCenter() {
        val right = panelPlacement(BubbleSide.RIGHT, 900, 300, size, 1000, 2200, area, gap = 10)
        assertEquals(PanelPlacement(alignRight = true, alignBottom = false, x = 110, y = 300), right)

        val left = panelPlacement(BubbleSide.LEFT, 0, 300, size, 1000, 2200, area, gap = 10)
        assertEquals(PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 300), left)
    }

    @Test
    fun panelGrowsUpwardInLowerHalf() {
        val placement = panelPlacement(BubbleSide.LEFT, 0, 1800, size, 1000, 2200, area, gap = 10)
        assertEquals(PanelPlacement(alignRight = false, alignBottom = true, x = 110, y = 300), placement)
    }

    @Test
    fun panelDirectionUsesAreaAboveIme() {
        // Bubble center 1050 is in the upper half of the full area, but the lower half of the area above the IME.
        val noIme = panelPlacement(BubbleSide.LEFT, 0, 1000, size, 1000, 2200, area, gap = 10)
        assertEquals(PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 1000), noIme)

        val aboveIme = area.copy(bottom = 1200)
        val withIme = panelPlacement(BubbleSide.LEFT, 0, 1000, size, 1000, 2200, aboveIme, gap = 10)
        assertEquals(PanelPlacement(alignRight = false, alignBottom = true, x = 110, y = 1100), withIme)
    }
}
