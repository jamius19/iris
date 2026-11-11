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
 * True if the expanded stack (button + pill) of [stackHeight], hanging down from the button's top [buttonTop], would
 * reach past [visibleBottom] (the IME or the usable area's bottom): then the pill opens upward instead.
 */
internal fun pillOpensUp(buttonTop: Int, stackHeight: Int, visibleBottom: Int): Boolean =
    buttonTop + stackHeight > visibleBottom

/**
 * The live-text panel's max width: [widthPercent] % of the display, but no wider than the usable [area] beside a
 * bubble of [bubbleSize] snapped to either edge, less the [gap], so it stays on screen.
 */
internal fun panelMaxWidth(displayWidth: Int, widthPercent: Int, area: Area, bubbleSize: Int, gap: Int): Int =
    minOf(displayWidth * widthPercent / 100, area.right - area.left - bubbleSize - gap)

/**
 * Puts the panel on the bubble's side facing screen center: left of a right-edge bubble, right of a left-edge one.
 * A one-line panel is as tall as the bubble's circle and lines up with it. Taller panels keep the circle's top edge
 * and grow down, unless [panelMaxHeight] wouldn't fit above the [visible] area's bottom (the IME or the usable area's
 * bottom): then they keep the circle's bottom edge and grow up. If that wouldn't fit below [visible]'s top either,
 * the panel's top sits on [visible]'s top and it grows down. With [growUp] (the pill opens upward) the panel skips
 * growing down: it keeps the circle's bottom edge if it fits, else its top sits on [visible]'s top.
 */
internal fun panelPlacement(
    side: BubbleSide,
    bubbleX: Int,
    bubbleSize: Int,
    circleTop: Int,
    circleSize: Int,
    panelMaxHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    visible: Area,
    gap: Int,
    growUp: Boolean,
): PanelPlacement {
    val alignRight = side == BubbleSide.RIGHT
    val circleBottom = circleTop + circleSize
    val growsDown = !growUp && circleTop + panelMaxHeight <= visible.bottom
    val alignBottom = !growsDown && circleBottom - panelMaxHeight >= visible.top
    return PanelPlacement(
        alignRight = alignRight,
        alignBottom = alignBottom,
        x = if (alignRight) displayWidth - bubbleX + gap else bubbleX + bubbleSize + gap,
        y = when {
            growsDown -> circleTop
            alignBottom -> displayHeight - circleBottom
            else -> visible.top
        },
    )
}
