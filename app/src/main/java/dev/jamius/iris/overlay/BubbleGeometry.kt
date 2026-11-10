/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import dev.jamius.iris.data.settings.BubbleSide
import kotlin.math.roundToInt

// Pure placement math for the bubble overlay, kept free of Android types so it can be unit tested.
// All values are in display pixels.

/** Rectangle the bubble may occupy (screen minus system bars, cutouts and, where relevant, the IME). */
internal data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Where the live-text panel goes: offsets from the display's right/bottom edge when the flags are set. */
internal data class PanelPlacement(val alignRight: Boolean, val alignBottom: Boolean, val x: Int, val y: Int)

/** True once a pointer has moved further than [touchSlop] from where it went down: a drag, not a tap. */
internal fun isDrag(dx: Float, dy: Float, touchSlop: Int): Boolean = dx * dx + dy * dy > touchSlop * touchSlop

/** Keeps a view of [size] starting at [position] inside [min]..[max]; pins it to [min] if it can't fit. */
internal fun clamp(position: Int, min: Int, max: Int, size: Int): Int =
    position.coerceIn(min, (max - size).coerceAtLeast(min))

internal fun nearestSide(bubbleX: Int, size: Int, area: Area): BubbleSide =
    if (bubbleX + size / 2 < (area.left + area.right) / 2) BubbleSide.LEFT else BubbleSide.RIGHT

internal fun snappedX(side: BubbleSide, size: Int, area: Area): Int =
    if (side == BubbleSide.LEFT) area.left else (area.right - size).coerceAtLeast(area.left)

internal fun yForFraction(fraction: Float, size: Int, area: Area): Int =
    area.top + ((area.bottom - area.top - size).coerceAtLeast(0) * fraction).roundToInt()

internal fun fractionForY(y: Int, size: Int, area: Area): Float {
    val range = area.bottom - area.top - size
    return if (range <= 0) 0f else ((y - area.top).toFloat() / range).coerceIn(0f, 1f)
}

/**
 * Puts the panel on the bubble's side facing screen center: left of a right-edge bubble, right of a left-edge one.
 * It is top-aligned with the bubble in the upper half of the [visible] area (screen above the IME) and
 * bottom-aligned in the lower half, so it grows away from the nearer vertical edge.
 */
internal fun panelPlacement(
    side: BubbleSide,
    bubbleX: Int,
    bubbleY: Int,
    bubbleSize: Int,
    displayWidth: Int,
    displayHeight: Int,
    visible: Area,
    gap: Int,
): PanelPlacement {
    val alignRight = side == BubbleSide.RIGHT
    val alignBottom = bubbleY + bubbleSize / 2 > (visible.top + visible.bottom) / 2
    return PanelPlacement(
        alignRight = alignRight,
        alignBottom = alignBottom,
        x = if (alignRight) displayWidth - bubbleX + gap else bubbleX + bubbleSize + gap,
        y = if (alignBottom) displayHeight - (bubbleY + bubbleSize) else bubbleY,
    )
}
