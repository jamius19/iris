/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamius.iris.ui.theme.IrisColors
import dev.jamius.iris.ui.theme.IrisTheme

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

/** A drawable (Lucide icons are drawn white and tinted here); its size comes from the drawable or [modifier]. */
@Composable
fun Icon(@DrawableRes id: Int, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    Image(painterResource(id), contentDescription, modifier, colorFilter = ColorFilter.tint(tint))
}

/** The 1 dp outline black controls get in dark mode (none in light). */
fun Modifier.controlBorder(colors: IrisColors): Modifier =
    if (colors.controlBorder.isSpecified) border(1.dp, colors.controlBorder, ControlShape) else this
