/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The field's text after an insertion, and where the cursor goes (right after the inserted text). [insertion] is
 * the inserted text with any added spaces (what a paste inserts).
 */
internal data class MergedText(val text: String, val cursor: Int, val insertion: String)

/**
 * Replaces the [selectionStart]..[selectionEnd] range of [current] with [insertion] (the cursor, if they are
 * equal). An invalid selection (-1 or out of range) appends at the end. Adds a leading space after a
 * non-whitespace character (except an opening bracket or quote) and a trailing space before one (except a closing
 * bracket or quote, or punctuation).
 */
internal fun mergeInsertion(current: String, selectionStart: Int, selectionEnd: Int, insertion: String): MergedText {
    val valid = isValidSelection(current, selectionStart, selectionEnd)
    // The selection can be reversed (start > end) when it was made backwards.
    val start = if (valid) minOf(selectionStart, selectionEnd) else current.length
    val end = if (valid) maxOf(selectionStart, selectionEnd) else current.length
    val before = current.getOrNull(start - 1)
    val after = current.getOrNull(end)
    val leading = if (before != null && !before.isWhitespace() && before !in OPENERS) " " else ""
    val trailing = if (after != null && !after.isWhitespace() && after !in CLOSERS) " " else ""
    val inserted = leading + insertion + trailing
    return MergedText(
        current.substring(0, start) + inserted + current.substring(end),
        start + inserted.length,
        inserted
    )
}

private fun isValidSelection(current: String, selectionStart: Int, selectionEnd: Int): Boolean =
    selectionStart in 0..current.length && selectionEnd in 0..current.length

private const val OPENERS = "([{\"'“"

// ASCII " and ' are left out: they may be opening quotes.
private const val CLOSERS = ".,!?:;)]}”’…"

/** How [insertText] delivered the text. */
internal enum class Insertion { INSERTED, COPIED }

/**
 * The notice to show instead of the inserted text, or null if it was inserted cleanly. [cleanupError] is set if
 * cleanup failed (the original text was used). [targetFocused]: the field dictated into still had focus (else the
 * text was copied). [insertion] is how the text was delivered, null if delivering threw (the text was copied).
 */
internal fun doneNotice(cleanupError: String?, targetFocused: Boolean, insertion: Insertion?): String? {
    val copied = if (cleanupError == null) "Copied to clipboard" else "Original copied to clipboard"
    val delivery = when {
        insertion == null -> "Something went wrong\n$copied"
        !targetFocused -> "$copied — field changed"
        insertion == Insertion.COPIED -> copied
        cleanupError != null -> "Inserted the original text"
        else -> null
    }
    return listOfNotNull(cleanupError, delivery).joinToString("\n").ifEmpty { null }
}

/**
 * Inserts [text] at the cursor of [node] with `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`. If the field rejects
 * SET_TEXT, puts [text] (spaced as for SET_TEXT) on the clipboard and pastes it. An editable [node] with an invalid
 * selection first gets a paste of [text] as is: its text can't be trusted (WhatsApp's empty box reports its
 * placeholder "Message" as text, with no hint and selection -1..-1), and a paste lands at the app's real cursor; if
 * that paste fails, the steps above follow. A non-editable [node] (the keyboard is open, but the field isn't
 * reported as editable) only gets the paste of [text] as is. Without a live node (null or stale), or if the paste
 * fails too, [text] is left on the clipboard: [Insertion.COPIED].
 */
internal fun insertText(context: Context, node: AccessibilityNodeInfo?, text: String): Insertion {
    if (node != null && node.refresh()) {
        val pasted = if (node.isEditable) {
            val current = fieldText(node)
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (!isValidSelection(current, start, end) && paste(context, node, text)) return Insertion.INSERTED
            val merged = mergeInsertion(current, start, end, text)
            if (setText(node, merged)) return Insertion.INSERTED
            merged.insertion
        } else {
            text
        }
        if (paste(context, node, pasted)) return Insertion.INSERTED
    }
    copyToClipboard(context, text)
    return Insertion.COPIED
}

private fun paste(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
    copyToClipboard(context, text)
    return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
}

/** The field's text, or "" while it shows its hint (some fields report the hint as their text). */
private fun fieldText(node: AccessibilityNodeInfo): String {
    val text = node.text?.toString().orEmpty()
    return if (node.isShowingHintText || text == node.hintText?.toString()) "" else text
}

private fun setText(node: AccessibilityNodeInfo, merged: MergedText): Boolean {
    val textArgs = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged.text)
    }
    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)) return false
    val selectionArgs = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, merged.cursor)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, merged.cursor)
    }
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
    return true
}

internal fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Iris", text))
}
