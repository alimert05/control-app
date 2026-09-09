import Foundation

/// Picks which media application's state to report when more than one is
/// running, and remembers that choice so the tablet doesn't flip between two
/// players every couple of seconds.
///
/// ## Selection policy
///
/// Evaluated fresh on each sample, in this order:
///
/// 1. **Only running applications are consulted at all.** A non-running app
///    is skipped without any scripting call (which would launch it).
/// 2. **A player that is actually playing wins.** If more than one is
///    playing, the source chosen last time keeps priority — that stickiness
///    is what stops two open players from fighting for the widget.
/// 3. **Otherwise the previously chosen source keeps the widget**, as long as
///    it still has a track loaded (typically paused). Pausing a track should
///    not hand the display to some other idle app.
/// 4. **Otherwise the first source in priority order with a track**, where
///    priority is Apple Music then Spotify (TASK-009's stated order).
/// 5. **Otherwise nothing** — the tablet shows "Media unavailable".
///
/// The user never has to choose a player manually, and no state is persisted:
/// the "last chosen" memory lives only for the lifetime of the running helper.
final class MediaProviderCoordinator {

    enum ArtworkFetchResult: Equatable {
        case data(Data)
        case unavailable
        case trackChanged
    }

    private let providers: [MediaProviding]
    private var lastSelectedSource: MediaSource?

    /// Providers in priority order — Apple Music before Spotify.
    init(providers: [MediaProviding]) {
        self.providers = providers
    }

    convenience init() {
        self.init(providers: [ScriptedMediaProvider.appleMusic(), ScriptedMediaProvider.spotify()])
    }

    /// The media state to report right now, or nil when nothing usable is
    /// available.
    func currentState() -> MediaState? {
        let states = providers
            .filter { $0.isRunning }
            .compactMap { $0.currentState() }

        guard !states.isEmpty else {
            lastSelectedSource = nil
            return nil
        }

        let selected = Self.select(from: states, preferring: lastSelectedSource)
        lastSelectedSource = selected?.source
        return selected
    }

    /// Raw artwork bytes for whichever source [currentState] most recently
    /// selected, or nil if nothing is selected or that source has none.
    /// Never triggers a scripting call to a *different* source than the one
    /// actually driving the widget right now.
    func fetchArtworkData(matching expectedState: MediaState) -> ArtworkFetchResult {
        guard expectedState.source == lastSelectedSource,
              let provider = providers.first(where: { $0.source == expectedState.source }) else {
            return .trackChanged
        }

        let data = provider.fetchArtworkData()

        // State and artwork are separate local Apple events. Re-read the
        // state after the potentially slower artwork query so bytes from a
        // rapidly skipped track are never labelled with the previous
        // track's identity (or negatively cached against it).
        guard provider.currentState()?.trackIdentity == expectedState.trackIdentity else {
            return .trackChanged
        }
        return data.map(ArtworkFetchResult.data) ?? .unavailable
    }

    /// Drops the sticky preference — used when telemetry stops, so a later
    /// reconnect re-evaluates from scratch rather than inheriting a stale
    /// preference from a previous session.
    func reset() {
        lastSelectedSource = nil
    }

    /// Pure selection policy (see the class documentation), exposed so every
    /// branch can be tested with plain values instead of real applications.
    /// `states` must already be in priority order.
    static func select(from states: [MediaState], preferring previous: MediaSource?) -> MediaState? {
        let playing = states.filter { $0.isPlaying }
        if !playing.isEmpty {
            return playing.first { $0.source == previous } ?? playing.first
        }

        let withTrack = states.filter { $0.hasTrack }
        if let previous, let sticky = withTrack.first(where: { $0.source == previous }) {
            return sticky
        }
        return withTrack.first
    }
}
