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

    // Bubble view of `size` at y = 300 with an 80 px circle inset by 10; two-line panel height 130.
    private fun placement(
        side: BubbleSide = BubbleSide.LEFT,
        bubbleY: Int = 300,
        visible: Area = area,
        panelMaxHeight: Int = 130,
        growUp: Boolean = false,
    ) = panelPlacement(
        side = side,
        bubbleX = if (side == BubbleSide.LEFT) 0 else 900,
        bubbleSize = size,
        circleTop = bubbleY + 10,
        circleSize = 80,
        panelMaxHeight = panelMaxHeight,
        displayWidth = 1000,
        displayHeight = 2200,
        visible = visible,
        gap = 10,
        growUp = growUp,
    )

    @Test
    fun panelFacesScreenCenterAndLinesUpWithCircleTop() {
        // A one-line panel is as tall as the circle, so top-aligning it lines it up with the circle.
        assertEquals(
            PanelPlacement(alignRight = true, alignBottom = false, x = 110, y = 310),
            placement(BubbleSide.RIGHT)
        )
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 310),
            placement(BubbleSide.LEFT)
        )
    }

    @Test
    fun panelGrowsDownWhileTwoLinesFitAboveAreaBottom() {
        // Circle top 1960 + 130 = 2090 fits above the area bottom 2100.
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 1960),
            placement(bubbleY = 1950)
        )
    }

    @Test
    fun panelGrowsDownWhenItExactlyReachesAreaBottom() {
        // Circle top 1970 + 130 = 2100 is exactly the area bottom.
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 1970),
            placement(bubbleY = 1960)
        )
    }

    @Test
    fun noticePanelUsesItsFourLineHeight() {
        // Circle top 1910: two lines (130) fit below, four lines (250) don't, so a notice grows up (bottom 1990).
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 1910),
            placement(bubbleY = 1900)
        )
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = true, x = 110, y = 210),
            placement(bubbleY = 1900, panelMaxHeight = 250)
        )
    }

    @Test
    fun panelTopClampsToVisibleTopWhenNeitherDirectionFits() {
        // Circle 110..190, panel 130: down reaches 240 past the IME at 220, up starts at 60 above the top 100.
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 100),
            placement(bubbleY = 100, visible = area.copy(bottom = 220))
        )
    }

    @Test
    fun panelGrowsUpNearAreaBottom() {
        // Circle top 1990 + 130 overflows 2100: bottom-aligned with the circle (bottom 2070, 130 above display bottom).
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = true, x = 110, y = 130),
            placement(bubbleY = 1980)
        )
    }

    @Test
    fun panelGrowsUpAboveIme() {
        val aboveIme = area.copy(bottom = 1200)
        // Circle top 1060 + 130 = 1190 still fits above the IME at 1200.
        val belowFits = placement(bubbleY = 1050, visible = aboveIme)
        assertEquals(PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 1060), belowFits)
        // Circle top 1110 + 130 would cover the IME at 1200: bottom-aligned with the circle (bottom 1190).
        val nearIme = placement(bubbleY = 1100, visible = aboveIme)
        assertEquals(PanelPlacement(alignRight = false, alignBottom = true, x = 110, y = 1010), nearIme)
    }

    @Test
    fun panelGrowsUpWhileThePillOpensUpEvenIfItFitsBelow() {
        // Circle 310..390: bottom-aligned (390 - 130 = 260, 1810 above display bottom), not top-aligned at 310.
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = true, x = 110, y = 1810),
            placement(growUp = true)
        )
    }

    @Test
    fun panelTopClampsToVisibleTopWhenThePillOpensUpAndUpDoesNotFit() {
        // Circle 160..240, panel 130: up starts at 110 above the top 150, so its top sits on 150 (down would fit).
        assertEquals(
            PanelPlacement(alignRight = false, alignBottom = false, x = 110, y = 150),
            placement(bubbleY = 150, visible = area.copy(top = 150), growUp = true)
        )
    }

    @Test
    fun pillOpensUpOnlyWhenTheStackWouldPassTheVisibleBottom() {
        assertFalse(pillOpensUp(buttonTop = 1000, stackHeight = 300, visibleBottom = 2100))
        assertFalse(pillOpensUp(buttonTop = 1800, stackHeight = 300, visibleBottom = 2100))
        assertTrue(pillOpensUp(buttonTop = 1801, stackHeight = 300, visibleBottom = 2100))
    }

    @Test
    fun panelMaxWidthIsThePercentOfTheDisplay() {
        assertEquals(500, panelMaxWidth(1000, 50, area, size, gap = 10))
        assertEquals(300, panelMaxWidth(1000, 30, area, size, gap = 10))
    }

    @Test
    fun panelMaxWidthStaysBesideTheBubble() {
        assertEquals(890, panelMaxWidth(1000, 90, area, size, gap = 10))
        assertEquals(790, panelMaxWidth(1000, 90, area.copy(left = 50, right = 950), size, gap = 10))
    }
}
