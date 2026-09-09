import Foundation

/// Which local application a `MediaState` came from. Raw values are the
/// strings that travel over BLE and are shown on the tablet.
enum MediaSource: String, Equatable, CaseIterable {
    case appleMusic = "apple_music"
    case spotify = "spotify"

    /// Human-readable name for the tablet's Now Playing header.
    var displayName: String {
        switch self {
        case .appleMusic: return "Apple Music"
        case .spotify: return "Spotify"
        }
    }
}

/// What a media application is currently doing. `stopped` means the app is
/// running but has no active track — distinct from "the app isn't running at
/// all", which is represented by the absence of a `MediaState` entirely.
enum MediaPlaybackState: String, Equatable {
    case playing
    case paused
    case stopped
}

/// A snapshot of one media application's Now Playing state.
///
/// Deliberately platform-independent: nothing here knows about AppleScript,
/// Spotify's millisecond durations, or BLE. Providers normalize into this,
/// the coordinator picks between instances of it, and the wire layer encodes
/// it.
struct MediaState: Equatable {

    let source: MediaSource
    let playbackState: MediaPlaybackState
    let title: String?
    let artist: String?
    let album: String?

    /// Seconds into the track, when the source reports it.
    let positionSeconds: Double?

    /// Track length in seconds, when the source reports it.
    let durationSeconds: Double?

    let timestamp: Date

    init(
        source: MediaSource,
        playbackState: MediaPlaybackState,
        title: String? = nil,
        artist: String? = nil,
        album: String? = nil,
        positionSeconds: Double? = nil,
        durationSeconds: Double? = nil,
        timestamp: Date = Date()
    ) {
        self.source = source
        self.playbackState = playbackState
        self.title = title
        self.artist = artist
        self.album = album
        self.positionSeconds = positionSeconds
        self.durationSeconds = durationSeconds
        self.timestamp = timestamp
    }

    var isPlaying: Bool { playbackState == .playing }

    /// Whether there is a real track to describe. A running-but-stopped
    /// player has nothing worth showing on the tablet.
    var hasTrack: Bool {
        playbackState != .stopped && !(title ?? "").isEmpty
    }

    /// The fields that identify *which* track this is — used to detect track
    /// changes so the (larger) track message is only sent when it actually
    /// changes, rather than every sampling tick.
    var trackIdentity: String {
        "\(source.rawValue)|\(title ?? "")|\(artist ?? "")|\(album ?? "")"
    }
}

/// One local media application Ali Control can read. Implemented per app
/// (Apple Music, Spotify) and faked wholesale in tests, so no test ever needs
/// either application installed, running, or playing.
protocol MediaProviding {
    var source: MediaSource { get }

    /// Whether the application is currently running. Checked *before* any
    /// scripting call: asking a non-running app for its state would launch
    /// it, which is both surprising and slow.
    var isRunning: Bool { get }

    /// Current state, or nil when unavailable (not running, no permission,
    /// or the app declined to answer).
    func currentState() -> MediaState?

    /// Raw (undecoded, unprocessed) artwork bytes for the currently playing
    /// track, or nil when this source has none available locally (either
    /// this application exposes no local artwork at all — see Spotify's
    /// provider — or the current track simply has none).
    ///
    /// Never touches the network: TASK-009A explicitly forbids fetching a
    /// remote artwork URL. A source with no *local* retrieval mechanism
    /// always returns nil here — that is an honest "unavailable", not a bug.
    func fetchArtworkData() -> Data?
}
