/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The settings UI palette. Light is the Figma design's, exactly; dark is derived from it (the design has no dark
 * mode). Logo colours live in `res/values(-night)/colors.xml` (`logo_body`, `logo_capsule`), since the logo is a
 * VectorDrawable.
 *
 * | Token | Light (Figma) | Dark (derived) | Used for |
 * |---|---|---|---|
 * | background | #E2E7E7 | #141616 | screen + windowBackground |
 * | card | #FFFFFF | #212424 | cards |
 * | text | #2F3031 | #E8EAEA | titles, labels, headings, typed text, card icons |
 * | muted | #6F7173 | #9B9EA0 | subtitles, helper, placeholders, footer |
 * | failure | #EC1664 | #FF5C93 | "Disabled", errors, trash icons, "Not set", "Falls behind" |
 * | enabled | #0061F3 | #5C9DFF | "Enabled" status, "Keeps up" |
 * | warning | #B45309 | #F59E0B | "Borderline" (Statistics) |
 * | statsCard | #0061F3 | #0061F3 | Statistics summary card, white text (~5.2:1) |
 * | control | #E8EAEE | #0B0C0C | dropdown fields and panels |
 * | controlBorder | none | #3A3D3E | 1 dp outline of controls/panels in dark |
 * | onControl | #2F3031 | #FFFFFF | text + icons on control |
 * | onControlMuted | #6F7173 | #8E9193 | placeholder on control |
 * | button | #2F3031 | #0B0C0C | small dark buttons |
 * | buttonText | #FDFDFD | #FDFDFD | small dark button text + icon |
 * | setupButton | #2F3031 | #FFFFFF | Home's "Setup →" buttons (no outline in dark) |
 * | setupButtonText | #FDFDFD | #2F3031 | Setup text + icon |
 * | fieldBorder | #D7D7D7 | #3D4142 | outlined text field border |
 * | fieldBorderFocused | #2F3031 | #E8EAEA | focused field border |
 * | servicesDivider | #D6D8DD | #343738 | 1 dp divider in the Home Services card |
 * | separator | #D8DBDF | #303334 | 0.5 dp separator in settings cards |
 * | track | #C6C8CC | #4A4E50 | slider inactive track, switch off track |
 * | accent | #2F3031 | #E8EAEA | slider active track + thumb, switch on track |
 * | switchThumbOn | #FFFFFF | #141616 | switch thumb when on |
 * | switchThumbOff | #FFFFFF | #C9CCCD | switch thumb when off |
 * | cardShadow | black 7 % | black 35 % | card shadow (y 3, blur 3.1) |
 * | buttonShadow | black 8 % | black 35 % | small button shadow (y 2, blur 0.95) |
 * | panelShadow | black 16 % | black 50 % | dropdown panel shadow (y 4, blur 12) |
 * | pressOverlay | black 6 % | white 6 % | press feedback on cards/rows |
 * | pressOverlayOnControl | black 6 % | white 10 % | press feedback on dropdowns |
 * | pressOverlayOnButton | white 10 % | white 10 % | press feedback on small dark buttons |
 * | pressOverlayOnSetupButton | white 10 % | black 6 % | press feedback on Setup |
 *
 * Dark derivation: a cool dark grey page (#141616 keeps the light background's slight green-cyan tint) with cards
 * one step lighter and the same text/muted hierarchy. Dropdowns are black in dark (#0B0C0C, darker than the card,
 * plus a 1 dp outline so the edge reads); in light they are light grey (#E8EAEE). The slider and switch "on" colour
 * is [accent] (the text colour), not [control]: a black thumb would vanish on a dark card. Failure and enabled are
 * lightened to keep >= 4.5:1 on the dark card at 11-12 sp (#EC1664 ~3.7:1 -> #FF5C93 ~5.4:1; #0061F3 ~2.8:1 ->
 * #5C9DFF ~5.8:1). Warning (not in the design) is an amber with the same contrast: #B45309 ~5:1 on white, #F59E0B
 * ~7:1 on the dark card.
 */
@Immutable
data class IrisColors(
    val background: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val failure: Color,
    val enabled: Color,
    val warning: Color,
    val statsCard: Color,
    val control: Color,
    /** [Color.Unspecified] = no outline (light). */
    val controlBorder: Color,
    val onControl: Color,
    val onControlMuted: Color,
    val button: Color,
    val buttonText: Color,
    val setupButton: Color,
    val setupButtonText: Color,
    val fieldBorder: Color,
    val fieldBorderFocused: Color,
    val servicesDivider: Color,
    val separator: Color,
    val track: Color,
    val accent: Color,
    val switchThumbOn: Color,
    val switchThumbOff: Color,
    val cardShadow: Color,
    val buttonShadow: Color,
    val panelShadow: Color,
    val pressOverlay: Color,
    val pressOverlayOnControl: Color,
    val pressOverlayOnButton: Color,
    val pressOverlayOnSetupButton: Color,
)

val LightIrisColors = IrisColors(
    background = Color(0xFFE2E7E7),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF2F3031),
    muted = Color(0xFF6F7173),
    failure = Color(0xFFEC1664),
    enabled = Color(0xFF0061F3),
    warning = Color(0xFFB45309),
    statsCard = Color(0xFF0061F3),
    control = Color(0xFFE8EAEE),
    controlBorder = Color.Unspecified,
    onControl = Color(0xFF2F3031),
    onControlMuted = Color(0xFF6F7173),
    button = Color(0xFF2F3031),
    buttonText = Color(0xFFFDFDFD),
    setupButton = Color(0xFF2F3031),
    setupButtonText = Color(0xFFFDFDFD),
    fieldBorder = Color(0xFFD7D7D7),
    fieldBorderFocused = Color(0xFF2F3031),
    servicesDivider = Color(0xFFD6D8DD),
    separator = Color(0xFFD8DBDF),
    track = Color(0xFFC6C8CC),
    accent = Color(0xFF2F3031),
    switchThumbOn = Color(0xFFFFFFFF),
    switchThumbOff = Color(0xFFFFFFFF),
    cardShadow = Color.Black.copy(alpha = 0.07f),
    buttonShadow = Color.Black.copy(alpha = 0.08f),
    panelShadow = Color.Black.copy(alpha = 0.16f),
    pressOverlay = Color.Black.copy(alpha = 0.06f),
    pressOverlayOnControl = Color.Black.copy(alpha = 0.06f),
    pressOverlayOnButton = Color.White.copy(alpha = 0.10f),
    pressOverlayOnSetupButton = Color.White.copy(alpha = 0.10f),
)

val DarkIrisColors = IrisColors(
    background = Color(0xFF141616),
    card = Color(0xFF212424),
    text = Color(0xFFE8EAEA),
    muted = Color(0xFF9B9EA0),
    failure = Color(0xFFFF5C93),
    enabled = Color(0xFF5C9DFF),
    warning = Color(0xFFF59E0B),
    statsCard = Color(0xFF0061F3),
    control = Color(0xFF0B0C0C),
    controlBorder = Color(0xFF3A3D3E),
    onControl = Color(0xFFFFFFFF),
    onControlMuted = Color(0xFF8E9193),
    button = Color(0xFF0B0C0C),
    buttonText = Color(0xFFFDFDFD),
    setupButton = Color(0xFFFFFFFF),
    setupButtonText = Color(0xFF2F3031),
    fieldBorder = Color(0xFF3D4142),
    fieldBorderFocused = Color(0xFFE8EAEA),
    servicesDivider = Color(0xFF343738),
    separator = Color(0xFF303334),
    track = Color(0xFF4A4E50),
    accent = Color(0xFFE8EAEA),
    switchThumbOn = Color(0xFF141616),
    switchThumbOff = Color(0xFFC9CCCD),
    cardShadow = Color.Black.copy(alpha = 0.35f),
    buttonShadow = Color.Black.copy(alpha = 0.35f),
    panelShadow = Color.Black.copy(alpha = 0.50f),
    pressOverlay = Color.White.copy(alpha = 0.06f),
    pressOverlayOnControl = Color.White.copy(alpha = 0.10f),
    pressOverlayOnButton = Color.White.copy(alpha = 0.10f),
    pressOverlayOnSetupButton = Color.Black.copy(alpha = 0.06f),
)
