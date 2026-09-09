import AppKit
import Foundation

/// Whether a given application is currently running. Abstracted so tests can
/// describe a machine with (or without) Music/Spotify running, without either
/// app being installed.
protocol RunningApplicationChecking {
    func isRunning(bundleIdentifier: String) -> Bool
}

/// Real implementation: `NSWorkspace`'s running-application list. This is a
/// plain read of already-published state — it needs no permission and cannot
/// launch anything.
struct WorkspaceApplicationChecker: RunningApplicationChecking {
    func isRunning(bundleIdentifier: String) -> Bool {
        !NSRunningApplication.runningApplications(withBundleIdentifier: bundleIdentifier).isEmpty
    }
}

/// A media provider backed by one AppleScript against one local application.
///
/// Both supported apps expose the same conceptual properties through
/// scripting, so the only differences are the application name, its bundle
/// identifier, and how it reports duration — which is why this is one type
/// with two configurations rather than two near-identical classes.
///
/// Two behaviors matter for correctness:
///
/// 1. **Never script an app that isn't running.** `tell application "Spotify"`
///    *launches* Spotify if it isn't open. `isRunning` is therefore checked
///    first, every time, and a non-running app simply reports no state.
/// 2. **Never re-prompt for Automation.** If the user denies access, macOS
///    returns `errAEEventNotPermitted` forever after; this provider latches
///    that and stops issuing events for the rest of the process's life, so a
///    2-second sampler can't turn into a permission-prompt loop.
final class ScriptedMediaProvider: MediaProviding {

    /// Field separator inside the script's single-line reply: ASCII 30
    /// (record separator). A control character no track title contains,
    /// which avoids the ambiguity a comma or newline would introduce.
    static let fieldSeparator = "\u{1E}"

    let source: MediaSource

    private let bundleIdentifier: String
    private let script: String
    private let artworkScript: String?
    private let runner: AppleScriptRunning
    private let workspace: RunningApplicationChecking

    /// Latched once the user denies Automation for this app: no further Apple
    /// events are sent, so the prompt never reappears.
    private(set) var isAutomationDenied = false

    init(
        source: MediaSource,
        bundleIdentifier: String,
        script: String,
        artworkScript: String? = nil,
        runner: AppleScriptRunning = AppleScriptRunner(),
        workspace: RunningApplicationChecking = WorkspaceApplicationChecker()
    ) {
        self.source = source
        self.bundleIdentifier = bundleIdentifier
        self.script = script
        self.artworkScript = artworkScript
        self.runner = runner
        self.workspace = workspace
    }

    var isRunning: Bool { workspace.isRunning(bundleIdentifier: bundleIdentifier) }

    func currentState() -> MediaState? {
        guard !isAutomationDenied, isRunning else { return nil }

        switch runner.run(script) {
        case .failure(let failure):
            if failure == .notPermitted { isAutomationDenied = true }
            return nil
        case .success(let reply):
            return Self.parse(reply, source: source)
        }
    }

    /// Raw artwork bytes for the current track, or nil.
    ///
    /// Only latched-denied/non-running checks are shared with
    /// [currentState] — this issues its own, separate Apple event (a
    /// different script), so it independently respects the automation
    /// latch and never scripts a non-running app.
    func fetchArtworkData() -> Data? {
        guard !isAutomationDenied, isRunning, let artworkScript else { return nil }
        switch runner.runData(artworkScript) {
        case .failure(let failure):
            if failure == .notPermitted { isAutomationDenied = true }
            return nil
        case .success(let data):
            return data.isEmpty ? nil : data
        }
    }

    /// Pure: turns the script's separator-delimited reply into a `MediaState`.
    ///
    /// The script emits `state ⏎ title ⏎ artist ⏎ album ⏎ position ⏎ duration`
    /// (separator = ASCII 30), or the bare word `stopped` when there is no
    /// current track. Numbers are rounded to integers *inside* AppleScript so
    /// this parser never has to deal with a locale-dependent decimal
    /// separator.
    static func parse(_ reply: String, source: MediaSource) -> MediaState? {
        let trimmed = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        let fields = trimmed.components(separatedBy: fieldSeparator)
        guard let rawState = fields.first, let playbackState = MediaPlaybackState(rawValue: rawState.lowercased()) else {
            return nil
        }
        guard fields.count >= 6 else {
            // A running player with nothing loaded still reports its state.
            return MediaState(source: source, playbackState: playbackState)
        }

        return MediaState(
            source: source,
            playbackState: playbackState,
            title: nonEmpty(fields[1]),
            artist: nonEmpty(fields[2]),
            album: nonEmpty(fields[3]),
            positionSeconds: nonNegativeSeconds(fields[4]),
            durationSeconds: nonNegativeSeconds(fields[5])
        )
    }

