/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

/** A dropdown panel's top [y] and the height it may use, all in window pixels; [above] = it opens upwards. */
data class DropdownPlacement(val y: Int, val maxHeight: Int, val above: Boolean)

/**
 * Places a panel of [contentHeight] [gap] below the anchor if it fits above [maxBottom] (the IME's or nav bar's top
 * minus a margin), else on the side with more room (above down to [minTop]), its height capped to that room.
 */
fun dropdownPlacement(
    anchorTop: Int,
    anchorBottom: Int,
    minTop: Int,
    maxBottom: Int,
    contentHeight: Int,
    gap: Int,
): DropdownPlacement {
    val roomBelow = maxBottom - (anchorBottom + gap)
    val roomAbove = anchorTop - gap - minTop
    if (contentHeight <= roomBelow || roomBelow >= roomAbove) {
        return DropdownPlacement(anchorBottom + gap, contentHeight.coerceAtMost(roomBelow).coerceAtLeast(0), false)
    }
    val height = contentHeight.coerceAtMost(roomAbove).coerceAtLeast(0)
    return DropdownPlacement(anchorTop - gap - height, height, true)
}

/** [models] whose id contains the trimmed [query] (case-insensitive); all of them for a blank query, none if null. */
fun filterModels(models: List<String>?, query: String): List<String> =
    models.orEmpty().filter { it.contains(query.trim(), ignoreCase = true) }
