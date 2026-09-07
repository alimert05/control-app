package com.alicontrol.app.ble

/**
 * The explicit allowlist of command actions a desktop agent (macOS in
 * TASK-003, Windows later) must implement. There is no generic/arbitrary
 * command path on either side — only these action strings are ever sent, and
 * a desktop agent must reject anything else with an error state message
 * rather than executing it.
 */
object AliControlCommands {
    const val PING = "ping"
    const val VOLUME_UP = "volume_up"
    const val VOLUME_DOWN = "volume_down"
    const val MUTE_TOGGLE = "mute_toggle"
    const val PLAY_PAUSE = "play_pause"
    const val OPEN_TERMINAL = "open_terminal"
}
