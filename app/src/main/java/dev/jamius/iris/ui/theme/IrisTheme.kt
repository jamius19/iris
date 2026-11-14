/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.jamius.iris.ui.components.PressIndication

private val LocalIrisColors = staticCompositionLocalOf { LightIrisColors }
private val LocalIrisType = staticCompositionLocalOf { DefaultIrisType }

/**
 * The settings UI theme (no Material): colours follow the system dark mode (the activity is recreated on a uiMode
 * change, so the XML window background and logo colours agree), plus the press overlay and text selection colours.
 */
@Composable
fun IrisTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkIrisColors else LightIrisColors
    CompositionLocalProvider(
        LocalIrisColors provides colors,
        LocalIrisType provides DefaultIrisType,
        LocalIndication provides PressIndication(colors.pressOverlay),
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.accent,
            backgroundColor = colors.accent.copy(alpha = 0.25f)
        ),
        content = content
    )
}

object IrisTheme {
    val colors: IrisColors
        @Composable @ReadOnlyComposable get() = LocalIrisColors.current

    val type: IrisType
        @Composable @ReadOnlyComposable get() = LocalIrisType.current
}
