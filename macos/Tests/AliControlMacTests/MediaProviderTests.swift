import XCTest
@testable import AliControlMac

/// Media providers and the selection policy, driven entirely through fakes.
/// No test here needs Music or Spotify installed, starts or changes playback,
/// or sends a real Apple event.
final class MediaProviderTests: XCTestCase {

    private let separator = ScriptedMediaProvider.fieldSeparator

    private func reply(state: String, title: String, artist: String, album: String, position: String, duration: String) -> String {
        [state, title, artist, album, position, duration].joined(separator: separator)
    }

    // MARK: - Parsing

    func testParsesAFullPlayingReply() {
        let state = ScriptedMediaProvider.parse(
            reply(state: "playing", title: "Song", artist: "Artist", album: "Album", position: "83", duration: "214"),
            source: .spotify
        )

        XCTAssertEqual(state?.source, .spotify)
        XCTAssertEqual(state?.playbackState, .playing)
        XCTAssertEqual(state?.title, "Song")
        XCTAssertEqual(state?.artist, "Artist")
        XCTAssertEqual(state?.album, "Album")
        XCTAssertEqual(state?.positionSeconds, 83)
        XCTAssertEqual(state?.durationSeconds, 214)
        XCTAssertTrue(state!.isPlaying)
        XCTAssertTrue(state!.hasTrack)
    }

    func testParsesAPausedReply() {
        let state = ScriptedMediaProvider.parse(
            reply(state: "paused", title: "Song", artist: "Artist", album: "Album", position: "0", duration: "100"),
            source: .appleMusic
        )

        XCTAssertEqual(state?.playbackState, .paused)
        XCTAssertFalse(state!.isPlaying)
        // Paused still has a track worth showing.
        XCTAssertTrue(state!.hasTrack)
    }

    func testParsesTheBareStoppedReplyAsRunningButWithoutATrack() {
        let state = ScriptedMediaProvider.parse("stopped", source: .spotify)

        XCTAssertEqual(state?.playbackState, .stopped)
        XCTAssertFalse(state!.hasTrack)
        XCTAssertNil(state?.title)
    }

    func testEmptyMetadataFieldsBecomeNilRatherThanEmptyStrings() {
        let state = ScriptedMediaProvider.parse(
            reply(state: "playing", title: "Song", artist: "", album: "", position: "1", duration: "2"),
            source: .spotify
        )

        XCTAssertEqual(state?.title, "Song")
        XCTAssertNil(state?.artist)
        XCTAssertNil(state?.album)
    }

    func testNonNumericOrNegativeTimesAreRejected() {
        let state = ScriptedMediaProvider.parse(
            reply(state: "playing", title: "Song", artist: "A", album: "B", position: "not a number", duration: "-5"),
            source: .spotify
        )

        XCTAssertNil(state?.positionSeconds)
        XCTAssertNil(state?.durationSeconds)
    }

    func testMalformedRepliesFailSafely() {
        XCTAssertNil(ScriptedMediaProvider.parse("", source: .spotify))
        XCTAssertNil(ScriptedMediaProvider.parse("   ", source: .spotify))
        XCTAssertNil(ScriptedMediaProvider.parse("something unexpected", source: .spotify))
    }

    func testMultibyteMetadataSurvivesParsingIntact() {
        let title = "Über Straße 東京 🎧"
        let state = ScriptedMediaProvider.parse(
            reply(state: "playing", title: title, artist: "Ärtist", album: "Ålbum", position: "1", duration: "2"),
            source: .appleMusic
        )

        XCTAssertEqual(state?.title, title)
        XCTAssertEqual(state?.artist, "Ärtist")
    }

    // MARK: - Provider behaviour

    func testAnApplicationThatIsNotRunningIsNeverScripted() {
        let runner = FakeAppleScriptRunner(result: .success("playing"))
        let provider = ScriptedMediaProvider.spotify(
            runner: runner,
            workspace: FakeWorkspace(runningBundleIdentifiers: [])
        )

        XCTAssertFalse(provider.isRunning)
        XCTAssertNil(provider.currentState())
        // Critical: scripting a non-running app would launch it.
        XCTAssertEqual(runner.runCount, 0)
    }

    func testSpotifyUnavailableWhenNotInstalledOrNotRunning() {
        let provider = ScriptedMediaProvider.spotify(
            runner: FakeAppleScriptRunner(result: .failure(.unavailable)),
            workspace: FakeWorkspace(runningBundleIdentifiers: [])
        )

        XCTAssertNil(provider.currentState())
    }

    func testAppleMusicUnavailableWhenNotRunning() {
        let provider = ScriptedMediaProvider.appleMusic(
            runner: FakeAppleScriptRunner(result: .failure(.unavailable)),
            workspace: FakeWorkspace(runningBundleIdentifiers: [])
        )

        XCTAssertNil(provider.currentState())
    }

    func testARunningApplicationThatErrorsSimplyReportsNoState() {
        let provider = ScriptedMediaProvider.appleMusic(
            runner: FakeAppleScriptRunner(result: .failure(.unavailable)),
            workspace: FakeWorkspace(runningBundleIdentifiers: ["com.apple.Music"])
        )

        XCTAssertNil(provider.currentState())
        XCTAssertFalse(provider.isAutomationDenied, "a transient error must not latch as a permission denial")
    }

