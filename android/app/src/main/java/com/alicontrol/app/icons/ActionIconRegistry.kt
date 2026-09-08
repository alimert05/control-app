package com.alicontrol.app.icons

import androidx.annotation.DrawableRes
import com.alicontrol.app.R
import com.alicontrol.app.ble.AliControlCommands

/**
 * The one place that maps an [com.alicontrol.app.actions.ActionLibrary]
 * action id to a locally bundled vector drawable. Every icon here is a hand
 * -authored `res/drawable/ic_*.xml` vector — no emoji, no runtime download,
 * no PNGs (see TASK-007's icon system requirement).
 *
 * Deliberately keyed by the raw action id string (not the enum/category) so
 * it stays a flat, obviously-complete lookup table that's trivial to audit
 * against [com.alicontrol.app.actions.ActionLibrary.all] — see
 * ActionIconRegistryTest's full-coverage check.
 */
object ActionIconRegistry {

    private val icons: Map<String, Int> = mapOf(
        AliControlCommands.PLAY_PAUSE to R.drawable.ic_play_pause,
        AliControlCommands.NEXT_TRACK to R.drawable.ic_skip_next,
        AliControlCommands.PREVIOUS_TRACK to R.drawable.ic_skip_previous,
        AliControlCommands.VOLUME_UP to R.drawable.ic_volume_up,
        AliControlCommands.VOLUME_DOWN to R.drawable.ic_volume_down,
        AliControlCommands.MUTE_TOGGLE to R.drawable.ic_volume_off,

        AliControlCommands.LOCK_SCREEN to R.drawable.ic_lock,
        AliControlCommands.SLEEP to R.drawable.ic_sleep,
        AliControlCommands.DISPLAY_SLEEP to R.drawable.ic_display_sleep,
        AliControlCommands.SCREENSHOT to R.drawable.ic_screenshot,
        AliControlCommands.SHOW_DESKTOP to R.drawable.ic_desktop,
        AliControlCommands.SPOTLIGHT to R.drawable.ic_search,
        AliControlCommands.MISSION_CONTROL to R.drawable.ic_dashboard,

        AliControlCommands.OPEN_TERMINAL to R.drawable.ic_terminal,
        AliControlCommands.OPEN_VSCODE to R.drawable.ic_code,
        AliControlCommands.OPEN_FINDER to R.drawable.ic_folder,
        AliControlCommands.OPEN_BROWSER to R.drawable.ic_language,
        AliControlCommands.OPEN_SPOTIFY to R.drawable.ic_music_note,
        AliControlCommands.OPEN_SYSTEM_SETTINGS to R.drawable.ic_settings,
        AliControlCommands.OPEN_ACTIVITY_MONITOR to R.drawable.ic_monitor_heart,

        AliControlCommands.MINIMIZE_WINDOW to R.drawable.ic_minimize,
        AliControlCommands.CLOSE_WINDOW to R.drawable.ic_close,
        AliControlCommands.FULLSCREEN_TOGGLE to R.drawable.ic_fullscreen,

        AliControlCommands.COPY to R.drawable.ic_copy,
        AliControlCommands.PASTE to R.drawable.ic_paste,
        AliControlCommands.UNDO to R.drawable.ic_undo,
        AliControlCommands.REDO to R.drawable.ic_redo
    )

    /** Falls back to a generic glyph rather than crashing if an id is ever missing here. */
    @DrawableRes
    fun iconFor(actionId: String): Int = icons[actionId] ?: R.drawable.ic_apps

    /** Whether [actionId] has a dedicated (non-fallback) icon mapped. */
    fun hasDedicatedIcon(actionId: String): Boolean = icons.containsKey(actionId)
}
