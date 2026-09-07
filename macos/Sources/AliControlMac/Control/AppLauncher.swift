import AppKit
import Foundation

protocol AppLaunching {
    func openTerminal() -> Bool
    func openVSCode() -> Bool
    func openFinder() -> Bool
    func openBrowser() -> Bool
    func openSpotify() -> Bool
    func openSystemSettings() -> Bool
    func openActivityMonitor() -> Bool
}

/// Launches fixed, hardcoded applications by bundle identifier — never a
/// path or command supplied by the tablet. Fails safely (returns false, no
/// crash, no error thrown) if the target app isn't installed: NSWorkspace's
/// lookup simply returns nil rather than launching anything.
final class AppLauncher: AppLaunching {

    func openTerminal() -> Bool { open(bundleIdentifier: "com.apple.Terminal") }
    func openFinder() -> Bool { open(bundleIdentifier: "com.apple.finder") }
    func openVSCode() -> Bool { open(bundleIdentifier: "com.microsoft.VSCode") }
    func openSpotify() -> Bool { open(bundleIdentifier: "com.spotify.client") }
    func openSystemSettings() -> Bool { open(bundleIdentifier: "com.apple.systempreferences") }
    func openActivityMonitor() -> Bool { open(bundleIdentifier: "com.apple.ActivityMonitor") }

    /// The user's actual default browser — never hardcoded to Safari/Chrome.
    /// `urlForApplication(toOpen:)` is the documented, correct way to ask
    /// LaunchServices which app is registered as the "https" handler.
    func openBrowser() -> Bool {
        guard let browserURL = NSWorkspace.shared.urlForApplication(toOpen: URL(string: "https://")!) else {
            return false
        }
        NSWorkspace.shared.openApplication(at: browserURL, configuration: NSWorkspace.OpenConfiguration())
        return true
    }

    private func open(bundleIdentifier: String) -> Bool {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleIdentifier) else {
            return false
        }
        NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
        return true
    }
}
