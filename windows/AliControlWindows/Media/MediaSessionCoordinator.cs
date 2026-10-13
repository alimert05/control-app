namespace AliControlWindows.Media;

/// <summary>
/// Picks which media session's state to report when more than one is open, and
/// remembers that choice so the tablet doesn't flip between two players every
/// couple of seconds.
///
/// <para><b>Selection policy</b>, evaluated fresh on each sample:</para>
/// <list type="number">
/// <item>A session that is actually <i>playing</i> wins. If more than one is,
/// the session chosen last time keeps priority (that stickiness stops two open
/// players fighting for the widget); with no previous choice, the system's own
/// current session (first in the list) decides.</item>
/// <item>Otherwise the previously chosen session keeps the widget as long as it
/// still has a track loaded (pausing must not hand the display to an idle app).</item>
/// <item>Otherwise the first session with a track (the system's current one first).</item>
/// <item>Otherwise nothing — the tablet shows "Media unavailable".</item>
/// </list>
///
/// The user never chooses a player manually, and nothing is persisted: the
/// "last chosen" memory lives only for the running agent and is dropped when
/// telemetry stops, so a reconnect re-evaluates from scratch.
/// </summary>
public sealed class MediaSessionCoordinator
{
    public enum ArtworkFetchKind { Data, Unavailable, TrackChanged }

    public readonly record struct ArtworkFetchResult(ArtworkFetchKind Kind, byte[]? Data = null);

    private readonly IMediaSessionSource _source;
    private string? _lastSelectedSessionId;

    public MediaSessionCoordinator(IMediaSessionSource source) => _source = source;

    public bool IsAvailable => _source.IsAvailable;

    /// <summary>The media state to report right now, or null when nothing usable is available.</summary>
    public async Task<MediaState?> CurrentStateAsync(CancellationToken cancellationToken = default)
    {
        IReadOnlyList<MediaState> states = await _source.GetSessionsAsync(cancellationToken).ConfigureAwait(false);
        if (states.Count == 0)
        {
            _lastSelectedSessionId = null;
            return null;
        }

        MediaState? selected = Select(states, _lastSelectedSessionId);
        _lastSelectedSessionId = selected?.SessionId;
        return selected;
    }

    /// <summary>
    /// Raw artwork bytes for whichever session <see cref="CurrentStateAsync"/>
    /// most recently selected. Never reads a different session than the one
    /// driving the widget, and re-checks track identity afterwards: state and
    /// artwork are read separately, so bytes from a rapidly skipped track must
    /// never be labelled with the previous track's identity.
    /// </summary>
    public async Task<ArtworkFetchResult> FetchArtworkAsync(MediaState expected, CancellationToken cancellationToken = default)
    {
        if (expected.SessionId != _lastSelectedSessionId) return new ArtworkFetchResult(ArtworkFetchKind.TrackChanged);

        byte[]? data = await _source.GetArtworkAsync(expected.SessionId, cancellationToken).ConfigureAwait(false);

        IReadOnlyList<MediaState> after = await _source.GetSessionsAsync(cancellationToken).ConfigureAwait(false);
        MediaState? stillSelected = after.FirstOrDefault(s => s.SessionId == expected.SessionId);
        if (stillSelected?.TrackIdentity != expected.TrackIdentity)
        {
            return new ArtworkFetchResult(ArtworkFetchKind.TrackChanged);
        }
        return data is { Length: > 0 }
            ? new ArtworkFetchResult(ArtworkFetchKind.Data, data)
            : new ArtworkFetchResult(ArtworkFetchKind.Unavailable);
    }

    /// <summary>Drops the sticky preference — used when telemetry stops, so a later reconnect starts fresh.</summary>
    public void Reset() => _lastSelectedSessionId = null;

    /// <summary>
    /// Pure selection policy (see the class documentation), exposed so every
    /// branch can be tested with plain values. <paramref name="states"/> must
    /// already be in priority order (the system's current session first).
    /// </summary>
    public static MediaState? Select(IReadOnlyList<MediaState> states, string? previousSessionId)
    {
        var playing = states.Where(s => s.IsPlaying && s.HasTrack).ToList();
        if (playing.Count > 0)
        {
            return playing.FirstOrDefault(s => s.SessionId == previousSessionId) ?? playing[0];
        }

        var withTrack = states.Where(s => s.HasTrack).ToList();
        if (previousSessionId != null)
        {
            MediaState? sticky = withTrack.FirstOrDefault(s => s.SessionId == previousSessionId);
            if (sticky != null) return sticky;
        }
        return withTrack.Count > 0 ? withTrack[0] : null;
    }
}