    private static func nonEmpty(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Rejects negatives, non-numeric text and non-finite values rather than
    /// letting them reach a progress bar.
    private static func nonNegativeSeconds(_ value: String) -> Double? {
        guard let seconds = Double(value.trimmingCharacters(in: .whitespacesAndNewlines)),
              seconds.isFinite, seconds >= 0 else { return nil }
        return seconds
    }
}

extension ScriptedMediaProvider {

    /// Apple Music (`Music.app`). `duration` is already in seconds.
    ///
    /// Artwork **is** available: `data of artwork 1 of current track` hands
    /// back the track's embedded picture data (typically TIFF or JPEG)
    /// directly from the local library — no network access.
    static func appleMusic(
        runner: AppleScriptRunning = AppleScriptRunner(),
        workspace: RunningApplicationChecking = WorkspaceApplicationChecker()
    ) -> ScriptedMediaProvider {
        ScriptedMediaProvider(
            source: .appleMusic,
            bundleIdentifier: "com.apple.Music",
            script: script(application: "Music", durationExpression: "(round (duration of current track))"),
            artworkScript: artworkScript(application: "Music"),
            runner: runner,
            workspace: workspace
        )
    }

    /// Spotify. `duration of current track` is in **milliseconds**, so it's
    /// converted to whole seconds inside the script with integer division.
    ///
    /// Artwork is **not** available here: Spotify's AppleScript dictionary
    /// only exposes `artwork url` (a remote CDN URL), and fetching that would
    /// mean an HTTP request — explicitly out of scope for TASK-009A. The
    /// alternative, reading Spotify's local on-disk artwork cache, relies on
    /// an undocumented, version-fragile file layout — exactly the kind of
    /// private hack the task rules out. `artworkScript` is therefore left
    /// nil, so [fetchArtworkData] always returns nil for this source: an
    /// honest "unavailable" rather than a workaround.
    static func spotify(
        runner: AppleScriptRunning = AppleScriptRunner(),
        workspace: RunningApplicationChecking = WorkspaceApplicationChecker()
    ) -> ScriptedMediaProvider {
        ScriptedMediaProvider(
            source: .spotify,
            bundleIdentifier: "com.spotify.client",
            script: script(application: "Spotify", durationExpression: "((duration of current track) div 1000)"),
            artworkScript: nil,
            runner: runner,
            workspace: workspace
        )
    }

    /// One read-only query per tick. It reads properties and returns a
    /// string — it never starts, pauses or changes playback (playback control
    /// stays with the existing ActionLibrary media keys).
    ///
    /// Numbers are `round`ed inside AppleScript so they serialize as
    /// integers; a real number would render with the *system* decimal
    /// separator and break parsing under a comma-decimal locale.
    private static func script(application: String, durationExpression: String) -> String {
        """
        tell application "\(application)"
            if player state is stopped then return "stopped"
            set sep to character id 30
            set trackTitle to ""
            set trackArtist to ""
            set trackAlbum to ""
            set trackPosition to 0
            set trackDuration to 0
            try
                set trackTitle to name of current track
                set trackArtist to artist of current track
                set trackAlbum to album of current track
                set trackDuration to \(durationExpression)
                set trackPosition to (round (player position))
            end try
            return (player state as text) & sep & trackTitle & sep & trackArtist & sep & trackAlbum & sep & (trackPosition as text) & sep & (trackDuration as text)
        end tell
        """
    }

    /// A second, separate one-line query: raw artwork bytes only. Kept apart
    /// from [script] so a metadata-only tick never has to touch (potentially
    /// much larger) picture data, and vice versa — [ArtworkPipeline] only
    /// calls this once per *track change*, never every sampling tick.
    private static func artworkScript(application: String) -> String {
        """
        tell application "\(application)"
            if player state is stopped then return
            if (count of artworks of current track) is 0 then return
            return data of artwork 1 of current track
        end tell
        """
    }
}