    func testDeniedAutomationLatchesAndStopsSendingFurtherAppleEvents() {
        let runner = FakeAppleScriptRunner(result: .failure(.notPermitted))
        let provider = ScriptedMediaProvider.spotify(
            runner: runner,
            workspace: FakeWorkspace(runningBundleIdentifiers: ["com.spotify.client"])
        )

        XCTAssertNil(provider.currentState())
        XCTAssertTrue(provider.isAutomationDenied)

        // Every later tick must be silent — re-issuing the event is exactly
        // what would re-prompt the user.
        for _ in 0..<10 { XCTAssertNil(provider.currentState()) }
        XCTAssertEqual(runner.runCount, 1)
    }

    func testARunningPermittedApplicationIsScriptedAndParsed() {
        let runner = FakeAppleScriptRunner(
            result: .success(reply(state: "playing", title: "T", artist: "A", album: "B", position: "5", duration: "10"))
        )
        let provider = ScriptedMediaProvider.spotify(
            runner: runner,
            workspace: FakeWorkspace(runningBundleIdentifiers: ["com.spotify.client"])
        )

        let state = provider.currentState()

        XCTAssertEqual(state?.title, "T")
        XCTAssertEqual(state?.source, .spotify)
        XCTAssertEqual(runner.runCount, 1)
    }

    // MARK: - Selection policy

    private func state(_ source: MediaSource, _ playback: MediaPlaybackState, title: String = "Song") -> MediaState {
        MediaState(source: source, playbackState: playback, title: title, artist: "Artist")
    }

    func testAPlayingSourceWinsOverAPausedOne() {
        let selected = MediaProviderCoordinator.select(
            from: [state(.appleMusic, .paused), state(.spotify, .playing)],
            preferring: nil
        )

        XCTAssertEqual(selected?.source, .spotify)
    }

    func testWhenTwoSourcesPlayThePreviouslyChosenOneKeepsTheWidget() {
        let states = [state(.appleMusic, .playing), state(.spotify, .playing)]

        XCTAssertEqual(MediaProviderCoordinator.select(from: states, preferring: .spotify)?.source, .spotify)
        XCTAssertEqual(MediaProviderCoordinator.select(from: states, preferring: .appleMusic)?.source, .appleMusic)
    }

    func testWithNoPreviousChoiceTwoPlayingSourcesResolveByPriorityOrder() {
        // Providers arrive in priority order (Apple Music first).
        let selected = MediaProviderCoordinator.select(
            from: [state(.appleMusic, .playing), state(.spotify, .playing)],
            preferring: nil
        )

        XCTAssertEqual(selected?.source, .appleMusic)
    }

    func testPausingDoesNotHandTheWidgetToAnotherIdlePlayer() {
        let selected = MediaProviderCoordinator.select(
            from: [state(.appleMusic, .paused), state(.spotify, .paused)],
            preferring: .spotify
        )

        XCTAssertEqual(selected?.source, .spotify)
    }

    func testASourceWithoutATrackIsNeverSelected() {
        let stopped = MediaState(source: .spotify, playbackState: .stopped)

        XCTAssertNil(MediaProviderCoordinator.select(from: [stopped], preferring: .spotify))
    }

    func testNothingRunningMeansMediaIsUnavailable() {
        XCTAssertNil(MediaProviderCoordinator.select(from: [], preferring: .appleMusic))
    }

    func testCoordinatorOnlyConsultsRunningProvidersAndRemembersItsChoice() {
        let music = FakeMediaProvider(source: .appleMusic, isRunning: false, state: state(.appleMusic, .playing))
        let spotify = FakeMediaProvider(source: .spotify, isRunning: true, state: state(.spotify, .playing))
        let coordinator = MediaProviderCoordinator(providers: [music, spotify])

        XCTAssertEqual(coordinator.currentState()?.source, .spotify)
        XCTAssertEqual(music.currentStateCallCount, 0, "a non-running provider must not be queried")

        // Now both are playing: the sticky choice keeps Spotify rather than
        // flipping to the higher-priority source mid-track.
        music.isRunning = true
        XCTAssertEqual(coordinator.currentState()?.source, .spotify)
    }

    func testResetClearsTheStickyPreferenceForTheNextSession() {
        let music = FakeMediaProvider(source: .appleMusic, isRunning: true, state: state(.appleMusic, .playing))
        let spotify = FakeMediaProvider(source: .spotify, isRunning: true, state: state(.spotify, .playing))
        let coordinator = MediaProviderCoordinator(providers: [spotify, music])

        XCTAssertEqual(coordinator.currentState()?.source, .spotify)

        coordinator.reset()

        // With no memory, order decides again.
        XCTAssertEqual(coordinator.currentState()?.source, .spotify)
    }

    func testCoordinatorReportsNothingWhenNoProviderIsRunning() {
        let coordinator = MediaProviderCoordinator(providers: [
            FakeMediaProvider(source: .appleMusic, isRunning: false),
            FakeMediaProvider(source: .spotify, isRunning: false)
        ])

        XCTAssertNil(coordinator.currentState())
    }
}
