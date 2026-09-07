import XCTest
@testable import AliControlMac

final class ActionRegistryTests: XCTestCase {

    // MARK: - Completeness / no duplicates

    func testEveryDeclaredActionIsRegistered() {
        let fakes = FakeControllers()
        let registry = fakes.makeRegistry()

        for action in AliControlAction.allCases {
            XCTAssertNotNil(registry.handler(for: action.rawValue), "missing handler for \(action.rawValue)")
        }
    }

    func testNoDuplicateActionIds() {
        // AliControlAction is an enum with String raw values, so the Swift
        // compiler already forbids duplicate cases sharing a raw value —
        // this asserts the *registry* ends up with exactly one entry per
        // declared action too (ActionRegistry's own construction would trap
        // on a real duplicate; this is the non-crashing version of that check).
        let fakes = FakeControllers()
        let registry = fakes.makeRegistry()
        XCTAssertEqual(registry.registeredActionIds.count, AliControlAction.allCases.count)
    }

    func testUnknownActionIdIsNotRegistered() {
        let fakes = FakeControllers()
        let registry = fakes.makeRegistry()
        XCTAssertNil(registry.handler(for: "shutdown"))
        XCTAssertNil(registry.handler(for: "restart"))
        XCTAssertNil(registry.handler(for: "logout"))
        XCTAssertNil(registry.handler(for: "run_shell_command"))
        XCTAssertNil(registry.handler(for: ""))
    }

    // MARK: - Dispatch routes to the right controller

    func testEachActionDispatchesToItsController() {
        let fakes = FakeControllers()
        let registry = fakes.makeRegistry()

        let dispatched: [AliControlAction] = [
            .playPause, .nextTrack, .previousTrack, .volumeUp, .volumeDown, .muteToggle,
            .lockScreen, .sleep, .displaySleep, .screenshot, .showDesktop, .spotlight, .missionControl,
            .openTerminal, .openVSCode, .openFinder, .openBrowser, .openSpotify, .openSystemSettings, .openActivityMonitor,
            .minimizeWindow, .closeWindow, .fullscreenToggle,
            .copy, .paste, .undo, .redo
        ]
        for action in dispatched {
            _ = registry.handler(for: action.rawValue)?.execute()
        }

        XCTAssertEqual(fakes.media.calls, ["playPause", "nextTrack", "previousTrack", "volumeUp", "volumeDown", "muteToggle"])
        XCTAssertEqual(fakes.system.calls, ["lockScreen", "sleep", "displaySleep", "screenshot", "showDesktop", "spotlight", "missionControl"])
        XCTAssertEqual(fakes.apps.calls, ["openTerminal", "openVSCode", "openFinder", "openBrowser", "openSpotify", "openSystemSettings", "openActivityMonitor"])
        XCTAssertEqual(fakes.window.calls, ["minimizeWindow", "closeWindow", "fullscreenToggle"])
        XCTAssertEqual(fakes.productivity.calls, ["copy", "paste", "undo", "redo"])
    }

    // MARK: - Permission metadata is internally consistent

    func testAccessibilityRequiredActionsAreExactlyTheExpectedSet() {
        let fakes = FakeControllers()
        let registry = fakes.makeRegistry()

        let expectedAccessibility: Set<String> = [
            "play_pause", "next_track", "previous_track",
            "screenshot", "show_desktop", "spotlight", "mission_control",
            "minimize_window", "close_window", "fullscreen_toggle",
            "copy", "paste", "undo", "redo"
        ]

        for action in AliControlAction.allCases where action != .ping {
            let handler = registry.handler(for: action.rawValue)!
            let expected = expectedAccessibility.contains(action.rawValue)
            XCTAssertEqual(handler.requiresAccessibility, expected, "\(action.rawValue) accessibility flag mismatch")
        }
    }

    // MARK: - canExecute respects trust without prompting

    func testCanExecuteAllowsNonAccessibilityActionsRegardlessOfTrust() {
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = false
        let registry = fakes.makeRegistry()

        let handler = registry.handler(for: "volume_up")!
        XCTAssertTrue(registry.canExecute(handler))
    }

    func testCanExecuteBlocksAccessibilityActionsWhenNotTrusted() {
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = false
        let registry = fakes.makeRegistry()

        let handler = registry.handler(for: "play_pause")!
        XCTAssertFalse(registry.canExecute(handler))
    }

    func testCanExecuteAllowsAccessibilityActionsWhenTrusted() {
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = true
        let registry = fakes.makeRegistry()

        let handler = registry.handler(for: "play_pause")!
        XCTAssertTrue(registry.canExecute(handler))
    }
}
