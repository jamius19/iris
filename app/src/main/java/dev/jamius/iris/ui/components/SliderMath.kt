/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import kotlin.math.roundToInt

/** Where [value] sits in [range], 0..1. */
fun sliderFraction(value: Int, range: IntRange): Float {
    if (range.last == range.first) return 0f
    return ((value - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)
}

/** The value at [fraction] (clamped to 0..1), snapped to `range.first + k * step` and kept within [range]. */
fun sliderValue(fraction: Float, range: IntRange, step: Int): Int {
    val raw = fraction.coerceIn(0f, 1f) * (range.last - range.first)
    return (range.first + (raw / step).roundToInt() * step).coerceIn(range.first, range.last)
}
