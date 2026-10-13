namespace AliControlWindows.Media;

/// <summary>
/// What a media session is currently doing. <c>Stopped</c> means the session
/// exists but has no active track — distinct from "no session at all", which is
/// represented by the absence of a <see cref="MediaState"/> entirely.
/// </summary>
public enum MediaPlaybackState
{
    Playing,
    Paused,
    Stopped
}

/// <summary>
/// A snapshot of one media session's Now Playing state.
///
/// Deliberately platform-independent: nothing here knows about the Windows
/// media transport API or BLE. The session source normalises into this, the
/// coordinator picks between instances of it, and the wire layer encodes it.
/// </summary>
/// <param name="SessionId">Stable per-session key (the app's AppUserModelId); never sent over the wire.</param>
/// <param name="Source">Short, wire-safe app identifier such as <c>spotify</c>; shown by the tablet.</param>
public sealed record MediaState(
    string SessionId,
    string Source,
    MediaPlaybackState PlaybackState,
    string? Title = null,
    string? Artist = null,
    string? Album = null,
    double? PositionSeconds = null,
    double? DurationSeconds = null)
{
    public bool IsPlaying => PlaybackState == MediaPlaybackState.Playing;

    /// <summary>Whether there is a real track to describe. A session with no title has nothing worth showing.</summary>
    public bool HasTrack => PlaybackState != MediaPlaybackState.Stopped && !string.IsNullOrEmpty(Title);

    /// <summary>
    /// The fields that identify *which* track this is — used to detect track
    /// changes so the (larger) track message is only sent when it changes,
    /// rather than on every sampling tick.
    /// </summary>
    public string TrackIdentity => $"{Source}|{Title}|{Artist}|{Album}";
}

/// <summary>
/// The system media sessions Ali Control can read. Implemented over the Windows
/// global media transport controls and faked wholesale in tests, so no test
/// needs any media application installed, running or playing.
/// </summary>
public interface IMediaSessionSource
{
    /// <summary>Whether the platform media session service could be reached at all.</summary>
    bool IsAvailable { get; }

    /// <summary>
    /// Every current session, already normalised, with the system's own
    /// "current" session first. An empty list means nothing is playing or paused.
    /// </summary>
    Task<IReadOnlyList<MediaState>> GetSessionsAsync(CancellationToken cancellationToken = default);

    /// <summary>
    /// Raw (undecoded, unprocessed) artwork bytes for the session's current
    /// track, or null when the app exposes none. Never touches the network:
    /// only the thumbnail the media session itself provides is read.
    /// </summary>
    Task<byte[]?> GetArtworkAsync(string sessionId, CancellationToken cancellationToken = default);
}
