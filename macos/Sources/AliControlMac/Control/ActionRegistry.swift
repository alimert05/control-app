import Foundation

/// One entry in the macOS action allowlist: an action ID mapped to how it's
/// executed and whether it needs Accessibility trust.
struct ActionHandler {
    let id: String
    let requiresAccessibility: Bool
    let execute: () -> Bool
}

/// Builds and owns the fixed set of supported action handlers — replacing
/// TASK-002/003's single ever-growing switch statement in CommandDispatcher.
/// Adding action #28 means one `add(...)` line in `buildHandlers`, not
/// touching a giant switch.
///
/// This is still a strict allowlist: nothing outside `handlers` is ever
/// reachable. `handler(for:)` returns nil for any ID not explicitly
/// registered here, and there is no fallback/default path that executes
/// anything — an unrecognized action ID can never run.
final class ActionRegistry {

    private let handlers: [String: ActionHandler]
    private let accessibility: AccessibilityChecking

    init(
        media: MediaControlling = MediaController(),
        system: SystemControlling = SystemController(),
        apps: AppLaunching = AppLauncher(),
        window: WindowControlling = WindowController(),
        productivity: ProductivityControlling = ProductivityController(),
        accessibility: AccessibilityChecking = AccessibilityChecker()
    ) {
        self.accessibility = accessibility
        self.handlers = ActionRegistry.buildHandlers(
            media: media, system: system, apps: apps, window: window, productivity: productivity
        )
    }

    /// All registered action IDs — used by tests to cross-check completeness
    /// against `AliControlAction.allCases`.
    var registeredActionIds: Set<String> { Set(handlers.keys) }

    func handler(for actionId: String) -> ActionHandler? {
        handlers[actionId]
    }

    /// Whether `handler` can run right now without ever prompting the user —
    /// true if it needs no permission, or it needs Accessibility and this
    /// process is already trusted. Never requests permission itself.
    func canExecute(_ handler: ActionHandler) -> Bool {
        !handler.requiresAccessibility || accessibility.isTrusted
    }

    private static func buildHandlers(
        media: MediaControlling,
        system: SystemControlling,
        apps: AppLaunching,
        window: WindowControlling,
        productivity: ProductivityControlling
    ) -> [String: ActionHandler] {
        var entries: [ActionHandler] = []
        func add(_ action: AliControlAction, accessibility: Bool = false, _ execute: @escaping () -> Bool) {
            entries.append(ActionHandler(id: action.rawValue, requiresAccessibility: accessibility, execute: execute))
        }

        add(.ping) { true }

        // Media
        add(.playPause, accessibility: true, media.playPause)
        add(.nextTrack, accessibility: true, media.nextTrack)
        add(.previousTrack, accessibility: true, media.previousTrack)
        add(.volumeUp, media.volumeUp)
        add(.volumeDown, media.volumeDown)
        add(.muteToggle, media.muteToggle)

        // System
        add(.lockScreen, system.lockScreen)
        add(.sleep, system.sleep)
        add(.displaySleep, system.displaySleep)
        add(.screenshot, accessibility: true, system.screenshot)
        add(.showDesktop, accessibility: true, system.showDesktop)
        add(.spotlight, accessibility: true, system.spotlight)
        add(.missionControl, accessibility: true, system.missionControl)

        // Apps
        add(.openTerminal, apps.openTerminal)
        add(.openVSCode, apps.openVSCode)
        add(.openFinder, apps.openFinder)
        add(.openBrowser, apps.openBrowser)
        add(.openSpotify, apps.openSpotify)
        add(.openSystemSettings, apps.openSystemSettings)
        add(.openActivityMonitor, apps.openActivityMonitor)

        // Window
        add(.minimizeWindow, accessibility: true, window.minimizeWindow)
        add(.closeWindow, accessibility: true, window.closeWindow)
        add(.fullscreenToggle, accessibility: true, window.fullscreenToggle)

        // Productivity
        add(.copy, accessibility: true, productivity.copy)
        add(.paste, accessibility: true, productivity.paste)
        add(.undo, accessibility: true, productivity.undo)
        add(.redo, accessibility: true, productivity.redo)

        // uniqueKeysWithValues crashes immediately (at construction, i.e.
        // effectively at app launch) if two entries ever share an ID — a
        // deliberate, loud safety net against a registration bug in this
        // static list; ActionRegistryTests also asserts this explicitly so
        // it's caught by `swift test`, not just at runtime.
        return Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
    }
}
