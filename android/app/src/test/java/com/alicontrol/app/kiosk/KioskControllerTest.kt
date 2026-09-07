package com.alicontrol.app.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskControllerTest {

    @Test
    fun holdShorterThanThresholdDoesNotTriggerEscape() {
        assertFalse(KioskController.isEscapeHoldComplete(pressStartMs = 1000L, nowMs = 1000L + KioskController.ESCAPE_HOLD_DURATION_MS - 1))
    }

    @Test
    fun holdAtExactlyThresholdTriggersEscape() {
        assertTrue(KioskController.isEscapeHoldComplete(pressStartMs = 1000L, nowMs = 1000L + KioskController.ESCAPE_HOLD_DURATION_MS))
    }

    @Test
    fun holdLongerThanThresholdTriggersEscape() {
        assertTrue(KioskController.isEscapeHoldComplete(pressStartMs = 1000L, nowMs = 1000L + KioskController.ESCAPE_HOLD_DURATION_MS + 5000))
    }

    @Test
    fun aBriefTapDoesNotTriggerEscape() {
        assertFalse(KioskController.isEscapeHoldComplete(pressStartMs = 5000L, nowMs = 5120L))
    }
}
