/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamius.iris.ui.theme.IrisColors
import dev.jamius.iris.ui.theme.IrisTheme

/** Min height of dropdowns, text fields and icon buttons. */
val ControlHeight = 40.dp

/** Buttons, dropdowns and text fields. */
val ControlShape = RoundedCornerShape(8.dp)

/** Cards. */
val CardShape = RoundedCornerShape(15.dp)

/** Text in [style]; the colour defaults to the style's, else the theme's text colour. */
@Composable
fun Text(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolved = color.takeOrElse { style.color.takeOrElse { IrisTheme.colors.text } }
    BasicText(text, modifier, style.copy(color = resolved), overflow = overflow, maxLines = maxLines)
}

/** [Text] with spans (e.g. bold labels). */
@Composable
fun Text(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val resolved = color.takeOrElse { style.color.takeOrElse { IrisTheme.colors.text } }
    BasicText(text, modifier, style.copy(color = resolved))
}

/** A drawable (Lucide icons are drawn white and tinted here); its size comes from the drawable or [modifier]. */
@Composable
fun Icon(@DrawableRes id: Int, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    Image(painterResource(id), contentDescription, modifier, colorFilter = ColorFilter.tint(tint))
}

/**
 * A 24 dp outlined box (radius 4, no fill) around a provider's icon in the black dropdowns; the 30 % on-control
 * outline reads on the black control in both themes.
 */
@Composable
fun ProviderIconBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .border(1.dp, IrisTheme.colors.onControl.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** A provider's brand mark for [ProviderIconBox]: 14 dp, untinted ([Icon] always tints). */
@Composable
fun BrandIcon(@DrawableRes id: Int) {
    Image(painterResource(id), null, Modifier.size(14.dp), contentScale = ContentScale.Fit)
}

/** The 1 dp outline black controls get in dark mode (none in light). */
fun Modifier.controlBorder(colors: IrisColors): Modifier =
    if (colors.controlBorder.isSpecified) border(1.dp, colors.controlBorder, ControlShape) else this
