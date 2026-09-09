import XCTest
@testable import AliControlMac

/// The sampler's behaviour: what it emits, when it stays quiet, and that it
/// only runs while a tablet is actually subscribed.
///
/// Every test drives `tick()` directly rather than waiting on the real timer,
/// so the suite is deterministic and instant.
final class LiveStatePublisherTests: XCTestCase {

    private func makePublisher(
        telemetry: FakeTelemetryProvider = FakeTelemetryProvider(),
        providers: [MediaProviding] = []
    ) -> (LiveStatePublisher, FakeTelemetryProvider, () -> [[String: Any]]) {
        var received: [[String: Any]] = []
        let publisher = LiveStatePublisher(
            telemetry: telemetry,
            media: MediaProviderCoordinator(providers: providers),
            queue: DispatchQueue(label: "test.live-state")
        )
        publisher.onMessage = { data in
            if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                received.append(object)
            }
        }
        return (publisher, telemetry, { received })
    }

    private func playing(_ source: MediaSource, title: String, position: Double = 0) -> MediaState {
        MediaState(
            source: source, playbackState: .playing, title: title, artist: "Artist", album: "Album",
            positionSeconds: position, durationSeconds: 200
        )
    }

    func testATickWithMetricsEmitsAMetricsMessage() {
        let telemetry = FakeTelemetryProvider()
        telemetry.next = SystemMetrics(cpuPercent: 12, memoryPercent: 34)
        let (publisher, _, received) = makePublisher(telemetry: telemetry)

        publisher.tick()

        let metrics = received().first { $0["type"] as? String == "metrics" }
        XCTAssertEqual(metrics?["cpu"] as? Int, 12)
        XCTAssertEqual(metrics?["memory"] as? Int, 34)
    }

    func testATickWithNoMeasurableMetricsEmitsNoMetricsMessage() {
        // e.g. the very first sample, before CPU has a baseline and if memory
        // were unavailable too — better to send nothing than an empty message.
        let (publisher, _, received) = makePublisher()

        publisher.tick()

        XCTAssertTrue(received().allSatisfy { $0["type"] as? String != "metrics" })
    }

    func testTrackMetadataIsSentOnceAndOnlyResentWhenTheTrackChanges() {
        let provider = FakeMediaProvider(source: .spotify, state: playing(.spotify, title: "First"))
        let (publisher, _, received) = makePublisher(providers: [provider])

        publisher.tick()
        publisher.tick()
        publisher.tick()

        XCTAssertEqual(received().filter { $0["type"] as? String == "track" }.count, 1)

        provider.state = playing(.spotify, title: "Second")
        publisher.tick()

        let tracks = received().filter { $0["type"] as? String == "track" }
        XCTAssertEqual(tracks.count, 2)
        XCTAssertEqual(tracks.last?["title"] as? String, "Second")
    }

    func testPlaybackIsResentWhileThePositionAdvancesButGoesQuietWhenNothingChanges() {
        let provider = FakeMediaProvider(source: .spotify, state: playing(.spotify, title: "Song", position: 10))
        let (publisher, _, received) = makePublisher(providers: [provider])

        publisher.tick()
        provider.state = playing(.spotify, title: "Song", position: 12)
        publisher.tick()
        // Same position twice: nothing new to say.
        publisher.tick()

        let playback = received().filter { $0["type"] as? String == "media" }
        XCTAssertEqual(playback.count, 2)
        XCTAssertEqual(playback.last?["position"] as? Int, 12)
    }

    func testMediaBecomingUnavailableIsAnnouncedOnceThenStaysQuiet() {
        let provider = FakeMediaProvider(source: .spotify, state: playing(.spotify, title: "Song"))
        let (publisher, _, received) = makePublisher(providers: [provider])
        publisher.tick()

        provider.state = nil
        publisher.tick()
        publisher.tick()

        let unavailable = received().filter { $0["type"] as? String == "media" && ($0["available"] as? Bool) == false }
        XCTAssertEqual(unavailable.count, 1, "the tablet needs to be told once, not every two seconds")
    }

    func testTheSameTrackIsResentAfterMediaBecomesAvailableAgain() {
        let provider = FakeMediaProvider(source: .spotify, state: playing(.spotify, title: "Song"))
        let (publisher, _, received) = makePublisher(providers: [provider])
        publisher.tick()

        provider.state = nil
        publisher.tick()
        provider.state = playing(.spotify, title: "Song")
        publisher.tick()

        // Track identity was forgotten while unavailable, so the tablet gets
        // the metadata again rather than being left with an empty widget.
        XCTAssertEqual(received().filter { $0["type"] as? String == "track" }.count, 2)
    }

    func testTrackChangeAnnouncesArtworkCancellationBeforePublishingNewTrack() {
        let provider = FakeMediaProvider(source: .spotify, state: playing(.spotify, title: "First"))
        let publisher = LiveStatePublisher(
            telemetry: FakeTelemetryProvider(), media: MediaProviderCoordinator(providers: [provider]),
            queue: DispatchQueue(label: "test.live-state.artwork-cancel")
        )
        var events: [String] = []
        publisher.onArtworkChanged = { _ in events.append("artwork") }
        publisher.onMessage = { data in
            if (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["type"] as? String == "track" {
                events.append("track")
            }
        }

        publisher.tick()

        XCTAssertEqual(events, ["artwork", "track"])
    }

    func testTrackChangingDuringArtworkFetchSuppressesStaleTrackAndPlayback() {
        let first = playing(.appleMusic, title: "First")
        let provider = FakeMediaProvider(source: .appleMusic, state: first, artworkData: Data([1]))
        provider.onFetchArtwork = { provider.state = self.playing(.appleMusic, title: "Second") }
        let (publisher, _, received) = makePublisher(providers: [provider])

        publisher.tick()

        XCTAssertTrue(received().allSatisfy { ($0["type"] as? String) != "track" && ($0["type"] as? String) != "media" })
    }

    func testNoMediaApplicationsMeansOneUnavailableMessageAndNoTrackMessages() {
        let (publisher, _, received) = makePublisher(providers: [])

        publisher.tick()

        XCTAssertEqual(received().filter { $0["type"] as? String == "track" }.count, 0)
        let playback = received().filter { $0["type"] as? String == "media" }
        XCTAssertEqual(playback.count, 1)
        XCTAssertEqual(playback.first?["available"] as? Bool, false)
    }

    func testEveryEmittedMessageFitsTheNotificationBudget() {
        let telemetry = FakeTelemetryProvider()
        telemetry.next = SystemMetrics(cpuPercent: 99, memoryPercent: 99)
        let provider = FakeMediaProvider(
            source: .appleMusic,
            state: MediaState(
                source: .appleMusic, playbackState: .playing,
                title: String(repeating: "長い曲名", count: 50),
                artist: String(repeating: "アーティスト名", count: 50),
                album: String(repeating: "アルバム名", count: 50),
                positionSeconds: 12_345, durationSeconds: 54_321
            )
        )
        var sizes: [Int] = []
        let publisher = LiveStatePublisher(
            telemetry: telemetry,
            media: MediaProviderCoordinator(providers: [provider]),
            queue: DispatchQueue(label: "test.live-state.size")
        )
        publisher.onMessage = { sizes.append($0.count) }

        publisher.tick()

        XCTAssertFalse(sizes.isEmpty)
        for size in sizes {
            XCTAssertLessThanOrEqual(size, LiveWireLimits.maxNotificationBytes)
        }
    }

    // MARK: - Lifecycle

    func testSamplingOnlyRunsBetweenStartAndStop() {
        let (publisher, telemetry, _) = makePublisher()
        XCTAssertFalse(publisher.isRunning)

        publisher.start()
        drainQueue(publisher)
        XCTAssertTrue(publisher.isRunning)
        // start() primes a CPU baseline immediately so the first real tick
        // can report a delta.
        XCTAssertEqual(telemetry.sampleCount, 1)

        publisher.stop()
        drainQueue(publisher)
        XCTAssertFalse(publisher.isRunning)
    }

    func testStartingTwiceDoesNotCreateASecondSampler() {
        let (publisher, telemetry, _) = makePublisher()

        publisher.start()
        publisher.start()
        drainQueue(publisher)

        XCTAssertTrue(publisher.isRunning)
        XCTAssertEqual(telemetry.sampleCount, 1, "a duplicate subscribe callback must not double the sampling rate")

        publisher.stop()
        drainQueue(publisher)
    }

    func testAReconnectResendsCurrentStateInsteadOfDiffingAgainstThePreviousSession() {
        let provider = FakeMediaProvider(source: .spotify, state: playing(.spotify, title: "Song"))
        let (publisher, _, received) = makePublisher(providers: [provider])

        publisher.tick()
        XCTAssertEqual(received().filter { $0["type"] as? String == "track" }.count, 1)

        // Tablet drops and comes back with the same track still playing.
        publisher.stop()
        drainQueue(publisher)
        publisher.start()
        drainQueue(publisher)
        publisher.tick()

        XCTAssertEqual(
            received().filter { $0["type"] as? String == "track" }.count, 2,
            "a reconnecting tablet starts with no state and must be told everything again"
        )
    }

    /// `start`/`stop` hop onto the publisher's serial queue; this waits for
    /// that work to finish so assertions don't race it.
    private func drainQueue(_ publisher: LiveStatePublisher) {
        let expectation = expectation(description: "publisher queue drained")
        DispatchQueue(label: "test.drain").asyncAfter(deadline: .now() + 0.05) { expectation.fulfill() }
        wait(for: [expectation], timeout: 2)
    }
}
