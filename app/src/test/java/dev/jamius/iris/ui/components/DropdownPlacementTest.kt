/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DropdownPlacementTest {

    @Test
    fun fitsBelow() {
        assertEquals(
            DropdownPlacement(y = 210, maxHeight = 300, above = false),
            dropdownPlacement(anchorTop = 100, anchorBottom = 200, minTop = 50, maxBottom = 2000, 300, gap = 10)
        )
    }

    @Test
    fun moreRoomAboveOpensAboveWithCappedHeight() {
        // Below: 1800 - 1710 = 90; above: 1590 - 50 = 1540.
        assertEquals(
            DropdownPlacement(y = 1290, maxHeight = 300, above = true),
            dropdownPlacement(anchorTop = 1600, anchorBottom = 1700, minTop = 50, maxBottom = 1800, 300, gap = 10)
        )
        assertEquals(
            DropdownPlacement(y = 50, maxHeight = 240, above = true),
            dropdownPlacement(anchorTop = 300, anchorBottom = 400, minTop = 50, maxBottom = 500, 1000, gap = 10)
        )
    }

    @Test
    fun neitherFitsTakesTheLargerSideCapped() {
        // Below: 900 - 510 = 390; above: 390 - 100 = 290.
        assertEquals(
            DropdownPlacement(y = 510, maxHeight = 390, above = false),
            dropdownPlacement(anchorTop = 400, anchorBottom = 500, minTop = 100, maxBottom = 900, 1000, gap = 10)
        )
    }

    @Test
    fun imeRaisesTheBottomLimit() {
        val noIme = dropdownPlacement(anchorTop = 1000, anchorBottom = 1100, minTop = 50, maxBottom = 2300, 280, 10)
        val withIme = dropdownPlacement(anchorTop = 1000, anchorBottom = 1100, minTop = 50, maxBottom = 1300, 280, 10)
        assertEquals(DropdownPlacement(1110, 280, false), noIme)
        assertEquals(DropdownPlacement(710, 280, true), withIme)
    }

    @Test
    fun anchorBelowTheLimitGetsNoNegativeHeight() {
        assertEquals(
            DropdownPlacement(y = 30, maxHeight = 0, above = true),
            dropdownPlacement(anchorTop = 40, anchorBottom = 80, minTop = 50, maxBottom = 60, 280, gap = 10)
        )
    }

    @Test
    fun filterIsCaseInsensitiveAndTrimmed() {
        val models = listOf("openai/gpt-live-transcribe", "openai/whisper-1", "elevenlabs/scribe-v2")
        assertEquals(listOf("openai/gpt-live-transcribe"), filterModels(models, " LIVE "))
        assertEquals(listOf("elevenlabs/scribe-v2"), filterModels(models, "ElevenLabs"))
    }

    @Test
    fun blankQueryShowsAllAndNullModelsNone() {
        val models = listOf("a", "b")
        assertEquals(models, filterModels(models, ""))
        assertEquals(models, filterModels(models, "  "))
        assertEquals(emptyList<String>(), filterModels(null, "a"))
    }
}
