import AppKit
import Foundation

/// Executes the fixed set of supported macOS actions. Abstracted behind a
/// protocol purely so `CommandDispatcher` is unit-testable without actually
/// changing the test machine's volume or opening Terminal.
protocol MacOSControlling {
    func volumeUp() -> Bool
    func volumeDown() -> Bool
    func toggleMute() -> Bool
    func playPause() -> Bool
    func openTerminal() -> Bool
}

/// No arbitrary command execution exists anywhere in this type: each action
/// is its own explicit, hard-coded method. There is no method that takes a
/// string/shell command and runs it.
final class MacOSController: MacOSControlling {

    private let volumeStepPercent = 10

    /// System output volume, via the "Standard Additions" `set volume`/`get
    /// volume settings` AppleScript commands. This is a built-in scripting
    /// command handled by the OS itself (not sent as an Apple Event to a
    /// named application), so it needs neither Accessibility nor Automation
    /// permission.
    func volumeUp() -> Bool {
        runAppleScript("set volume output volume (output volume of (get volume settings) + \(volumeStepPercent))")
    }

    func volumeDown() -> Bool {
        runAppleScript("set volume output volume (output volume of (get volume settings) - \(volumeStepPercent))")
    }

    func toggleMute() -> Bool {
        runAppleScript("set volume output muted not (output muted of (get volume settings))")
    }

    /// Simulates the hardware media-key press so whichever app currently owns
    /// "now playing" (Music, a browser tab, etc.) toggles — not tied to any
    /// one media app. See postMediaKey's doc comment for the permission caveat.
    func playPause() -> Bool {
        postMediaKey(down: true)
        postMediaKey(down: false)
        return true
    }

    /// NSWorkspace app launch — unprivileged, no special permission.
    func openTerminal() -> Bool {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.Terminal") else {
            return false
        }
        NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
        return true
    }

    @discardableResult
    private func runAppleScript(_ source: String) -> Bool {
        guard let script = NSAppleScript(source: source) else { return false }
        var errorDict: NSDictionary?
        script.executeAndReturnError(&errorDict)
        return errorDict == nil
    }

    /// NX_KEYTYPE_PLAY, from the private IOKit/hidsystem/ev_keymap.h header.
    /// Not importable directly from a plain Swift package target, but this
    /// value has been stable and widely relied on (media-key simulation
    /// tools, several shipped apps) for well over a decade.
    private let nxKeyTypePlay: Int32 = 16

    /// Posts a synthetic system-defined (media key) HID event. This is a
    /// public, documented mechanism (NSEvent/CGEvent), not the private
    /// Accessibility (AXUIElement) API — but on some macOS versions the OS
    /// still requires the process be Accessibility-trusted before a posted
    /// CGEvent is actually delivered system-wide. This agent does not
    /// proactively request that permission (nothing here calls
    /// AXIsProcessTrustedWithOptions); if it turns out to be required on the
    /// target machine, macOS will surface its own prompt the first time this
    /// runs, or the key press will silently have no effect. See README.md.
    private func postMediaKey(down: Bool) {
        let keyState = down ? 0xa : 0xb
        let data1 = (Int(nxKeyTypePlay) << 16) | (keyState << 8)
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
}
