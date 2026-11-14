/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.jamius.iris.R
import dev.jamius.iris.ui.theme.IrisTheme
import kotlinx.coroutines.flow.drop
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The black control box (dropdowns): 32 dp min height, radius 8, dark-mode outline (failure colour with an error),
 * trailing icons in 28 dp boxes.
 */
@Composable
private fun ControlShell(modifier: Modifier, hasError: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val colors = IrisTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ControlHeight)
            .clip(ControlShape)
            .background(colors.control)
            .then(if (hasError) Modifier.border(1.dp, colors.failure, ControlShape) else Modifier.controlBorder(colors))
            .then(modifier)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** The arrow-down toggle's turn: 180° while open (200 ms). */
@Composable
private fun arrowRotation(open: Boolean): Float = animateFloatAsState(if (open) 180f else 0f, tween(200)).value

/** Where the dropdown's anchor sits in the window (not clipped by the scroll viewport). */
private fun Modifier.trackAnchor(onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onBounds(Rect(it.positionInWindow(), it.size.toSize())) }

/**
 * The black dropdown panel in a [Popup], as wide as [anchor] (window pixels), 4 dp below it, or above it when the
 * IME / nav bar leaves more room there; height capped at 280 dp and by that room. Fades and grows in (120 ms) from
 * the anchor side, fades out (90 ms); the popup is removed after the exit.
 */
@Composable
private fun DropdownPanel(
    expanded: Boolean,
    anchor: Rect?,
    focusable: Boolean,
    onDismiss: () -> Unit,
    itemCount: Int,
    initialIndex: Int,
    content: LazyListScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    if (anchor == null || (!visibleState.currentState && !visibleState.targetState)) return
    val colors = IrisTheme.colors
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val margin = with(density) { 8.dp.roundToPx() }
    val minTop = WindowInsets.statusBars.getTop(density) + margin
    val maxBottom = windowHeight - margin -
        max(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
    val contentHeight = with(density) {
        (PANEL_ITEM_HEIGHT * itemCount + 8.dp).coerceAtMost(PANEL_MAX_HEIGHT).roundToPx()
    }
    val placement = dropdownPlacement(
        anchor.top.roundToInt(),
        anchor.bottom.roundToInt(),
        minTop,
        maxBottom,
        contentHeight,
        with(density) { 4.dp.roundToPx() }
    )
    val shadowRoom = with(density) { PANEL_SHADOW_ROOM.roundToPx() }
    val provider = remember(placement, shadowRoom) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(
                anchorBounds.left - shadowRoom,
                if (placement.above) placement.y - shadowRoom else placement.y
            )
        }
    }
    Popup(provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = focusable)) {
        val transition = rememberTransition(visibleState)
        val alpha by transition.animateFloat({ tween(if (targetState) PANEL_IN_MS else PANEL_OUT_MS) }) {
            if (it) 1f else 0f
        }
        val scale by transition.animateFloat({ tween(PANEL_IN_MS, easing = FastOutSlowInEasing) }) {
            if (it) 1f else 0.96f
        }
        // No shadow room on the anchor side: there the popup window would cover the anchor and swallow its taps.
        Box(
            Modifier.padding(
                start = PANEL_SHADOW_ROOM,
                end = PANEL_SHADOW_ROOM,
                top = if (placement.above) PANEL_SHADOW_ROOM else 0.dp,
                bottom = if (placement.above) 0.dp else PANEL_SHADOW_ROOM
            )
        ) {
            LazyColumn(
                state = rememberLazyListState((initialIndex - 2).coerceAtLeast(0)),
                modifier = Modifier
                    .width(with(density) { anchor.width.toDp() })
                    .height(with(density) { placement.maxHeight.toDp() })
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, if (placement.above) 1f else 0f)
                    }
                    .dropShadow(
                        ControlShape,
                        Shadow(radius = 12.dp, color = colors.panelShadow, offset = DpOffset(0.dp, 4.dp))
                    )
                    .clip(ControlShape)
                    .background(colors.control)
                    .controlBorder(colors),
                contentPadding = PaddingValues(vertical = 4.dp),
                content = content
            )
        }
    }
}

