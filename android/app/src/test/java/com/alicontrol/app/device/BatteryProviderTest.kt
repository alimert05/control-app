package com.alicontrol.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryProviderTest {

    @Test
    fun toPercentConvertsLevelAndScale() {
        assertEquals(50, BatteryProvider.toPercent(50, 100))
        assertEquals(75, BatteryProvider.toPercent(3, 4))
    }

    @Test
    fun toPercentInvalidInputReturnsMinusOne() {
        assertEquals(-1, BatteryProvider.toPercent(-1, 100))
        assertEquals(-1, BatteryProvider.toPercent(50, 0))
    }
}
