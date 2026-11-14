/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamius.iris.R
import dev.jamius.iris.ui.theme.IrisTheme

/** A Home card that opens a page: icon, title, subtitle; the whole card is the button. */
@Composable
fun NavCard(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Card(verticalPadding = 15.dp, onClick = onClick, onClickLabel = "Open") {
        IconRow(icon) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, IrisTheme.type.title)
                Text(subtitle, IrisTheme.type.subtitle, color = IrisTheme.colors.muted)
            }
        }
    }
}

/**
 * A Services row: icon, title, [enabledText] (blue) / [disabledText] (failure colour); while disabled, [helper] and a
 * "Setup" button ([onSetup]) below.
 */
@Composable
fun StatusRow(
    @DrawableRes icon: Int,
    iconTopPadding: Dp,
    title: String,
    enabled: Boolean,
    helper: String?,
    onSetup: () -> Unit,
    enabledText: String = "Enabled",
    disabledText: String = "Disabled",
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    IconRow(icon, iconTopPadding = iconTopPadding) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, type.title)
                Text(
                    if (enabled) enabledText else disabledText,
                    type.subtitle,
                    color = if (enabled) colors.enabled else colors.failure
                )
            }
            if (!enabled) {
                helper?.let { Text(it, type.helper, color = colors.muted) }
                SmallButton(
                    "Setup",
                    R.drawable.lucide_arrow_right,
                    onSetup,
                    Modifier.semantics { contentDescription = "Set up $title" },
                    setup = true
                )
            }
        }
    }
}
