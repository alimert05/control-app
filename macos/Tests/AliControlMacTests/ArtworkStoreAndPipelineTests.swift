import XCTest
@testable import AliControlMac

final class ArtworkStoreTests: XCTestCase {

    private func artwork(id: String, byte: UInt8 = 0xFF) -> ProcessedArtwork {
        ProcessedArtwork(artworkId: id, data: Data([byte]), mimeType: "image/jpeg")
    }

    func testStoredArtworkIsFoundByItsOwnId() {
        let store = ArtworkStore()
        store.store(artwork(id: "aaaa"), forTrackIdentity: "track-a")

        XCTAssertEqual(store.artwork(forId: "aaaa"), artwork(id: "aaaa"))
    }

    func testAnUnknownIdIsNotFound() {
        let store = ArtworkStore()
        store.store(artwork(id: "aaaa"), forTrackIdentity: "track-a")

        XCTAssertNil(store.artwork(forId: "bbbb"))
    }

    func testCachedResultDistinguishesNeverCheckedFromConfirmedNoArtwork() {
        let store = ArtworkStore()

        XCTAssertNil(store.cachedResult(forTrackIdentity: "track-a"), "never checked")

        store.storeNoArtwork(forTrackIdentity: "track-a")

        XCTAssertEqual(store.cachedResult(forTrackIdentity: "track-a"), .some(nil), "checked, confirmed none")
    }

    func testCachedResultReturnsThePreviouslyProcessedArtworkForARepeatedTrack() {
        let store = ArtworkStore()
        store.store(artwork(id: "aaaa"), forTrackIdentity: "track-a")

        XCTAssertEqual(store.cachedResult(forTrackIdentity: "track-a"), .some(artwork(id: "aaaa")))
    }

    func testTheArtworkIdCacheEvictsTheLeastRecentlyUsedEntryOnceOverCapacity() {
        let store = ArtworkStore(capacity: 2)
        store.store(artwork(id: "1111"), forTrackIdentity: "t1")
        store.store(artwork(id: "2222"), forTrackIdentity: "t2")
        store.store(artwork(id: "3333"), forTrackIdentity: "t3")

        XCTAssertNil(store.artwork(forId: "1111"), "oldest entry should have been evicted")
        XCTAssertNotNil(store.artwork(forId: "2222"))
        XCTAssertNotNil(store.artwork(forId: "3333"))
    }

    func testReadingAnEntryCountsAsRecentUseAndProtectsItFromEviction() {
        let store = ArtworkStore(capacity: 2)
        store.store(artwork(id: "1111"), forTrackIdentity: "t1")
        store.store(artwork(id: "2222"), forTrackIdentity: "t2")

        _ = store.artwork(forId: "1111") // touch 1111, making 2222 the LRU one

        store.store(artwork(id: "3333"), forTrackIdentity: "t3")

        XCTAssertNotNil(store.artwork(forId: "1111"), "recently touched, should survive")
        XCTAssertNil(store.artwork(forId: "2222"), "least recently used, should be evicted")
    }

    func testTheTrackIdentityCacheIsBoundedIndependentlyOfTheArtworkIdCache() {
        let store = ArtworkStore(capacity: 2)
        store.storeNoArtwork(forTrackIdentity: "t1")
        store.storeNoArtwork(forTrackIdentity: "t2")
        store.storeNoArtwork(forTrackIdentity: "t3")

        XCTAssertNil(store.cachedResult(forTrackIdentity: "t1"))
        XCTAssertEqual(store.cachedResult(forTrackIdentity: "t3"), .some(nil))
    }
}

/// The orchestration layer: fetch (via a fake coordinator-shaped closure),
/// process once per track, cache both outcomes. No real AppleScript, no real
/// Music/Spotify.
final class ArtworkPipelineTests: XCTestCase {

    private func state(title: String, source: MediaSource = .appleMusic) -> MediaState {
        MediaState(source: source, playbackState: .playing, title: title, artist: "Artist")
    }

    private func makeCoordinator(artworkData: Data?) -> MediaProviderCoordinator {
        let provider = FakeMediaProvider(source: .appleMusic, isRunning: true, state: state(title: "Song A"), artworkData: artworkData)
        let coordinator = MediaProviderCoordinator(providers: [provider])
        _ = coordinator.currentState() // selects .appleMusic as lastSelectedSource
        return coordinator
    }

