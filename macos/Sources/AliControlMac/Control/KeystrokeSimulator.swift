import CoreGraphics
import Foundation

/// Simulates one real keyboard shortcut (key-down then key-up, with modifier
/// flags) via `CGEventPost` — a single, bounded, user-triggered post per
/// call, never a loop or a held key. Used by every macOS action that has no
/// cleaner native API (screenshot, show desktop, Spotlight, Mission Control,
/// window controls, copy/paste/undo/redo).
///
/// This is why every action that uses it is classified as requiring
/// Accessibility: posting synthetic keyboard events with modifier flags to
/// the frontmost application is squarely what macOS's Accessibility/Input
/// Monitoring gating covers. `ActionRegistry` checks that trust before ever
/// calling into here — this type has no permission-checking logic of its own.
enum KeystrokeSimulator {
    static func postShortcut(keyCode: CGKeyCode, modifiers: CGEventFlags) {
        let source = CGEventSource(stateID: .hidSystemState)
        guard
            let keyDown = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: true),
            let keyUp = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false)
        else { return }

        keyDown.flags = modifiers
        keyUp.flags = modifiers
        keyDown.post(tap: .cghidEventTap)
        keyUp.post(tap: .cghidEventTap)
    }
}
