import Darwin
import Foundation

protocol SystemControlling {
    func lockScreen() -> Bool
    func sleep() -> Bool
    func displaySleep() -> Bool
    func screenshot() -> Bool
    func showDesktop() -> Bool
    func spotlight() -> Bool
    func missionControl() -> Bool
}

/// System-level actions.
///
/// `lockScreen`/`sleep`/`displaySleep` invoke fixed, hardcoded system
/// mechanisms (never anything derived from tablet input) — `pmset` is
/// Apple's own power-management CLI and needs no special permission.
/// Bounded, single invocation each, no polling.
///
/// `screenshot`/`showDesktop`/`spotlight`/`missionControl` simulate the real
/// system keyboard shortcut via `KeystrokeSimulator`, which requires
/// Accessibility trust — gated centrally by `ActionRegistry`, not here.
/// `lockScreen` deliberately does NOT use `KeystrokeSimulator` (even though
/// macOS's own Apple-menu "Lock Screen" item is bound to ⌃⌘Q) specifically
/// so it needs no Accessibility permission at all.
final class SystemController: SystemControlling {

    /// Locks the current session without logging out and without
    /// Accessibility permission.
    ///
    /// The historical implementation shelled out to a `CGSession -suspend`
    /// binary that used to live under `.../Menu Extras/User.menu/Contents/
    /// Resources/` — Apple has since removed that bundle from the system
    /// image (confirmed absent on macOS 26), so that path silently stopped
    /// working. That binary was always just a thin wrapper around a private
    /// C function in `login.framework`; this calls the same function
    /// directly via `dlopen`/`dlsym` — a native dynamic-linking mechanism,
    /// not a shell, and not something whose behavior depends on Accessibility
    /// or any other TCC permission (it's a login-session state change, not a
    /// synthetic input event). It's also the same function macOS's own Apple
    /// menu → Lock Screen item triggers internally.
    ///
    /// Falls back to the legacy CLI path in case it still exists on an older
    /// macOS version this ever runs on. Both candidates are fixed, hardcoded
    /// paths/symbol names — never anything supplied by the tablet.
    func lockScreen() -> Bool {
        lockViaLoginFramework() || lockViaLegacyCGSessionUtility()
    }

    private func lockViaLoginFramework() -> Bool {
        guard let handle = dlopen(Self.loginFrameworkPath, RTLD_LAZY) else { return false }
        defer { dlclose(handle) }
        guard let symbol = dlsym(handle, Self.lockScreenSymbolName) else { return false }

        typealias LockScreenFunction = @convention(c) () -> Void
        let lock = unsafeBitCast(symbol, to: LockScreenFunction.self)
        lock()
        return true
    }

    private func lockViaLegacyCGSessionUtility() -> Bool {
        runProcess(Self.legacyCGSessionPath, ["-suspend"])
    }

    private static let loginFrameworkPath = "/System/Library/PrivateFrameworks/login.framework/Versions/A/login"
    private static let lockScreenSymbolName = "SACLockScreenImmediate"
    private static let legacyCGSessionPath = "/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession"

    func sleep() -> Bool {
        runProcess("/usr/bin/pmset", ["sleepnow"])
    }

    /// Turns the display off without sleeping the whole Mac — `pmset` makes
    /// this fully reliable with no extra permission, so no degraded fallback
    /// is needed here.
    func displaySleep() -> Bool {
        runProcess("/usr/bin/pmset", ["displaysleepnow"])
    }

    func screenshot() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 20, modifiers: [.maskCommand, .maskShift]) // Cmd+Shift+3
        return true
    }

    /// F11 is macOS's shipped default "Show Desktop" shortcut. This depends
    /// on the user not having remapped their Mission Control shortcuts away
    /// from the system defaults — see README.md for this limitation.
    func showDesktop() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 103, modifiers: []) // F11
        return true
    }

    func spotlight() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 49, modifiers: [.maskCommand]) // Cmd+Space
        return true
    }

    /// Ctrl+Up is macOS's shipped default Mission Control shortcut — same
    /// remapping caveat as showDesktop().
    func missionControl() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 126, modifiers: [.maskControl]) // Ctrl+Up
        return true
    }

    @discardableResult
    private func runProcess(_ path: String, _ arguments: [String]) -> Bool {
        guard FileManager.default.isExecutableFile(atPath: path) else { return false }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        do {
            try process.run()
            return true
        } catch {
            return false
        }
    }
}