/**
 * One panel row: [leading] (if any), text (medium when selected) with [badge] (if any) right after it, then the
 * "Streams" radio icon and the selected check.
 */
@Composable
private fun PanelItem(
    text: String,
    selected: Boolean,
    streams: Boolean,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PANEL_ITEM_HEIGHT)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = PressIndication(colors.pressOverlayOnControl),
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        LabelWithBadge(Modifier.weight(1f), badge) {
            Text(
                text,
                if (selected) type.menuItemSelected else type.menuItem,
                it,
                color = colors.onControl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (streams) Icon(R.drawable.lucide_radio, "Streams", colors.onControl.copy(alpha = 0.7f))
        if (selected) Icon(R.drawable.lucide_check, null, colors.onControl)
    }
}

/** [text] (given its modifier) with [badge] (if any) right after it; the text ellipsizes before the badge. */
@Composable
private fun LabelWithBadge(
    modifier: Modifier,
    badge: (@Composable () -> Unit)?,
    text: @Composable (Modifier) -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        text(Modifier.weight(1f, fill = false))
        badge?.invoke()
    }
}

/**
 * Read-only black dropdown (fixed choices): shows [selected]; a pick closes the panel and calls [onSelect] if it
 * changed. [optionIcon] (if any) is drawn before each option's text and [optionBadge] (if any) right after it, in
 * the field and the panel. Options that [streams] get the radio icon (menu rows only). TalkBack: "[label],
 * <value>, Collapsed/Expanded, drop down list".
 */
