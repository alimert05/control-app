package com.alicontrol.app.device

import android.content.Context

/**
 * Single access point for the tablet-local controllers the Device/Settings
 * screen uses, so the Activity instantiates one thing instead of three.
 */
class DeviceController(context: Context) {
    val brightness = BrightnessController(context)
    val volume = VolumeController(context)
    val battery = BatteryProvider(context)
}
