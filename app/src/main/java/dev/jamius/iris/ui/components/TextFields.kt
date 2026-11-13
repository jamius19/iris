/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jamius.iris.R
import dev.jamius.iris.ui.theme.IrisTheme
import kotlinx.coroutines.flow.drop

/**
 * The outlined field box: 1 dp border (focused: text colour, error: failure colour; animated), radius 8, no fill.
 * [field] gets the remaining width; [trailing] icons sit outside the text field, so tapping them never focuses it.
 * Taps on the box's padding focus the field.
 */
@Composable
private fun FieldShell(
    focused: Boolean,
    hasError: Boolean,
    multiLine: Boolean,
    focusRequester: FocusRequester,
    hasTrailing: Boolean,
    field: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = IrisTheme.colors
    val border by animateColorAsState(
        when {
            hasError -> colors.failure
            focused -> colors.fieldBorderFocused
            else -> colors.fieldBorder
        },
        tween(150)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (multiLine) 71.dp else 32.dp)
            .border(1.dp, border, ControlShape)
            .pointerInput(Unit) { detectTapGestures { focusRequester.requestFocus() } }
            .padding(start = 12.dp, end = if (hasTrailing) 6.dp else 12.dp),
        verticalAlignment = if (multiLine) Alignment.Top else Alignment.CenterVertically
    ) {
        field()
        trailing()
    }
}

/** The text field's own box: vertical padding, placeholder while empty. */
@Composable
private fun RowScope.FieldBox(
    state: TextFieldState,
    placeholder: String,
    style: TextStyle,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .padding(vertical = 8.dp)
    ) {
        if (state.text.isEmpty()) Text(placeholder, style, color = IrisTheme.colors.muted, maxLines = 1)
        content()
    }
}

/**
 * An autosaving text field (single line, or a text area with [multiLine]). The draft commits on blur (IME Done
 * clears focus; so do taps elsewhere, page leave and app stop): trimmed and unchanged = nothing, [validate] error =
 * not saved and shown in the helper slot, else [onSave] through [saver]. Stored values never flow into a focused
 * field. [clearable] adds a trash icon that clears the field and saves "" (only where blank is valid).
 */
@Composable
fun AutosaveTextField(
    label: String,
    stored: String,
    onSave: suspend (String) -> Unit,
    saver: Saver,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    subtitle: String? = null,
    helper: String? = null,
    validate: (String) -> String? = { null },
    clearable: Boolean = true,
    multiLine: Boolean = false,
    mono: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    selectAll: Boolean = false,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val focusManager = LocalFocusManager.current
    // Saveable: a draft survives rotation. [selectAll] starts with the whole text selected (cursor at the end else).
    val state = rememberTextFieldState(stored, TextRange(if (selectAll) 0 else stored.length, stored.length))
    var lastStored by rememberSaveable { mutableStateOf(stored) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val saveState = rememberSaveState(saver)

    // A new stored value (a save, or a change elsewhere) replaces the text only while the field isn't focused.
    LaunchedEffect(stored) {
        if (stored != lastStored) {
            lastStored = stored
            error = null
            if (!focused) state.setTextAndPlaceCursorAtEnd(stored)
        }
    }
    // Editing clears the error until the next commit.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect { error = null }
    }

    fun commit() {
        when (val decision = commitDecision(state.text.toString(), stored, validate)) {
            is Commit.Save -> {
                if (state.text.toString() != decision.value) state.setTextAndPlaceCursorAtEnd(decision.value)
                saveState.save { onSave(decision.value) }
            }
            is Commit.Invalid -> error = decision.error
            Commit.None -> if (state.text.toString() != stored) state.setTextAndPlaceCursorAtEnd(stored)
        }
    }

    val style = if (mono) type.mono else type.field
    val showTrash = clearable && (state.text.isNotBlank() || stored.isNotBlank())
    FieldGroup(
        label,
        modifier,
        subtitle = subtitle,
        helper = helper,
        error = error ?: saveState.error
    ) {
        FieldShell(
            focused = focused,
            hasError = error != null,
            multiLine = multiLine,
            focusRequester = focusRequester,
            hasTrailing = showTrash,
            field = {
                FieldBox(state, placeholder, style) {
                    BasicTextField(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged {
                                if (focused && !it.isFocused) commit()
                                focused = it.isFocused
                            }
                            .semantics { contentDescription = label },
                        textStyle = style.copy(color = colors.text),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = if (multiLine) ImeAction.Default else ImeAction.Done
                        ),
                        onKeyboardAction = if (multiLine) null else ({ focusManager.clearFocus() }),
                        lineLimits = if (multiLine) {
                            TextFieldLineLimits.MultiLine(maxHeightInLines = 10)
                        } else {
                            TextFieldLineLimits.SingleLine
                        },
                        cursorBrush = SolidColor(colors.text)
                    )
                }
            },
            trailing = {
                if (showTrash) {
                    IconAction(
                        R.drawable.lucide_trash,
                        contentDescription = "Clear $label",
                        tint = colors.failure,
                        width = 28.dp,
                        clearFocus = false,
                        onClick = {
                            // Cleared first, so the blur commit (or the direct one) saves "".
                            state.clearText()
                            if (focused) focusManager.clearFocus() else commit()
                        }
                    )
                }
            }
        )
    }
}

