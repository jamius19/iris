/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.jamius.iris.ui.theme.IrisTheme

/**
 * The design's small dark button ("Setup →"): hugs its text + 11 dp trailing icon (if any). [setup]: Home's Setup
 * buttons, white in dark mode.
 */
@Composable
fun SmallButton(
    text: String,
    @DrawableRes trailingIcon: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    setup: Boolean = false,
) {
    val colors = IrisTheme.colors
    val focusManager = LocalFocusManager.current
    val textColor = if (setup) colors.setupButtonText else colors.buttonText
    val pressOverlay = if (setup) colors.pressOverlayOnSetupButton else colors.pressOverlayOnButton
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .dropShadow(
                ControlShape,
                Shadow(radius = 0.95.dp, color = colors.buttonShadow, offset = DpOffset(0.dp, 2.dp))
            )
            .clip(ControlShape)
            .background(if (setup) colors.setupButton else colors.button)
            .then(if (setup) Modifier else Modifier.controlBorder(colors))
            .clickable(
                interactionSource = null,
                indication = PressIndication(pressOverlay),
                role = Role.Button
            ) {
                focusManager.clearFocus()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, IrisTheme.type.button, color = textColor)
        trailingIcon?.let { Icon(it, contentDescription = null, tint = textColor) }
    }
}

/** A destructive action: [SmallButton]'s shape and padding, no fill, failure-coloured outline and text. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = IrisTheme.colors
    val focusManager = LocalFocusManager.current
    Box(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(ControlShape)
            .border(1.dp, colors.failure, ControlShape)
            .clickable(role = Role.Button) {
                focusManager.clearFocus()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, IrisTheme.type.button, color = colors.failure)
    }
}

/**
 * An icon button: a [width] x [ControlHeight] touch box (rounded 8, press overlay) with the icon centred. Clears focus
 * before acting (a non-text control, so a focused field commits first). Disabled: 38 % alpha, not clickable.
 */
@Composable
fun IconAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 32.dp,
    iconSize: Dp? = null,
    iconModifier: Modifier = Modifier,
    indication: Indication = LocalIndication.current,
    clearFocus: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    // Alpha first, so a border passed in [modifier] dims too.
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(modifier)
            .size(width, ControlHeight)
            .clip(ControlShape)
            .clickable(interactionSource = null, indication = indication, enabled = enabled, role = Role.Button) {
                if (clearFocus) focusManager.clearFocus()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription,
            tint,
            iconModifier.then(if (iconSize != null) Modifier.size(iconSize) else Modifier)
        )
    }
}

const val DISABLED_ALPHA = 0.38f
