package com.alicontrol.app.kiosk

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Application-level kiosk behaviour: persistent immersive fullscreen, an
 * optional "keep screen on" preference, and a hidden long-press escape hatch.
 *
 * This intentionally does NOT attempt true OS-level lockdown (blocking the
 * Home/Recents buttons) — that requires Device Owner provisioning, which is
 * out of scope for TASK-004. What's here is everything achievable without
 * special permissions: hide the system bars, keep re-asserting that after any
 * user interaction reveals them, and absorb the back button on the home
 * screen. See README.md for the exact limitation.
 */
class KioskController(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var keepScreenAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /** Hides the status and navigation bars, restoring them if the system reveals them. */
    fun applyImmersiveMode(window: Window) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /** Fully reveals the system bars — the escape hatch calls this. */
    fun exitImmersiveMode(window: Window) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    fun applyKeepAwake(window: Window) {
        if (keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Wires a hidden development escape hatch onto [view]: press and hold for
     * [ESCAPE_HOLD_DURATION_MS] (checked on release, not mid-hold — simpler,
     * and this is a development aid, not a UI feature that needs a
     * countdown) reveals the system bars via [exitImmersiveMode], so a
     * developer can reach Home/Recents/Settings. Immersive mode reasserts
     * itself automatically the next time the app regains window focus, so
     * there's no separate "re-lock" step.
     *
     * A 3+ second deliberate hold is not something a normal user triggers by
     * accident during ordinary use of the screen this is attached to.
     */
    fun attachEscapeHatch(view: View, window: Window) {
        var pressStartMs = 0L
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> pressStartMs = SystemClock.elapsedRealtime()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressStartMs != 0L && isEscapeHoldComplete(pressStartMs, SystemClock.elapsedRealtime())) {
                        exitImmersiveMode(window)
                    }
                    pressStartMs = 0L
                }
            }
            false
        }
    }

    companion object {
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_KEEP_AWAKE = "keep_screen_awake"

        const val ESCAPE_HOLD_DURATION_MS = 3000L

        /** Pure timing check, exposed for testing without a View/MotionEvent. */
        fun isEscapeHoldComplete(pressStartMs: Long, nowMs: Long): Boolean =
            nowMs - pressStartMs >= ESCAPE_HOLD_DURATION_MS
    }
}
