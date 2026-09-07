package com.alicontrol.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeControllerTest {

    @Test
    fun levelToPercentConvertsFullRange() {
        assertEquals(0, VolumeController.levelToPercent(0, 15))
        assertEquals(100, VolumeController.levelToPercent(15, 15))
        assertEquals(50, VolumeController.levelToPercent(5, 10))
    }

    @Test
    fun levelToPercentClampsOutOfRangeInput() {
        assertEquals(0, VolumeController.levelToPercent(-2, 15))
        assertEquals(100, VolumeController.levelToPercent(99, 15))
    }

    @Test
    fun levelToPercentHandlesZeroMaxWithoutDividingByZero() {
        assertEquals(0, VolumeController.levelToPercent(0, 0))
    }

    @Test
    fun percentToLevelConvertsFullRange() {
        assertEquals(0, VolumeController.percentToLevel(0, 15))
        assertEquals(15, VolumeController.percentToLevel(100, 15))
    }

    @Test
    fun percentToLevelHandlesZeroMaxWithoutDividingByZero() {
        assertEquals(0, VolumeController.percentToLevel(50, 0))
    }
}
