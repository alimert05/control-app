package com.alicontrol.app.device

import android.content.Context
import android.media.AudioManager

/**
 * Reads/writes the tablet's own media (`STREAM_MUSIC`) volume. Entirely
 * separate from the remote Mac/Windows volume commands sent over BLE — those
 * never touch this class, and this never touches the BLE transport.
 * No permission is required for stream volume changes.
 */
class VolumeController(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val maxLevel: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val currentLevel: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun currentPercent(): Int = levelToPercent(currentLevel, maxLevel)

    fun setLevel(level: Int) {
        // flags=0: no extra system volume UI popup, this screen is the UI.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, maxLevel), 0)
    }

    companion object {
        fun levelToPercent(level: Int, max: Int): Int {
            if (max <= 0) return 0
            return (level.coerceIn(0, max) * 100) / max
        }

        fun percentToLevel(percent: Int, max: Int): Int {
            if (max <= 0) return 0
            return (percent.coerceIn(0, 100) * max) / 100
        }
    }
}
