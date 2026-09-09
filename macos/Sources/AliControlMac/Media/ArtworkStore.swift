import Foundation

/// A small, bounded cache of processed artwork, keyed by `artworkId`.
///
/// Two independent things are cached, both bounded, both LRU:
///
/// - **by track identity** — so replaying/re-visiting the same track within a
///   session never re-runs the AppleScript fetch + resize + compress
///   pipeline (the expensive part);
/// - **by artworkId** — what `PeripheralManager` actually looks up when a
///   `artwork_request` arrives, so a previously-sent (or previously-seen but
///   never-requested) artwork can be re-sent instantly without touching a
///   media application again.
///
/// Plain `NSLock`-guarded dictionaries plus an access-order array: the
/// dataset is tiny (at most a handful of ~20 KB entries) and accessed
/// rarely (once per track change, plus the occasional request), so there is
/// no need for anything fancier. Access is synchronized because
/// `ArtworkPipeline` populates this from the live-state queue while
/// `PeripheralManager` reads it from the main queue.
final class ArtworkStore {

    private let capacity: Int
    private let lock = NSLock()

    private var artworkById: [String: ProcessedArtwork] = [:]
    private var artworkIdOrder: [String] = []

    private var processedByTrackIdentity: [String: ProcessedArtwork?] = [:]
    private var trackIdentityOrder: [String] = []

    init(capacity: Int = 8) {
        self.capacity = capacity
    }

    /// Stores [artwork] under both its own id and the track it belongs to,
    /// evicting the least-recently-used entry of each kind if over capacity.
    func store(_ artwork: ProcessedArtwork, forTrackIdentity trackIdentity: String) {
        lock.lock()
        defer { lock.unlock() }
        insert(artwork, into: &artworkById, order: &artworkIdOrder, key: artwork.artworkId)
        insert(artwork, into: &processedByTrackIdentity, order: &trackIdentityOrder, key: trackIdentity)
    }

    /// Remembers that [trackIdentity] was checked and has **no** artwork, so
    /// a still-playing track that keeps re-appearing every 2-second tick
    /// never re-triggers the fetch attempt. Distinct from "not yet checked"
    /// (a plain cache miss).
    func storeNoArtwork(forTrackIdentity trackIdentity: String) {
        lock.lock()
        defer { lock.unlock() }
        insert(nil, into: &processedByTrackIdentity, order: &trackIdentityOrder, key: trackIdentity)
    }

    /// nil = never checked; `.some(nil)` = checked, confirmed none; `.some(.some)` = ready to reuse.
    func cachedResult(forTrackIdentity trackIdentity: String) -> ProcessedArtwork?? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = processedByTrackIdentity[trackIdentity] else { return nil }
        touch(&trackIdentityOrder, key: trackIdentity)
        return .some(entry)
    }

    func artwork(forId artworkId: String) -> ProcessedArtwork? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = artworkById[artworkId] else { return nil }
        touch(&artworkIdOrder, key: artworkId)
        return entry
    }

    /// Every currently-cached artworkId — used by tests, and potentially
    /// useful for diagnostics; never sent over the wire.
    var cachedArtworkIds: [String] {
        lock.lock()
        defer { lock.unlock() }
        return artworkIdOrder
    }

    private func insert<Value>(_ value: Value, into dictionary: inout [String: Value], order: inout [String], key: String) {
        if dictionary[key] == nil, order.count >= capacity, let oldest = order.first {
            order.removeFirst()
            dictionary.removeValue(forKey: oldest)
        }
        dictionary[key] = value
        touch(&order, key: key)
    }

    private func touch(_ order: inout [String], key: String) {
        order.removeAll { $0 == key }
        order.append(key)
    }
}
