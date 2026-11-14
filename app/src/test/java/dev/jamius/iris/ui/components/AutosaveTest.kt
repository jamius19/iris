/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AutosaveTest {

    private val noBlank: (String) -> String? = { if (it.isBlank()) "Can't be empty." else null }

    @Test
    fun unchangedDraftIsNone() {
        assertEquals(Commit.None, commitDecision("en", "en") { null })
    }

    @Test
    fun whitespaceOnlyChangeIsNone() {
        assertEquals(Commit.None, commitDecision("  en \n", "en") { null })
    }

    @Test
    fun changedDraftIsSavedTrimmed() {
        assertEquals(Commit.Save("bn"), commitDecision(" bn ", "en") { null })
    }

    @Test
    fun validatorErrorIsInvalid() {
        assertEquals(Commit.Invalid("Bad"), commitDecision("x", "en") { "Bad" })
    }

    @Test
    fun validatorSeesTheTrimmedValue() {
        var seen: String? = null
        commitDecision("  x  ", "en") { seen = it; null }
        assertEquals("x", seen)
    }

    @Test
    fun blankIsSavedWhenAllowed() {
        assertEquals(Commit.Save(""), commitDecision("   ", "en") { null })
    }

    @Test
    fun blankIsInvalidWhenRejected() {
        assertEquals(Commit.Invalid("Can't be empty."), commitDecision("   ", "en", noBlank))
    }
}
