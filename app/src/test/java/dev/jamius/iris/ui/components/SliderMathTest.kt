/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderMathTest {

    @Test
    fun fractionOfFirstLastAndMiddle() {
        assertEquals(0f, sliderFraction(100, 100..200), 0f)
        assertEquals(1f, sliderFraction(200, 100..200), 0f)
        assertEquals(0.5f, sliderFraction(150, 100..200), 0f)
    }

    @Test
    fun fractionOfOutOfRangeValueIsClamped() {
        assertEquals(0f, sliderFraction(50, 100..200), 0f)
        assertEquals(1f, sliderFraction(250, 100..200), 0f)
    }

    @Test
    fun fractionOfSingleValueRangeIsZero() {
        assertEquals(0f, sliderFraction(5, 5..5), 0f)
    }

    @Test
    fun valueAtEndsAndBeyond() {
        assertEquals(100, sliderValue(0f, 100..200, 10))
        assertEquals(200, sliderValue(1f, 100..200, 10))
        assertEquals(100, sliderValue(-0.3f, 100..200, 10))
        assertEquals(200, sliderValue(1.4f, 100..200, 10))
    }

    @Test
    fun valueSnapsToNearestStep() {
        assertEquals(150, sliderValue(0.54f, 100..200, 10))
        assertEquals(160, sliderValue(0.56f, 100..200, 10))
    }

    @Test
    fun valueWithStepOne() {
        assertEquals(1, sliderValue(0f, 1..6, 1))
        assertEquals(3, sliderValue(0.45f, 1..6, 1))
        assertEquals(6, sliderValue(1f, 1..6, 1))
    }

    @Test
    fun nonDivisibleRangeStaysInRange() {
        assertEquals(9, sliderValue(1f, 1..10, 4))
        assertEquals(5, sliderValue(0.5f, 1..10, 4))
    }
}
