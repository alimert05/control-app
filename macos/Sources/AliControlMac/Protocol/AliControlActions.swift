import Foundation

/// The explicit allowlist of action IDs this agent supports. Raw values must
/// match android/app/src/main/java/com/alicontrol/app/ble/AliControlCommands.kt
/// (and android/.../actions/ActionLibrary.kt) exactly — this is the
/// platform-independent protocol both sides agree on, so a future Windows
/// agent implements the same raw-value strings against the same IDs.
///
/// Renamed from the TASK-002/003 `AliControlCommand` as part of TASK-006's
/// action-registry refactor. This enum exists purely for type-safe reference
/// when building `ActionRegistry` — nothing switches over it exhaustively
/// anymore (see `ActionRegistry`), which is what lets new actions be added
/// without a growing switch statement.
enum AliControlAction: String, CaseIterable {
    case ping

    // Media — play_pause/volume_up/volume_down/mute_toggle existed before TASK-006.
    case playPause = "play_pause"
    case nextTrack = "next_track"
    case previousTrack = "previous_track"
    case volumeUp = "volume_up"
    case volumeDown = "volume_down"
    case muteToggle = "mute_toggle"

    // System
    case lockScreen = "lock_screen"
    case sleep
    case displaySleep = "display_sleep"
    case screenshot
    case showDesktop = "show_desktop"
    case spotlight
    case missionControl = "mission_control"

    // Apps — open_terminal existed before TASK-006.
    case openTerminal = "open_terminal"
    case openVSCode = "open_vscode"
    case openFinder = "open_finder"
    case openBrowser = "open_browser"
    case openSpotify = "open_spotify"
    case openSystemSettings = "open_system_settings"
    case openActivityMonitor = "open_activity_monitor"

    // Window
    case minimizeWindow = "minimize_window"
    case closeWindow = "close_window"
    case fullscreenToggle = "fullscreen_toggle"

    // Productivity
    case copy
    case paste
    case undo
    case redo
}
