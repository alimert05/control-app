import AppKit
import Foundation

protocol MediaControlling {
    func playPause() -> Bool
    func nextTrack() -> Bool
    func previousTrack() -> Bool
    func volumeUp() -> Bool
    func volumeDown() -> Bool
    func muteToggle() -> Bool
}

/// Media-key simulation (play/pause, next, previous) and system volume
/// (AppleScript "Standard Additions" — a built-in OS command, not routed
/// through a named application, so it needs no Accessibility/Automation
/// permission at all).
///
/// The three media-key methods always attempt the key post and report
/// success — permission gating (they're classified as requiring
/// Accessibility; see `ActionRegistry`) happens centrally before these are
/// ever called, not inside them.
final class MediaController: MediaControlling {

    private let volumeStepPercent = 10

    func volumeUp() -> Bool {
        runAppleScript("set volume output volume (output volume of (get volume settings) + \(volumeStepPercent))")
    }

    func volumeDown() -> Bool {
        runAppleScript("set volume output volume (output volume of (get volume settings) - \(volumeStepPercent))")
    }

    func muteToggle() -> Bool {
        runAppleScript("set volume output muted not (output muted of (get volume settings))")
    }

    func playPause() -> Bool {
        postMediaKey(.play)
        return true
    }

    func nextTrack() -> Bool {
        postMediaKey(.next)
        return true
    }

    func previousTrack() -> Bool {
        postMediaKey(.previous)
        return true
    }

    private enum MediaKey {
        case play, next, previous

        /// NX_KEYTYPE_* from the private IOKit/hidsystem/ev_keymap.h header —
        /// not importable directly from a Swift package target, but these
        /// values have been stable and widely relied on (media-key
        /// simulation tools, several shipped apps) for well over a decade.
        var rawKeyCode: Int32 {
            switch self {
            case .play: return 16     // NX_KEYTYPE_PLAY
            case .next: return 17     // NX_KEYTYPE_NEXT
            case .previous: return 18 // NX_KEYTYPE_PREVIOUS
            }
        }
    }

    private func postMediaKey(_ key: MediaKey) {
        func post(down: Bool) {
            let keyState = down ? 0xa : 0xb
            let data1 = (Int(key.rawKeyCode) << 16) | (keyState << 8)
            let event = NSEvent.otherEvent(
                with: .systemDefined,
                location: .zero,
                modifierFlags: down ? NSEvent.ModifierFlags(rawValue: 0xa00) : NSEvent.ModifierFlags(rawValue: 0xb00),
                timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: 0,
                context: nil,
                subtype: 8,
                data1: data1,
                data2: -1
            )
            event?.cgEvent?.post(tap: .cghidEventTap)
        }
        post(down: true)
        post(down: false)
    }

    @discardableResult
    private func runAppleScript(_ source: String) -> Bool {
        guard let script = NSAppleScript(source: source) else { return false }
        var errorDict: NSDictionary?
        script.executeAndReturnError(&errorDict)
        return errorDict == nil
    }
}
