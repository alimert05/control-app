import XCTest
@testable import AliControlMac

/// The desktop→tablet live-state wire format: what it serializes to, and —
/// most importantly — that it can never exceed the BLE payload budget or cut
/// a UTF-8 sequence in half.
final class LiveStateMessageTests: XCTestCase {

    private func json(_ message: some Encodable) throws -> [String: Any] {
        let data = try XCTUnwrap(message.encodedLiveState())
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    // MARK: - Metrics

    func testMetricsSerializeAsWholePercentages() throws {
        let object = try json(MetricsMessage(SystemMetrics(cpuPercent: 34.6, memoryPercent: 47.4)))

        XCTAssertEqual(object["type"] as? String, "metrics")
        XCTAssertEqual(object["cpu"] as? Int, 35)
        XCTAssertEqual(object["memory"] as? Int, 47)
    }

    func testUnavailableMetricsAreOmittedEntirelyRatherThanSentAsZero() throws {
        let object = try json(MetricsMessage(SystemMetrics(cpuPercent: 10, memoryPercent: 20)))

        // Absent means "no data"; 0 would mean "measured, idle". The tablet
        // relies on that distinction to decide between `0%` and `--`.
        XCTAssertNil(object["gpu"])
        XCTAssertNil(object["cpu_temp"])
        XCTAssertNil(object["gpu_temp"])
        XCTAssertEqual(object.count, 3)
    }

    func testAvailableGpuAndTemperatureFieldsSerializeWhenAProviderSuppliesThem() throws {
        // macOS leaves these nil today, but the wire model must already carry
        // them so a Windows agent needs no protocol change.
        let metrics = SystemMetrics(cpuPercent: 1, memoryPercent: 2, gpuPercent: 55.5, cpuTemperatureCelsius: 61.2, gpuTemperatureCelsius: 58.9)

        let object = try json(MetricsMessage(metrics))

        XCTAssertEqual(object["gpu"] as? Int, 56)
        XCTAssertEqual(object["cpu_temp"] as? Int, 61)
        XCTAssertEqual(object["gpu_temp"] as? Int, 59)
    }

    // MARK: - Playback

    func testPlaybackSerializesSourceStateAndTimes() throws {
        let state = MediaState(
            source: .spotify, playbackState: .playing, title: "Song", artist: "Artist",
            positionSeconds: 83.4, durationSeconds: 214.6
        )

        let object = try json(PlaybackMessage(state))

        XCTAssertEqual(object["type"] as? String, "media")
        XCTAssertEqual(object["available"] as? Bool, true)
        XCTAssertEqual(object["source"] as? String, "spotify")
        XCTAssertEqual(object["playing"] as? Bool, true)
        XCTAssertEqual(object["position"] as? Int, 83)
        XCTAssertEqual(object["duration"] as? Int, 215)
    }

    func testPlaybackCarriesNoTrackTextAtAll() throws {
        let state = MediaState(source: .spotify, playbackState: .playing, title: "Song", artist: "Artist", album: "Album")

        let object = try json(PlaybackMessage(state))

        // Track text lives only in the track message; keeping it out of the
        // frequent message is what bounds this one's size by construction.
        XCTAssertNil(object["title"])
        XCTAssertNil(object["artist"])
        XCTAssertNil(object["album"])
    }

    func testNoMediaSerializesAsExplicitlyUnavailable() throws {
        let object = try json(PlaybackMessage(nil))

        XCTAssertEqual(object["available"] as? Bool, false)
        XCTAssertNil(object["source"])
        XCTAssertEqual(object.count, 2)
    }

    func testARunningPlayerWithNoTrackIsAlsoUnavailable() throws {
        let stopped = MediaState(source: .appleMusic, playbackState: .stopped)

        let object = try json(PlaybackMessage(stopped))

        XCTAssertEqual(object["available"] as? Bool, false)
    }

    func testPausedPlaybackIsAvailableButNotPlaying() throws {
        let paused = MediaState(source: .appleMusic, playbackState: .paused, title: "Song")

        let object = try json(PlaybackMessage(paused))

        XCTAssertEqual(object["available"] as? Bool, true)
        XCTAssertEqual(object["playing"] as? Bool, false)
    }

    // MARK: - Track

    func testTrackSerializesMetadata() throws {
        let state = MediaState(source: .spotify, playbackState: .playing, title: "Song", artist: "Artist", album: "Album")

        let object = try json(TrackMessage(state))

        XCTAssertEqual(object["type"] as? String, "track")
        XCTAssertEqual(object["title"] as? String, "Song")
        XCTAssertEqual(object["artist"] as? String, "Artist")
        XCTAssertEqual(object["album"] as? String, "Album")
    }

    // MARK: - Payload size safety

    func testEveryLiveMessageStaysInsideTheNotificationBudget() throws {
        let messages: [Data] = [
            try XCTUnwrap(MetricsMessage(SystemMetrics(cpuPercent: 100, memoryPercent: 100, gpuPercent: 100, cpuTemperatureCelsius: 100, gpuTemperatureCelsius: 100)).encodedLiveState()),
            try XCTUnwrap(PlaybackMessage(MediaState(source: .appleMusic, playbackState: .playing, title: "T", positionSeconds: 99_999, durationSeconds: 99_999)).encodedLiveState()),
            try XCTUnwrap(PlaybackMessage(nil).encodedLiveState())
        ]

        for message in messages {
            XCTAssertLessThanOrEqual(message.count, LiveWireLimits.maxNotificationBytes)
        }
    }

    func testAnAbsurdlyLongTrackStillFitsInOneNotification() throws {
        let state = MediaState(
            source: .spotify,
            playbackState: .playing,
            title: String(repeating: "A very long song title indeed ", count: 40),
            artist: String(repeating: "An extremely long artist name ", count: 40),
            album: String(repeating: "A ridiculously long album name ", count: 40)
        )

        let data = try XCTUnwrap(TrackMessage(state).encodedLiveState())

        XCTAssertLessThanOrEqual(data.count, LiveWireLimits.maxNotificationBytes)
        XCTAssertNotNil(try JSONSerialization.jsonObject(with: data))
    }

    func testLongMultibyteMetadataIsTruncatedWithoutBreakingUtf8OrJson() throws {
        // Every character here is multi-byte; a naive byte-count truncation
        // would split one and produce invalid UTF-8.
        let state = MediaState(
            source: .appleMusic,
            playbackState: .playing,
            title: String(repeating: "東京の夜", count: 30),
            artist: String(repeating: "アーティスト", count: 30),
            album: String(repeating: "アルバム", count: 30)
        )

        let data = try XCTUnwrap(TrackMessage(state).encodedLiveState())

        XCTAssertLessThanOrEqual(data.count, LiveWireLimits.maxNotificationBytes)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let title = try XCTUnwrap(object["title"] as? String)
        // Round-tripping through JSON proves the bytes were valid UTF-8 and
        // the string was cut on a character boundary.
        XCTAssertTrue(title.hasPrefix("東京"))
        XCTAssertFalse(title.contains("\u{FFFD}"), "replacement character means UTF-8 was split")
    }

    func testEmojiAreNeverSplitInTheMiddleOfAGraphemeCluster() {
        let family = "👨‍👩‍👧‍👦"
        let title = String(repeating: family, count: 10)

        let bounded = title.boundedForWire(maxEscapedBytes: LiveWireLimits.maxTitleBytes)

        // Whole clusters only: the result must be a run of complete families.
        XCTAssertTrue(bounded.isEmpty || bounded.allSatisfy { String($0) == family })
        XCTAssertLessThanOrEqual(String.escapedByteCount(bounded), LiveWireLimits.maxTitleBytes)
    }

    func testQuotesAndBackslashesAreBudgetedAtTheirEscapedSize() throws {
        // Each of these costs 2 bytes once escaped; budgeting the raw length
        // would let the encoded message overflow.
        let state = MediaState(
            source: .spotify,
            playbackState: .playing,
            title: String(repeating: "\"\\", count: 60),
            artist: String(repeating: "\"", count: 60),
            album: String(repeating: "\\", count: 60)
        )

        let data = try XCTUnwrap(TrackMessage(state).encodedLiveState())

        XCTAssertLessThanOrEqual(data.count, LiveWireLimits.maxNotificationBytes)
        XCTAssertNotNil(try JSONSerialization.jsonObject(with: data))
    }

    func testShortMetadataIsLeftCompletelyUntouched() {
        let title = "Bohemian Rhapsody"

        XCTAssertEqual(title.boundedForWire(maxEscapedBytes: LiveWireLimits.maxTitleBytes), title)
    }

    func testAZeroBudgetProducesAnEmptyStringRatherThanCrashing() {
        XCTAssertEqual("anything".boundedForWire(maxEscapedBytes: 0), "")
    }

    func testControlCharactersAreCountedAtTheirSixByteEscapedCost() {
        // "\u{01}" encodes as  — six bytes, not one.
        XCTAssertEqual(String.escapedByteCount("\u{01}"), 6)
        XCTAssertEqual(String.escapedByteCount("\""), 2)
        XCTAssertEqual(String.escapedByteCount("a"), 1)
        XCTAssertEqual(String.escapedByteCount("é"), 2)
    }
}
