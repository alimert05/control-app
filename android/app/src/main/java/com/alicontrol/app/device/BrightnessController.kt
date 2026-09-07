package com.alicontrol.app.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Reads/writes the tablet's global screen brightness (0-255 raw, exposed as a
 * 0-100 percent for the UI). Writing requires the WRITE_SETTINGS special
 * permission — a system-managed grant screen, not a runtime prompt — so this
 * class never assumes it has it: callers must check [hasWritePermission]
 * first, and [setPercent] itself fails safely (returns false) rather than
 * throwing if the permission isn't there.
 */
class BrightnessController(private val context: Context) {

    val hasWritePermission: Boolean
        get() = Settings.System.canWrite(context)

    /** -1 if the system brightness setting can't be read for any reason. */
    fun currentPercent(): Int {
        val raw = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }
        return if (raw < 0) -1 else rawToPercent(raw)
    }

    /** Returns false without writing anything if [hasWritePermission] is false. */
    fun setPercent(percent: Int): Boolean {
        if (!hasWritePermission) return false
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, percentToRaw(percent))
        return true
    }

    /** Launches the system screen where the user can grant WRITE_SETTINGS. */
    fun createPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    companion object {
        private const val MAX_RAW = 255

        fun rawToPercent(raw: Int): Int = (raw.coerceIn(0, MAX_RAW) * 100) / MAX_RAW

        fun percentToRaw(percent: Int): Int = (percent.coerceIn(0, 100) * MAX_RAW) / 100
    }
}
