package com.alicontrol.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessControllerTest {

    @Test
    fun rawToPercentConvertsFullRange() {
        assertEquals(0, BrightnessController.rawToPercent(0))
        assertEquals(100, BrightnessController.rawToPercent(255))
        assertEquals(50, BrightnessController.rawToPercent(128))
    }

    @Test
    fun rawToPercentClampsOutOfRangeInput() {
        assertEquals(0, BrightnessController.rawToPercent(-10))
        assertEquals(100, BrightnessController.rawToPercent(500))
    }

    @Test
    fun percentToRawConvertsFullRange() {
        assertEquals(0, BrightnessController.percentToRaw(0))
        assertEquals(255, BrightnessController.percentToRaw(100))
    }

    @Test
    fun percentToRawClampsOutOfRangeInput() {
        assertEquals(0, BrightnessController.percentToRaw(-5))
        assertEquals(255, BrightnessController.percentToRaw(150))
    }

    @Test
    fun roundTripStaysCloseWithinRoundingError() {
        for (percent in 0..100 step 10) {
            val raw = BrightnessController.percentToRaw(percent)
            val roundTripped = BrightnessController.rawToPercent(raw)
            assertEquals(percent.toDouble(), roundTripped.toDouble(), 2.0)
        }
    }
}