    func testProcessesAndReturnsArtworkWhenTheProviderHasSomeToOffer() {
        let raw = Data(repeating: 0x11, count: 4)
        var processedCallCount = 0
        let pipeline = ArtworkPipeline(processor: { data in
            processedCallCount += 1
            return ProcessedArtwork(artworkId: "cafefeed00000000", data: data, mimeType: "image/jpeg")
        })

        let result = pipeline.artwork(for: state(title: "Song A"), coordinator: makeCoordinator(artworkData: raw))

        guard case .ready(let artwork) = result else { return XCTFail("expected processed artwork") }
        XCTAssertEqual(artwork.artworkId, "cafefeed00000000")
        XCTAssertEqual(processedCallCount, 1)
    }

    func testReturnsNilWithoutProcessingWhenTheProviderHasNoRawArtwork() {
        var processedCallCount = 0
        let pipeline = ArtworkPipeline(processor: { _ in processedCallCount += 1; return nil })

        let result = pipeline.artwork(for: state(title: "Song A"), coordinator: makeCoordinator(artworkData: nil))

        XCTAssertEqual(result, .unavailable)
        XCTAssertEqual(processedCallCount, 0, "no raw bytes means nothing to process")
    }

    func testAFailedProcessingAttemptIsAlsoCachedAsNoArtwork() {
        var processedCallCount = 0
        let pipeline = ArtworkPipeline(processor: { _ in processedCallCount += 1; return nil })
        let trackState = state(title: "Song A")

        _ = pipeline.artwork(for: trackState, coordinator: makeCoordinator(artworkData: Data([0x01])))
        _ = pipeline.artwork(for: trackState, coordinator: makeCoordinator(artworkData: Data([0x01])))

        XCTAssertEqual(processedCallCount, 1, "a decode failure must not be retried on every tick for the same track")
    }

    func testTheSameTrackIsNeverProcessedTwice() {
        var processedCallCount = 0
        let pipeline = ArtworkPipeline(processor: { data in
            processedCallCount += 1
            return ProcessedArtwork(artworkId: "cafefeed00000000", data: data, mimeType: "image/jpeg")
        })
        let trackState = state(title: "Song A")
        let coordinator = makeCoordinator(artworkData: Data([0x01]))

        let first = pipeline.artwork(for: trackState, coordinator: coordinator)
        let second = pipeline.artwork(for: trackState, coordinator: coordinator)

        XCTAssertEqual(first, second)
        XCTAssertEqual(processedCallCount, 1, "revisiting the same track must reuse the cached result, not re-fetch/re-process")
    }

    func testADifferentTrackIsProcessedIndependently() {
        var processedCallCount = 0
        let pipeline = ArtworkPipeline(processor: { data in
            processedCallCount += 1
            return ProcessedArtwork(artworkId: "cafefeed0000000\(processedCallCount)", data: data, mimeType: "image/jpeg")
        })
        let provider = FakeMediaProvider(source: .appleMusic, state: state(title: "Song A"), artworkData: Data([0x01]))
        let coordinator = MediaProviderCoordinator(providers: [provider])
        _ = coordinator.currentState()
        _ = pipeline.artwork(for: state(title: "Song A"), coordinator: coordinator)
        provider.state = state(title: "Song B")
        _ = coordinator.currentState()
        _ = pipeline.artwork(for: state(title: "Song B"), coordinator: coordinator)

        XCTAssertEqual(processedCallCount, 2)
    }

    func testLookupFindsPreviouslyProcessedArtworkByItsId() {
        let pipeline = ArtworkPipeline(processor: { data in
            ProcessedArtwork(artworkId: "cafefeed00000000", data: data, mimeType: "image/jpeg")
        })
        _ = pipeline.artwork(for: state(title: "Song A"), coordinator: makeCoordinator(artworkData: Data([0x01])))

        XCTAssertNotNil(pipeline.lookup(artworkId: "cafefeed00000000"))
    }

    func testLookupOfAnUnknownIdFindsNothing() {
        let pipeline = ArtworkPipeline()

        XCTAssertNil(pipeline.lookup(artworkId: "0000000000000000"))
    }

    func testTrackChangingDuringArtworkFetchIsNotProcessedOrCachedAgainstOldTrack() {
        let oldState = state(title: "Song A")
        let provider = FakeMediaProvider(source: .appleMusic, state: oldState, artworkData: Data([0x01]))
        let coordinator = MediaProviderCoordinator(providers: [provider])
        _ = coordinator.currentState()
        provider.onFetchArtwork = { provider.state = self.state(title: "Song B") }
        var processCount = 0
        let pipeline = ArtworkPipeline(processor: { _ in processCount += 1; return nil })

        XCTAssertEqual(pipeline.artwork(for: oldState, coordinator: coordinator), .trackChanged)
        XCTAssertEqual(processCount, 0)
        XCTAssertNil(pipeline.lookup(artworkId: "cafefeed00000000"))
    }
}
