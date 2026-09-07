import Foundation

/// The explicit allowlist of command actions this agent supports. Must match
/// android/app/src/main/java/com/alicontrol/app/ble/AliControlCommands.kt
/// exactly — this is the platform-independent protocol both sides agree on,
/// so a future Windows agent implements the same raw-value strings.
///
/// There is no generic/arbitrary command path: `CommandDispatcher` switches
/// over this enum exhaustively, and any action string that doesn't match one
/// of these cases is rejected with an error state rather than executed.
enum AliControlCommand: String {
    case ping
    case volumeUp = "volume_up"
    case volumeDown = "volume_down"
    case muteToggle = "mute_toggle"
    case playPause = "play_pause"
    case openTerminal = "open_terminal"
}
