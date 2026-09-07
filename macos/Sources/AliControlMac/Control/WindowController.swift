import Foundation

protocol WindowControlling {
    func minimizeWindow() -> Bool
    func closeWindow() -> Bool
    func fullscreenToggle() -> Bool
}

/// Simulates the standard macOS window-management keyboard shortcuts on
/// whatever application is currently frontmost. Requires Accessibility trust
/// — gated centrally by `ActionRegistry`, not here. `closeWindow` sends
/// Cmd+W, which closes only the frontmost window — it never force-quits
/// anything.
final class WindowController: WindowControlling {

    func minimizeWindow() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 46, modifiers: [.maskCommand]) // Cmd+M
        return true
    }

    func closeWindow() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 13, modifiers: [.maskCommand]) // Cmd+W
        return true
    }

    func fullscreenToggle() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 3, modifiers: [.maskCommand, .maskControl]) // Cmd+Ctrl+F
        return true
    }
}