/**
 * Write-only API key field: always starts empty (plain remember: never saved instance state, never seeded or read
 * back). A trimmed non-blank input commits on blur / Done (cleared once saved); the trash deletes the stored key; the
 * eye shows the input while typing. [unsetHelper] shows in the failure colour (the key blocks its provider).
 */
@OptIn(ExperimentalFoundationApi::class) // undoState: a cleared key must not come back with undo.
@Composable
fun ApiKeyField(
    isSet: Boolean,
    placeholder: String,
    setHelper: String,
    unsetHelper: String,
    onSave: suspend (String) -> Unit,
    onClear: suspend () -> Unit,
    saver: Saver,
) {
    val colors = IrisTheme.colors
    val type = IrisTheme.type
    val focusManager = LocalFocusManager.current
    val state = remember { TextFieldState() }
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun commit() {
        val key = state.text.toString().trim()
        if (key.isEmpty()) return
        saver.save(
            onResult = {
                error = it
                if (it == null) {
                    state.clearText()
                    state.undoState.clearHistory()
                    visible = false
                }
            },
            errorMessage = API_KEY_SAVE_ERROR
        ) { onSave(key) }
    }

    val hasInput = state.text.isNotEmpty()
    FieldGroup(
        LABEL_API_KEY,
        helper = if (isSet) setHelper else unsetHelper,
        error = error,
        helperColor = if (isSet) colors.muted else colors.failure
    ) {
        FieldShell(
            focused = focused,
            hasError = error != null,
            multiLine = false,
            focusRequester = focusRequester,
            hasTrailing = hasInput || isSet,
            field = {
                FieldBox(state, placeholder, type.field) {
                    BasicSecureTextField(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged {
                                if (focused && !it.isFocused) commit()
                                focused = it.isFocused
                            }
                            .semantics { contentDescription = LABEL_API_KEY },
                        textStyle = type.field.copy(color = colors.text),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        onKeyboardAction = { focusManager.clearFocus() },
                        cursorBrush = SolidColor(colors.text),
                        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden
                    )
                }
            },
            trailing = {
                if (hasInput) {
                    IconAction(
                        if (visible) R.drawable.lucide_eye_off else R.drawable.lucide_eye,
                        contentDescription = if (visible) "Hide API key" else "Show API key",
                        tint = colors.text,
                        width = 28.dp,
                        iconSize = 16.dp,
                        clearFocus = false,
                        onClick = { visible = !visible }
                    )
                }
                if (isSet) {
                    IconAction(
                        R.drawable.lucide_trash,
                        contentDescription = "Delete saved API key",
                        tint = colors.failure,
                        width = 28.dp,
                        clearFocus = false,
                        onClick = {
                            // Discarded before the focus is cleared, so the blur commits nothing.
                            state.clearText()
                            state.undoState.clearHistory()
                            focusManager.clearFocus()
                            saver.save({ error = it }, API_KEY_DELETE_ERROR) { onClear() }
                        }
                    )
                }
            }
        )
    }
}

private const val LABEL_API_KEY = "API Key"
