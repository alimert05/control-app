@testable import AliControlMac

/// Shared test doubles for the Control layer. None of these touch real
/// system volume, launch real apps, simulate real keystrokes, lock the
/// Mac, or sleep it — every call is just recorded, so `swift test` never
/// performs an actual OS action.

final class FakeMediaController: MediaControlling {
    var result = true
    private(set) var calls: [String] = []
    func playPause() -> Bool { calls.append("playPause"); return result }
    func nextTrack() -> Bool { calls.append("nextTrack"); return result }
    func previousTrack() -> Bool { calls.append("previousTrack"); return result }
    func volumeUp() -> Bool { calls.append("volumeUp"); return result }
    func volumeDown() -> Bool { calls.append("volumeDown"); return result }
    func muteToggle() -> Bool { calls.append("muteToggle"); return result }
}

final class FakeSystemController: SystemControlling {
    var result = true
    private(set) var calls: [String] = []
    func lockScreen() -> Bool { calls.append("lockScreen"); return result }
    func sleep() -> Bool { calls.append("sleep"); return result }
    func displaySleep() -> Bool { calls.append("displaySleep"); return result }
    func screenshot() -> Bool { calls.append("screenshot"); return result }
    func showDesktop() -> Bool { calls.append("showDesktop"); return result }
    func spotlight() -> Bool { calls.append("spotlight"); return result }
    func missionControl() -> Bool { calls.append("missionControl"); return result }
}

final class FakeAppLauncher: AppLaunching {
    var result = true
    private(set) var calls: [String] = []
    func openTerminal() -> Bool { calls.append("openTerminal"); return result }
    func openVSCode() -> Bool { calls.append("openVSCode"); return result }
    func openFinder() -> Bool { calls.append("openFinder"); return result }
    func openBrowser() -> Bool { calls.append("openBrowser"); return result }
    func openSpotify() -> Bool { calls.append("openSpotify"); return result }
    func openSystemSettings() -> Bool { calls.append("openSystemSettings"); return result }
    func openActivityMonitor() -> Bool { calls.append("openActivityMonitor"); return result }
}

final class FakeWindowController: WindowControlling {
    var result = true
    private(set) var calls: [String] = []
    func minimizeWindow() -> Bool { calls.append("minimizeWindow"); return result }
    func closeWindow() -> Bool { calls.append("closeWindow"); return result }
    func fullscreenToggle() -> Bool { calls.append("fullscreenToggle"); return result }
}

final class FakeProductivityController: ProductivityControlling {
    var result = true
    private(set) var calls: [String] = []
    func copy() -> Bool { calls.append("copy"); return result }
    func paste() -> Bool { calls.append("paste"); return result }
    func undo() -> Bool { calls.append("undo"); return result }
    func redo() -> Bool { calls.append("redo"); return result }
}

final class FakeAccessibilityChecker: AccessibilityChecking {
    var isTrusted: Bool
    init(isTrusted: Bool = true) { self.isTrusted = isTrusted }
}

/// Convenience bundle so tests can build one `ActionRegistry` and still
/// reach into each fake to assert on it.
struct FakeControllers {
    let media = FakeMediaController()
    let system = FakeSystemController()
    let apps = FakeAppLauncher()
    let window = FakeWindowController()
    let productivity = FakeProductivityController()
    let accessibility = FakeAccessibilityChecker()

    func makeRegistry() -> ActionRegistry {
        ActionRegistry(
            media: media, system: system, apps: apps, window: window, productivity: productivity,
            accessibility: accessibility
        )
    }

    /// All recorded calls across every fake, useful for asserting "nothing else ran".
    var allCalls: [String] {
        media.calls + system.calls + apps.calls + window.calls + productivity.calls
    }
}

// MARK: - TASK-009 live-state fakes
//
// None of these touch real CPU counters, real media applications, or real
// AppleScript. `swift test` never starts playback, never prompts for
// Automation, and never needs Music or Spotify installed.

import Foundation

final class FakeTelemetryProvider: TelemetryProviding {
    var next: SystemMetrics = SystemMetrics()
    private(set) var sampleCount = 0

    func sample() -> SystemMetrics {
        sampleCount += 1
        return next
    }
}

final class FakeMediaProvider: MediaProviding {
    let source: MediaSource
    var isRunning: Bool
    var state: MediaState?
    var artworkData: Data?
    var onFetchArtwork: (() -> Void)?
    private(set) var currentStateCallCount = 0
    private(set) var fetchArtworkCallCount = 0

    init(source: MediaSource, isRunning: Bool = true, state: MediaState? = nil, artworkData: Data? = nil) {
        self.source = source
        self.isRunning = isRunning
        self.state = state
        self.artworkData = artworkData
    }

    func currentState() -> MediaState? {
        currentStateCallCount += 1
        return state
    }

    func fetchArtworkData() -> Data? {
        fetchArtworkCallCount += 1
        onFetchArtwork?()
        return artworkData
    }
}

final class FakeAppleScriptRunner: AppleScriptRunning {
    var result: Result<String, AppleScriptFailure>
    var dataResult: Result<Data, AppleScriptFailure>
    private(set) var runCount = 0
    private(set) var runDataCount = 0

    init(result: Result<String, AppleScriptFailure> = .failure(.unavailable), dataResult: Result<Data, AppleScriptFailure> = .failure(.unavailable)) {
        self.result = result
        self.dataResult = dataResult
    }

    func run(_ source: String) -> Result<String, AppleScriptFailure> {
        runCount += 1
        return result
    }

    func runData(_ source: String) -> Result<Data, AppleScriptFailure> {
        runDataCount += 1
        return dataResult
    }
}

struct FakeWorkspace: RunningApplicationChecking {
    var runningBundleIdentifiers: Set<String> = []

    func isRunning(bundleIdentifier: String) -> Bool {
        runningBundleIdentifiers.contains(bundleIdentifier)
    }
}
