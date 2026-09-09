import Foundation

/// Orchestrates artwork for the currently playing track: fetch the raw bytes
/// from whichever provider is selected, process them once, and cache both
/// the outcome (including "this track has none") and the processed result.
///
/// `LiveStatePublisher` calls [artwork(for:coordinator:)] **only when the
/// track identity changes** — never every 2-second tick — which is what
/// keeps this from re-running AppleScript and JPEG compression repeatedly
/// for a track that's simply still playing.
final class ArtworkPipeline {

    enum Result: Equatable {
        case ready(ProcessedArtwork)
        case unavailable
        case trackChanged
    }

    private let store: ArtworkStore
    private let processor: (Data) -> ProcessedArtwork?

    init(store: ArtworkStore = ArtworkStore(), processor: @escaping (Data) -> ProcessedArtwork? = ArtworkProcessor.process) {
        self.store = store
        self.processor = processor
    }

    /// The processed artwork for [state]'s track, fetching and processing it
    /// at most once per distinct `trackIdentity`. Returns nil when the track
    /// has no artwork, the source can't supply it, or processing failed —
    /// every one of those is cached too, so it's asked at most once.
    func artwork(for state: MediaState, coordinator: MediaProviderCoordinator) -> Result {
        let trackIdentity = state.trackIdentity

        if let cached = store.cachedResult(forTrackIdentity: trackIdentity) {
            return cached.map(Result.ready) ?? .unavailable
        }

        let raw: Data
        switch coordinator.fetchArtworkData(matching: state) {
        case .data(let data): raw = data
        case .unavailable:
            store.storeNoArtwork(forTrackIdentity: trackIdentity)
            return .unavailable
        case .trackChanged:
            return .trackChanged
        }
        guard let processed = processor(raw) else {
            store.storeNoArtwork(forTrackIdentity: trackIdentity)
            return .unavailable
        }
        store.store(processed, forTrackIdentity: trackIdentity)
        return .ready(processed)
    }

    /// What `PeripheralManager` looks up when Android sends `artwork_request`.
    /// Only ever a dictionary read against already-processed, already-bounded
    /// data — never a filesystem path, never a fresh fetch.
    func lookup(artworkId: String) -> ProcessedArtwork? {
        store.artwork(forId: artworkId)
    }
}
