/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.jamius.iris.R

/** Inter (variable, `res/font/inter.ttf`): one instance per weight the design uses; optical size left at 14. */
@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(
    listOf(400, 500, 600, 800).map {
        Font(
            R.font.inter,
            FontWeight(it),
            variationSettings = FontVariation.Settings(FontVariation.weight(it))
        )
    }
)

/** Blinker SemiBold: page titles and section headings only. */
val Blinker = FontFamily(Font(R.font.blinker_semibold, FontWeight.SemiBold))

/** The settings UI type scale (Figma sizes; line heights from the design's text box heights). */
@Immutable
data class IrisType(
    val pageTitle: TextStyle,
    val sectionHeading: TextStyle,
    /** Row titles and field labels. */
    val title: TextStyle,
    /** Nav/status/switch-card subtitles. */
    val subtitle: TextStyle,
    /** Button text and dropdown values. */
    val button: TextStyle,
    /** Text field content and placeholders. */
    val field: TextStyle,
    /** JSON text. */
    val mono: TextStyle,
    val helper: TextStyle,
    val sliderValue: TextStyle,
    val menuItem: TextStyle,
    val menuItemSelected: TextStyle,
    val footer: TextStyle,
)

private fun style(family: FontFamily, weight: Int, size: TextUnit, lineHeight: TextUnit) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
)

val DefaultIrisType = IrisType(
    pageTitle = style(Blinker, 600, 20.sp, 24.sp),
    sectionHeading = style(Blinker, 600, 15.sp, 18.sp),
    title = style(Inter, 600, 14.sp, 17.sp),
    subtitle = style(Inter, 500, 12.sp, 15.sp),
    button = style(Inter, 500, 12.sp, 15.sp),
    field = style(Inter, 400, 12.sp, 15.sp),
    mono = style(FontFamily.Monospace, 400, 12.sp, 15.sp),
    helper = style(Inter, 500, 11.sp, 13.sp),
    sliderValue = style(Inter, 800, 12.sp, 15.sp),
    menuItem = style(Inter, 400, 12.sp, 15.sp),
    menuItemSelected = style(Inter, 500, 12.sp, 15.sp),
    footer = style(Inter, 500, 12.sp, 15.sp).copy(textAlign = TextAlign.Center),
)
