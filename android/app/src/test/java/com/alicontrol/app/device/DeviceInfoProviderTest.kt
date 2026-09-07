package com.alicontrol.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoProviderTest {

    @Test
    fun batteryPercentFromLevelAndScale() {
        assertEquals(50, DeviceInfoProvider.batteryToPercent(50, 100))
        assertEquals(75, DeviceInfoProvider.batteryToPercent(3, 4))
    }

    @Test
    fun batteryPercentInvalidInputReturnsMinusOne() {
        assertEquals(-1, DeviceInfoProvider.batteryToPercent(-1, 100))
        assertEquals(-1, DeviceInfoProvider.batteryToPercent(50, 0))
    }

    @Test
    fun volumePercentFromCurrentAndMax() {
        assertEquals(50, DeviceInfoProvider.volumeToPercent(5, 10))
        assertEquals(0, DeviceInfoProvider.volumeToPercent(0, 15))
    }

    @Test
    fun volumePercentInvalidInputReturnsMinusOne() {
        assertEquals(-1, DeviceInfoProvider.volumeToPercent(5, 0))
    }

    @Test
    fun brightnessPercentClampsToByteRange() {
        assertEquals(100, DeviceInfoProvider.brightnessToPercent(255))
        assertEquals(0, DeviceInfoProvider.brightnessToPercent(0))
        assertEquals(100, DeviceInfoProvider.brightnessToPercent(400))
    }
}
