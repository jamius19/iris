/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jamius.iris.ui.theme.IrisTheme

/** The switch itself (visual only; the row is the toggle): 34 x 20 track, 16 dp thumb, animated. */
@Composable
private fun Switch(checked: Boolean) {
    val colors = IrisTheme.colors
    val thumbX by animateDpAsState(if (checked) 16.dp else 2.dp, tween(SWITCH_MS, easing = FastOutSlowInEasing))
    val track by animateColorAsState(
        if (checked) colors.accent else colors.track,
        tween(SWITCH_MS, easing = FastOutSlowInEasing)
    )
    val thumb by animateColorAsState(
        if (checked) colors.switchThumbOn else colors.switchThumbOff,
        tween(SWITCH_MS, easing = FastOutSlowInEasing)
    )
    Canvas(Modifier.size(34.dp, 20.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val radius = 8.dp.toPx()
        drawCircle(thumb, radius, Offset(thumbX.toPx() + radius, size.height / 2))
    }
}

/**
 * A row that toggles a setting: title + subtitle, switch at the end. Saves at once through [saver]; a failed save
 * shows under the subtitle. With [icon] (a switch card like AI Cleanup) it gets the nav rows' icon column and
 * subtitle style.
 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onSave: suspend (Boolean) -> Unit,
    saver: Saver,
    @DrawableRes icon: Int? = null,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val saveState = rememberSaveState(saver)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, interactionSource = null, indication = null, role = Role.Switch) {
                focusManager.clearFocus()
                haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                saveState.save { onSave(it) }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val texts: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(if (icon != null) 3.dp else 4.dp)) {
                Text(title, type.title)
                Text(subtitle, if (icon != null) type.subtitle else type.helper, color = colors.muted)
                saveState.error?.let { Text(it, type.helper, color = colors.failure) }
            }
        }
        if (icon != null) {
            IconRow(icon, Modifier.weight(1f)) { texts() }
        } else {
            Column(Modifier.weight(1f)) { texts() }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked)
    }
}

private const val SWITCH_MS = 150
