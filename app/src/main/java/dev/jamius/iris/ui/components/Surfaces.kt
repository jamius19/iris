/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.jamius.iris.ui.theme.IrisTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * White rounded card with the design's soft shadow; items 15 dp apart. With [onClick] the whole card is one button
 * (press overlay, merged semantics). [color] replaces the card colour (e.g. the Statistics summary card).
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 16.dp,
    color: Color = IrisTheme.colors.card,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = IrisTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(
                CardShape,
                Shadow(radius = 3.1.dp, color = colors.cardShadow, offset = DpOffset(0.dp, 3.dp))
            )
            .clip(CardShape)
            .background(color)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 22.dp, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(15.dp),
        content = content
    )
}

/** 0.5 dp line between the items of a settings card. */
@Composable
fun Separator() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(IrisTheme.colors.separator)
    )
}

/** 1 dp line between the Home Services rows. */
@Composable
fun ServicesDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(IrisTheme.colors.servicesDivider)
    )
}

/** A section heading (no inset) over one card. */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text(title, IrisTheme.type.sectionHeading, Modifier.semantics { heading() })
        Card(content = content)
    }
}

/**
 * Icon column (24 dp wide, icon top-padded by [iconTopPadding]) 11 dp before [content], top aligned: the design's
 * nav, status and picker rows.
 */
@Composable
fun IconRow(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    iconTopPadding: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(24.dp).padding(top = iconTopPadding)) {
            Icon(icon, contentDescription = null, tint = IrisTheme.colors.text)
        }
        Column(Modifier.weight(1f), content = content)
    }
}

/**
 * A labelled control: [label] (+ [subtitle] 4 dp below), [content], then the helper slot: [error] (failure colour)
 * above [helper] ([helperColor], muted by default). While it holds focus, the whole group (incl. helper/error) is
 * kept above the IME.
 */
@Composable
fun FieldGroup(
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    helper: String? = null,
    error: String? = null,
    helperColor: Color = Color.Unspecified,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    Column(modifier.bringIntoViewAboveIme(error), verticalArrangement = Arrangement.spacedBy(spacing)) {
        if (subtitle == null) {
            Text(label, type.title)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, type.title)
                Text(subtitle, type.helper, color = colors.muted)
            }
        }
        content()
        if (error != null || helper != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                error?.let { Text(it, type.helper, color = colors.failure) }
                helper?.let { Text(it, type.helper, color = helperColor.takeOrElse { colors.muted }) }
            }
        }
    }
}

/**
 * While something inside holds focus, scrolls the whole element into view once the IME inset stops changing, and
 * again when [contentKey] changes (e.g. an error grows the helper text).
 */
@Composable
fun Modifier.bringIntoViewAboveIme(contentKey: Any? = null): Modifier {
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused, contentKey) {
        if (focused) {
            // collectLatest restarts the wait on every inset change, so this runs after the IME animation ends.
            snapshotFlow { ime.getBottom(density) }.collectLatest {
                delay(IME_SETTLE_MS)
                bringIntoView.bringIntoView()
            }
        }
    }
    return bringIntoViewRequester(bringIntoView).onFocusChanged { focused = it.hasFocus }
}

private const val IME_SETTLE_MS = 100L
