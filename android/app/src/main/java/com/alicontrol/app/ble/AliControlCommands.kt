package com.alicontrol.app.ble

/**
 * The explicit allowlist of command actions a desktop agent (macOS in
 * TASK-003, Windows later) must implement. There is no generic/arbitrary
 * command path on either side — only these action strings are ever sent, and
 * a desktop agent must reject anything else with an error state message
 * rather than executing it.
 *
 * TASK-006 expanded this from the original 5 (still identical values, fully
 * backward compatible) to the full first-batch action library — see
 * [com.alicontrol.app.actions.ActionLibrary], which builds its metadata from
 * these same constants so the ID string only ever exists in one place.
 */
object AliControlCommands {
    const val PING = "ping"

    // Media — PLAY_PAUSE/VOLUME_UP/VOLUME_DOWN/MUTE_TOGGLE existed before TASK-006.
    const val PLAY_PAUSE = "play_pause"
    const val NEXT_TRACK = "next_track"
    const val PREVIOUS_TRACK = "previous_track"
    const val VOLUME_UP = "volume_up"
    const val VOLUME_DOWN = "volume_down"
    const val MUTE_TOGGLE = "mute_toggle"

    // System
    const val LOCK_SCREEN = "lock_screen"
    const val SLEEP = "sleep"
    const val DISPLAY_SLEEP = "display_sleep"
    const val SCREENSHOT = "screenshot"
    const val SHOW_DESKTOP = "show_desktop"
    const val SPOTLIGHT = "spotlight"
    const val MISSION_CONTROL = "mission_control"

    // Apps — OPEN_TERMINAL existed before TASK-006.
    const val OPEN_TERMINAL = "open_terminal"
    const val OPEN_VSCODE = "open_vscode"
    const val OPEN_FINDER = "open_finder"
    const val OPEN_BROWSER = "open_browser"
    const val OPEN_SPOTIFY = "open_spotify"
    const val OPEN_SYSTEM_SETTINGS = "open_system_settings"
    const val OPEN_ACTIVITY_MONITOR = "open_activity_monitor"

    // Window
    const val MINIMIZE_WINDOW = "minimize_window"
    const val CLOSE_WINDOW = "close_window"
    const val FULLSCREEN_TOGGLE = "fullscreen_toggle"

    // Productivity
    const val COPY = "copy"
    const val PASTE = "paste"
    const val UNDO = "undo"
    const val REDO = "redo"
}
