package com.alicontrol.app.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Reads the tablet's battery percentage, for the Device/Settings screen only
 * (it must not reappear on the main dashboard — see TASK-002/003).
 *
 * Deliberately has no polling loop or scheduled refresh of its own: a caller
 * reads [currentPercent] when it actually needs a value (e.g. once when the
 * settings screen becomes visible), or registers its own
 * `ACTION_BATTERY_CHANGED` receiver to stay live-updated for as long as it's
 * on screen — both are event-driven, not timers.
 */
class BatteryProvider(private val context: Context) {

    fun currentPercent(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (capacity in 0..100) return capacity

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return toPercent(level, scale)
    }

    companion object {
        fun toPercent(level: Int, scale: Int): Int {
            if (level < 0 || scale <= 0) return -1
            return (level * 100) / scale
        }
    }
}
