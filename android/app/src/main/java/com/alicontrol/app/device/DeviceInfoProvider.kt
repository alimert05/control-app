package com.alicontrol.app.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings

/**
 * Reads local, read-only tablet state (battery, volume, brightness). None of
 * these require a runtime permission on API 27.
 */
class DeviceInfoProvider(private val context: Context) {

    fun batteryPercent(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (capacity in 0..100) return capacity

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return batteryToPercent(level, scale)
    }

    fun volumePercent(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return volumeToPercent(current, max)
    }

    fun brightnessPercent(): Int {
        return try {
            val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            brightnessToPercent(value)
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }
    }

    companion object {
        fun batteryToPercent(level: Int, scale: Int): Int {
            if (level < 0 || scale <= 0) return -1
            return (level * 100) / scale
        }

        fun volumeToPercent(current: Int, max: Int): Int {
            if (current < 0 || max <= 0) return -1
            return (current * 100) / max
        }

        fun brightnessToPercent(rawValue: Int): Int {
            if (rawValue < 0) return -1
            return (rawValue.coerceIn(0, 255) * 100) / 255
        }
    }
}
