import Foundation

protocol ProductivityControlling {
    func copy() -> Bool
    func paste() -> Bool
    func undo() -> Bool
    func redo() -> Bool
}

/// Simulates standard edit-menu keyboard shortcuts on whatever currently has
/// focus. Requires Accessibility trust — gated centrally by
/// `ActionRegistry`, not here.
final class ProductivityController: ProductivityControlling {

    func copy() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 8, modifiers: [.maskCommand]) // Cmd+C
        return true
    }

    func paste() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 9, modifiers: [.maskCommand]) // Cmd+V
        return true
    }

    func undo() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 6, modifiers: [.maskCommand]) // Cmd+Z
        return true
    }

    func redo() -> Bool {
        KeystrokeSimulator.postShortcut(keyCode: 6, modifiers: [.maskCommand, .maskShift]) // Cmd+Shift+Z
        return true
    }
}
