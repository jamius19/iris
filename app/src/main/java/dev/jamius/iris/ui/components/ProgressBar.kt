/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamius.iris.ui.theme.IrisTheme

/** A 3 dp progress line like the slider's track: [fraction] (0..1) filled in the accent colour. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = IrisTheme.colors
    val value = fraction.coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }
    ) {
        val stroke = size.height
        val cy = size.height / 2
        val start = stroke / 2
        val end = size.width - stroke / 2
        drawLine(colors.track, Offset(start, cy), Offset(end, cy), stroke, StrokeCap.Round)
        if (value > 0f) {
            val filled = start + (end - start) * value
            drawLine(colors.accent, Offset(start, cy), Offset(filled, cy), stroke, StrokeCap.Round)
        }
    }
}
