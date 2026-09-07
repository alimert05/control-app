package com.alicontrol.app.kiosk

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun bootCompletedActionShouldLaunch() {
        assertTrue(BootReceiver.shouldLaunch(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun nullActionShouldNotLaunch() {
        assertFalse(BootReceiver.shouldLaunch(null))
    }

    @Test
    fun unrelatedActionShouldNotLaunch() {
        assertFalse(BootReceiver.shouldLaunch(Intent.ACTION_BATTERY_CHANGED))
        assertFalse(BootReceiver.shouldLaunch("some.other.action"))
    }
}
