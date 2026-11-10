/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextInsertionTest {

    @Test
    fun emptyFieldGetsTextWithoutLeadingSpace() {
        // A field showing hint text is treated as empty by the caller; its selection is usually 0..0.
        assertEquals(MergedText("Hello", 5, "Hello"), mergeInsertion("", 0, 0, "Hello"))
    }

    @Test
    fun cursorAtEndAfterWordAddsLeadingSpace() {
        assertEquals(MergedText("Hi there", 8, " there"), mergeInsertion("Hi", 2, 2, "there"))
    }

    @Test
    fun cursorAfterWhitespaceAddsNoSpace() {
        assertEquals(MergedText("Hi there", 8, "there"), mergeInsertion("Hi ", 3, 3, "there"))
        assertEquals(MergedText("Hi\nthere", 8, "there"), mergeInsertion("Hi\n", 3, 3, "there"))
    }

    @Test
    fun cursorInMiddleInsertsThere() {
        assertEquals(MergedText("Hello big world", 10, "big "), mergeInsertion("Hello world", 6, 6, "big"))
        assertEquals(MergedText("Hello big world", 9, " big"), mergeInsertion("Hello world", 5, 5, "big"))
    }

    @Test
    fun cursorBetweenWordsAddsBothSpaces() {
        assertEquals(MergedText("Hello big world", 10, " big "), mergeInsertion("Helloworld", 5, 5, "big"))
    }

    @Test
    fun cursorAtStartAddsNoLeadingSpace() {
        assertEquals(MergedText("Oh world", 2, "Oh"), mergeInsertion(" world", 0, 0, "Oh"))
    }

    @Test
    fun cursorBeforeWordAddsTrailingSpace() {
        assertEquals(MergedText("Oh world", 3, "Oh "), mergeInsertion("world", 0, 0, "Oh"))
    }

    @Test
    fun noLeadingSpaceAfterOpeningBracketOrQuote() {
        assertEquals(MergedText("(hi", 3, "hi"), mergeInsertion("(", 1, 1, "hi"))
        assertEquals(MergedText("[hi", 3, "hi"), mergeInsertion("[", 1, 1, "hi"))
        assertEquals(MergedText("{hi", 3, "hi"), mergeInsertion("{", 1, 1, "hi"))
        assertEquals(MergedText("said \"hi", 8, "hi"), mergeInsertion("said \"", 6, 6, "hi"))
        assertEquals(MergedText("said 'hi", 8, "hi"), mergeInsertion("said '", 6, 6, "hi"))
        assertEquals(MergedText("said “hi", 8, "hi"), mergeInsertion("said “", 6, 6, "hi"))
    }

    @Test
    fun selectionIsReplaced() {
        assertEquals(MergedText("Hello there", 11, "there"), mergeInsertion("Hello world", 6, 11, "there"))
    }

    @Test
    fun reversedSelectionIsReplaced() {
        assertEquals(MergedText("Hello there", 11, "there"), mergeInsertion("Hello world", 11, 6, "there"))
    }

    @Test
    fun selectionAfterWordGetsLeadingSpace() {
        assertEquals(MergedText("Hello there", 11, " there"), mergeInsertion("Helloworld", 5, 10, "there"))
    }

    @Test
    fun invalidSelectionAppendsAtEnd() {
        assertEquals(MergedText("Hi there", 8, " there"), mergeInsertion("Hi", -1, -1, "there"))
        assertEquals(MergedText("Hi there", 8, " there"), mergeInsertion("Hi", 0, 9, "there"))
        assertEquals(MergedText("Hi there", 8, " there"), mergeInsertion("Hi", 5, 5, "there"))
    }

    @Test
    fun oneSidedInvalidSelectionAppendsAtEnd() {
        assertEquals(MergedText("Hi there", 8, " there"), mergeInsertion("Hi", -1, 1, "there"))
        assertEquals(MergedText("Hi there", 8, " there"), mergeInsertion("Hi", 1, -1, "there"))
    }

    @Test
    fun insertedIntoFocusedFieldHasNoNotice() {
        assertNull(doneNotice(cleanupError = null, targetFocused = true, insertion = Insertion.INSERTED))
    }

    @Test
    fun copiedWhenFieldRejectedInsertion() {
        assertEquals("Copied to clipboard", doneNotice(null, targetFocused = true, insertion = Insertion.COPIED))
    }

    @Test
    fun copiedWhenFieldChanged() {
        assertEquals(
            "Copied to clipboard — field changed",
            doneNotice(null, targetFocused = false, insertion = Insertion.COPIED)
        )
    }

    @Test
    fun deliveryFailureIsCopied() {
        assertEquals(
            "Something went wrong\nCopied to clipboard",
            doneNotice(null, targetFocused = false, insertion = null)
        )
    }

    @Test
    fun cleanupFailureNamesTheOriginalText() {
        assertEquals(
            "Cleanup timed out\nInserted the original text",
            doneNotice("Cleanup timed out", targetFocused = true, insertion = Insertion.INSERTED)
        )
        assertEquals(
            "Cleanup timed out\nOriginal copied to clipboard",
            doneNotice("Cleanup timed out", targetFocused = true, insertion = Insertion.COPIED)
        )
        assertEquals(
            "Cleanup timed out\nOriginal copied to clipboard — field changed",
            doneNotice("Cleanup timed out", targetFocused = false, insertion = Insertion.COPIED)
        )
        assertEquals(
            "Cleanup timed out\nSomething went wrong\nOriginal copied to clipboard",
            doneNotice("Cleanup timed out", targetFocused = true, insertion = null)
        )
    }
}