@Composable
fun <T> SelectDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionIcon: (@Composable (T) -> Unit)? = null,
    optionBadge: (@Composable (T) -> Unit)? = null,
    streams: (T) -> Boolean = { false },
) {
    val colors = IrisTheme.colors
    val focusManager = LocalFocusManager.current
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    // A tap on the field while the panel is open dismisses it (outside tap); it must not reopen it at once.
    var dismissedAt by remember { mutableLongStateOf(0L) }
    Box(modifier.trackAnchor { anchor = it }) {
        ControlShell(
            Modifier
                .clickable(
                    interactionSource = null,
                    indication = PressIndication(colors.pressOverlayOnControl),
                    role = Role.DropdownList,
                    onClickLabel = "Change"
                ) {
                    focusManager.clearFocus()
                    if (!open && SystemClock.uptimeMillis() - dismissedAt < REOPEN_GUARD_MS) return@clickable
                    open = !open
                }
                .semantics {
                    contentDescription = "$label, ${optionLabel(selected)}"
                    stateDescription = if (open) "Expanded" else "Collapsed"
                }
        ) {
            if (optionIcon != null) {
                optionIcon(selected)
                Spacer(Modifier.width(8.dp))
            }
            LabelWithBadge(Modifier.weight(1f), optionBadge?.let { { it(selected) } }) {
                Text(
                    optionLabel(selected),
                    IrisTheme.type.button,
                    it
                        .padding(vertical = 8.dp)
                        .clearAndSetSemantics {},
                    color = colors.onControl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(Modifier.size(28.dp, ControlHeight), contentAlignment = Alignment.Center) {
                Icon(R.drawable.lucide_arrow_down, null, colors.onControl, Modifier.rotate(arrowRotation(open)))
            }
        }
        DropdownPanel(
            expanded = open,
            anchor = anchor,
            focusable = true,
            onDismiss = {
                open = false
                dismissedAt = SystemClock.uptimeMillis()
            },
            itemCount = options.size,
            initialIndex = options.indexOf(selected)
        ) {
            items(options) { option ->
                PanelItem(
                    optionLabel(option),
                    option == selected,
                    streams = streams(option),
                    leading = optionIcon?.let { { it(option) } },
                    badge = optionBadge?.let { { it(option) } }
                ) {
                    open = false
                    if (option != selected) onSelect(option)
                }
            }
        }
    }
}

/** A labelled [SelectDropdown] that saves picks through [saver] (errors in the helper slot). */
@Composable
fun <T> SelectField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSave: suspend (T) -> Unit,
    saver: Saver,
    helper: String? = null,
    error: String? = null,
    spacing: Dp = 8.dp,
    optionIcon: (@Composable (T) -> Unit)? = null,
    optionBadge: (@Composable (T) -> Unit)? = null,
    streams: (T) -> Boolean = { false },
    helperContent: (@Composable () -> Unit)? = null,
) {
    val saveState = rememberSaveState(saver)
    FieldGroup(
        label,
        helper = helper,
        error = saveState.error ?: error,
        spacing = spacing,
        helperContent = helperContent
    ) {
        SelectDropdown(
            label,
            options,
            selected,
            optionLabel,
            onSelect = { saveState.save { onSave(it) } },
            optionIcon = optionIcon,
            optionBadge = optionBadge,
            streams = streams
        )
    }
}

/**
 * Editable black model dropdown: type any id (typing filters [models], case-insensitive), or pick one. Focusing the
 * field opens the full list; the arrow toggles it and refresh ([onLoad], spinning while [loading]) reloads it, both
 * without focusing the field or clearing the filter. The first open loads an unloaded list once. Back and blur close
 * the panel. A pick saves at once; typed text commits on blur / Done like [AutosaveTextField] (blank = error).
 * Models that [streams] get the radio icon.
 */
@Composable
fun ModelCombobox(
    label: String,
    stored: String,
    models: List<String>?,
    loading: Boolean,
    loadError: String?,
    helper: String?,
    onLoad: (() -> Unit)?,
    onSave: suspend (String) -> Unit,
    saver: Saver,
    streams: (String) -> Boolean = { false },
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val focusManager = LocalFocusManager.current
    val state = rememberTextFieldState(stored)
    var lastStored by rememberSaveable { mutableStateOf(stored) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    // What was typed to filter the list; empty shows the whole list.
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var fetchedOnOpen by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableLongStateOf(0L) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val focusRequester = remember { FocusRequester() }
    val saveState = rememberSaveState(saver)
    val filtered = remember(models, query) { filterModels(models, query) }
    val expanded = menuOpen && filtered.isNotEmpty()

    LaunchedEffect(stored) {
        if (stored != lastStored) {
            lastStored = stored
            error = null
            if (!focused) state.setTextAndPlaceCursorAtEnd(stored)
        }
    }
    // Typing (only possible while focused) filters and opens the list, and clears the error.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect {
            error = null
            if (focused) {
                query = it
                menuOpen = true
            }
        }
    }
    // Auto-fetch on the first open. An effect, so a toggle undone in the same event fetches nothing.
    LaunchedEffect(menuOpen) {
        if (menuOpen && models == null && !loading && !fetchedOnOpen && onLoad != null) {
            fetchedOnOpen = true
            onLoad()
        }
    }
    BackHandler(enabled = expanded) { menuOpen = false }

    fun commit() {
        val decision = commitDecision(state.text.toString(), stored) { if (it.isBlank()) BLANK_MODEL else null }
        when (decision) {
            is Commit.Save -> {
                if (state.text.toString() != decision.value) state.setTextAndPlaceCursorAtEnd(decision.value)
                saveState.save { onSave(decision.value) }
            }
            is Commit.Invalid -> error = decision.error
            Commit.None -> if (state.text.toString() != stored) state.setTextAndPlaceCursorAtEnd(stored)
        }
    }

    // The arrow and refresh act on the panel only; a field focused elsewhere commits first.
    fun leaveOtherFields() {
        if (!focused) focusManager.clearFocus()
    }

    FieldGroup(
        label,
        helper = helper,
        error = listOfNotNull(error ?: saveState.error, loadError).joinToString("\n").ifEmpty { null }
    ) {
        Box(Modifier.trackAnchor { anchor = it }) {
            ControlShell(
                Modifier.pointerInput(Unit) { detectTapGestures { focusRequester.requestFocus() } },
                hasError = error != null
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                ) {
                    if (state.text.isEmpty()) Text("Model id", type.button, color = colors.onControlMuted, maxLines = 1)
                    CompositionLocalProvider(
                        LocalTextSelectionColors provides TextSelectionColors(
                            handleColor = Color.White,
                            backgroundColor = Color.White.copy(alpha = 0.35f)
                        )
                    ) {
                        BasicTextField(
                            state = state,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged {
                                    if (it.isFocused && !focused) {
                                        query = ""
                                        menuOpen = true
                                    }
                                    if (focused && !it.isFocused) {
                                        menuOpen = false
                                        commit()
                                    }
                                    focused = it.isFocused
                                }
                                .semantics { contentDescription = label },
                            textStyle = type.button.copy(color = colors.onControl),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done
                            ),
                            onKeyboardAction = { focusManager.clearFocus() },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            cursorBrush = SolidColor(colors.onControl)
                        )
                    }
                }
                IconAction(
                    R.drawable.lucide_arrow_down,
                    contentDescription = if (expanded) "Hide models" else "Show models",
                    tint = colors.onControl,
                    width = 28.dp,
                    indication = PressIndication(colors.pressOverlayOnControl),
                    clearFocus = false,
                    iconModifier = Modifier.rotate(arrowRotation(expanded)),
                    onClick = {
                        leaveOtherFields()
                        if (menuOpen) {
                            menuOpen = false
                        } else if (SystemClock.uptimeMillis() - dismissedAt >= REOPEN_GUARD_MS) {
                            query = ""
                            menuOpen = true
                        }
                    }
                )
                if (onLoad != null) {
                    RefreshAction(loading) {
                        leaveOtherFields()
                        onLoad()
                        menuOpen = true
                    }
                }
            }
            DropdownPanel(
                expanded = expanded,
                anchor = anchor,
                focusable = false,
                onDismiss = {
                    menuOpen = false
                    dismissedAt = SystemClock.uptimeMillis()
                },
                itemCount = filtered.size,
                initialIndex = filtered.indexOf(stored)
            ) {
                items(filtered) { model ->
                    PanelItem(model, model == stored, streams(model)) {
                        state.setTextAndPlaceCursorAtEnd(model)
                        menuOpen = false
                        if (focused) focusManager.clearFocus() else commit()
                    }
                }
            }
        }
    }
}

/** Refresh models: spins (900 ms per turn) and is disabled while [loading]. */
@Composable
private fun RefreshAction(loading: Boolean, onClick: () -> Unit) {
    val colors = IrisTheme.colors
    val angle = if (loading) {
        rememberInfiniteTransition().animateFloat(
            0f,
            360f,
            infiniteRepeatable(tween(900, easing = LinearEasing))
        ).value
    } else {
        0f
    }
    IconAction(
        R.drawable.lucide_refresh_cw,
        contentDescription = "Refresh models",
        tint = colors.onControl,
        onClick = onClick,
        modifier = Modifier.semantics { if (loading) stateDescription = "Loading" },
        enabled = !loading,
        width = 28.dp,
        indication = PressIndication(colors.pressOverlayOnControl),
        clearFocus = false,
        iconModifier = Modifier.rotate(angle)
    )
}

private val PANEL_ITEM_HEIGHT = 40.dp
private val PANEL_MAX_HEIGHT = 288.dp
private val PANEL_SHADOW_ROOM = 16.dp
private const val PANEL_IN_MS = 120
private const val PANEL_OUT_MS = 90
private const val REOPEN_GUARD_MS = 400L
private const val BLANK_MODEL = "Enter a model id."
