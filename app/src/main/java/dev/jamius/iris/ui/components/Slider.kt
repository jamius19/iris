/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.jamius.iris.ui.theme.IrisTheme

/**
 * Integer slider over [range] in [step]s (the design's 3 dp track and 7 x 24 pill thumb) with its value text at the
 * end. Taps and horizontal drags move it (a vertical drag scrolls the page); [onRelease] runs when the finger lifts
 * (or a key is released, or TalkBack sets it) with a changed value. Ticks a haptic on every step.
 */
@Composable
private fun Slider(
    value: Int,
    range: IntRange,
    step: Int,
    valueText: (Int) -> String,
    label: String,
    onRelease: (Int) -> Unit,
) {
    val colors = IrisTheme.colors
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Follows the stored value (saves, other changes); moves locally while dragged.
    var position by remember(value) { mutableIntStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val thumbWidth by animateDpAsState(if (dragging || focused) 9.dp else 7.dp, tween(150))
    val stored by rememberUpdatedState(value)
    val release by rememberUpdatedState(onRelease)

    fun moveTo(newValue: Int) {
        if (newValue == position) return
        position = newValue
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    fun commit() {
        if (position != stored) release(position)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                // Keyed on value too: a save gives `position` a new state object, which the gesture must use.
                .pointerInput(range, step, rtl, value) {
                    val thumbHalf = 3.5.dp.toPx()
                    fun valueAt(x: Float): Int {
                        val fraction = (x - thumbHalf) / (size.width - 2 * thumbHalf)
                        return sliderValue(if (rtl) 1f - fraction else fraction, range, step)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (drag != null) {
                            focusManager.clearFocus()
                            dragging = true
                            moveTo(valueAt(drag.position.x))
                            horizontalDrag(drag.id) {
                                moveTo(valueAt(it.position.x))
                                it.consume()
                            }
                            dragging = false
                            commit()
                        } else {
                            // A tap (lifted within the touch slop) jumps there; a vertical drag belongs to the page.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && !up.pressed &&
                                (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                            ) {
                                focusManager.clearFocus()
                                moveTo(valueAt(up.position.x))
                                commit()
                            }
                        }
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent {
                    val delta = when (it.key) {
                        Key.DirectionLeft -> if (rtl) step else -step
                        Key.DirectionRight -> if (rtl) -step else step
                        else -> return@onKeyEvent false
                    }
                    if (it.type == KeyEventType.KeyDown) {
                        moveTo((position + delta).coerceIn(range.first, range.last))
                    } else if (it.type == KeyEventType.KeyUp) {
                        commit()
                    }
                    true
                }
                .semantics {
                    contentDescription = label
                    stateDescription = valueText(position)
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        position.toFloat(),
                        range.first.toFloat()..range.last.toFloat(),
                        steps = (range.last - range.first) / step - 1
                    )
                    setProgress { target ->
                        val fraction = (target - range.first) / (range.last - range.first)
                        moveTo(sliderValue(fraction, range, step))
                        commit()
                        true
                    }
                }
                .focusable()
        ) {
            val stroke = 3.dp.toPx()
            val cy = size.height / 2
            val start = stroke / 2
            val end = size.width - stroke / 2
            val thumbHalf = 3.5.dp.toPx()
            val fraction = sliderFraction(position, range)
            val travel = thumbHalf + (size.width - 2 * thumbHalf) * fraction
            val thumbX = if (rtl) size.width - travel else travel
            drawLine(colors.track, Offset(start, cy), Offset(end, cy), stroke, StrokeCap.Round)
            drawLine(colors.accent, Offset(if (rtl) end else start, cy), Offset(thumbX, cy), stroke, StrokeCap.Round)
            val width = thumbWidth.toPx()
            drawRoundRect(
                colors.accent,
                topLeft = Offset(thumbX - width / 2, 0f),
                size = Size(width, size.height),
                cornerRadius = CornerRadius(width / 2)
            )
        }
        Text(
            valueText(position),
            IrisTheme.type.sliderValue.copy(textAlign = TextAlign.End),
            Modifier
                .widthIn(min = 32.dp)
                .clearAndSetSemantics {},
        )
    }
}

/** A labelled [Slider] (label, subtitle, slider, helper); saves on release through [saver], errors in the helper. */
@Composable
fun SliderGroup(
    label: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    step: Int,
    valueText: (Int) -> String,
    onSave: suspend (Int) -> Unit,
    saver: Saver,
    helper: String? = null,
) {
    val saveState = rememberSaveState(saver)
    FieldGroup(label, subtitle = subtitle, helper = helper, error = saveState.error) {
        Slider(value, range, step, valueText, label) { saveState.save { onSave(it) } }
    }
}
