import Foundation

/// Samples system telemetry and media state on a single low-frequency timer
/// and emits the resulting BLE notification payloads.
///
/// ## Lifecycle
///
/// The publisher runs **only while a tablet is subscribed**. `PeripheralManager`
/// starts it on `didSubscribeTo` and stops it on `didUnsubscribeFrom` (and on
/// Bluetooth power-off), so an unattended Mac does no telemetry work at all:
/// no timer, no Mach calls, no Apple events.
///
/// ## Cost
///
/// One `DispatchSourceTimer` for the whole app — never one per widget, per
/// page, or per metric. Each tick is two Mach `host_statistics` calls
/// (microseconds) plus, *only for media applications that are already
/// running*, one read-only Apple event each. At the default 2-second interval
/// that is at most one scripting call per app per 2 seconds, which is what
/// TASK-009 asks for and is far below any level that would show up in
/// Activity Monitor.
///
/// ## What gets sent
///
/// - **Metrics** every tick that produces any value.
/// - **Playback** only when it actually differs from the last one sent.
///   While a track plays this is every tick (position advances); while paused
///   or with nothing playable it goes quiet on its own, with no extra
///   change-tracking logic.
/// - **Track identity** only when the track actually changes. This is the one
///   message carrying unbounded text, so keeping it event-driven is what
///   stops metadata from dominating the BLE link.
final class LiveStatePublisher {

    /// Default sampling interval. Centralized here (not sprinkled through the
    /// code) so it is one line to change.
    static let defaultInterval: TimeInterval = 2.0

    /// Called with each encoded notification payload, on `queue`.
    var onMessage: ((Data) -> Void)?

    /// Announces the artwork identity for the newly accepted track. A nil
    /// value means any queued transfer is stale and must be discarded.
    var onArtworkChanged: ((String?) -> Void)?

    private let telemetry: TelemetryProviding
    private let media: MediaProviderCoordinator
    private let artwork: ArtworkPipeline
    private let interval: TimeInterval
    private let queue: DispatchQueue

    private var timer: DispatchSourceTimer?
    private var lastPlayback: PlaybackMessage?
    private var lastTrackIdentity: String?

    init(
        telemetry: TelemetryProviding = SystemTelemetryProvider(),
        media: MediaProviderCoordinator = MediaProviderCoordinator(),
        artwork: ArtworkPipeline = ArtworkPipeline(),
        interval: TimeInterval = LiveStatePublisher.defaultInterval,
        // A dedicated serial queue, deliberately not the main queue: an
        // unresponsive media application must never be able to stall
        // CoreBluetooth's delegate callbacks and delay a command ack.
        // NSAppleScript is not thread-safe, so every scripting call is made
        // from this one queue and never concurrently.
        queue: DispatchQueue = DispatchQueue(label: "com.alicontrol.live-state")
    ) {
        self.telemetry = telemetry
        self.media = media
        self.artwork = artwork
        self.interval = interval
        self.queue = queue
    }

    /// Looks up previously processed artwork by id — what `PeripheralManager`
    /// calls when Android sends `artwork_request`. Safe to call from any
    /// queue; `ArtworkStore` is internally synchronized.
    func processedArtwork(for artworkId: String) -> ProcessedArtwork? {
        artwork.lookup(artworkId: artworkId)
    }

    var isRunning: Bool { timer != nil }

    /// Begins sampling. Safe to call when already running (no-op), so a
    /// duplicate subscribe callback cannot create a second timer.
    func start() {
        queue.async { [weak self] in
            guard let self, self.timer == nil else { return }

            // A fresh session: forget what the previous tablet was told, so
            // the reconnecting one receives full current state rather than
            // being diffed against a stale baseline.
            self.lastPlayback = nil
            self.lastTrackIdentity = nil
            self.media.reset()
            (self.telemetry as? SystemTelemetryProvider)?.reset()

            // CPU usage is a delta, so prime a baseline now; the first real
            // tick then reports the interval since this moment instead of
            // reporting nothing.
            _ = self.telemetry.sample()

            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + self.interval, repeating: self.interval, leeway: .milliseconds(250))
            timer.setEventHandler { [weak self] in self?.tick() }
            self.timer = timer
            timer.resume()
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.timer?.cancel()
            self.timer = nil
            self.lastPlayback = nil
            self.lastTrackIdentity = nil
            self.media.reset()
            (self.telemetry as? SystemTelemetryProvider)?.reset()
        }
    }

    /// One sampling pass. Exposed (internal) so tests can drive it directly
    /// rather than waiting on wall-clock time.
    func tick() {
        let metrics = telemetry.sample()
        if metrics.hasAnyValue, let data = MetricsMessage(metrics).encodedLiveState() {
            onMessage?(data)
        }

        let state = media.currentState()

        if let state, state.hasTrack, state.trackIdentity != lastTrackIdentity {
            // Artwork is fetched/processed here — once per *track change*,
            // never once per tick — and only the resulting id (if any) rides
            // along in the track message. The image bytes themselves are
            // never pushed; Android requests them separately, and only on a
            // cache miss.
            let artworkResult = artwork.artwork(for: state, coordinator: media)
            if artworkResult == .trackChanged {
                // The provider advanced while its artwork was being read.
                // Publish neither the stale track nor stale playback; the
                // next normal tick will sample the new track coherently.
                return
            }
            let processedArtwork: ProcessedArtwork?
            switch artworkResult {
            case .ready(let artwork): processedArtwork = artwork
            case .unavailable, .trackChanged: processedArtwork = nil
            }
            lastTrackIdentity = state.trackIdentity
            onArtworkChanged?(processedArtwork?.artworkId)
            if let data = TrackMessage(state, artworkId: processedArtwork?.artworkId).encodedLiveState() {
                onMessage?(data)
            }
        } else if state == nil || !(state?.hasTrack ?? false) {
            if lastTrackIdentity != nil { onArtworkChanged?(nil) }
            lastTrackIdentity = nil
        }

        let playback = PlaybackMessage(state)
        if playback != lastPlayback {
            lastPlayback = playback
            if let data = playback.encodedLiveState() {
                onMessage?(data)
            }
        }
    }
}
