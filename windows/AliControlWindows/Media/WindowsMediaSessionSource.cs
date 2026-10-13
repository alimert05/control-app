using Windows.Media.Control;
using Windows.Storage.Streams;

namespace AliControlWindows.Media;

/// <summary>
/// Reads Now Playing state from Windows' own system media session service
/// (<c>GlobalSystemMediaTransportControlsSessionManager</c>) — the same source
/// the lock screen and the volume flyout use. It is generic: Spotify, Windows
/// Media Player, browsers and any other app that publishes to the Windows media
/// transport controls show up the same way, with no provider-specific scripting,
/// no Spotify Web API and no cloud authentication.
///
/// Read-only. Nothing here starts, pauses or changes playback; transport
/// control stays on the existing media-key actions.
/// </summary>
public sealed class WindowsMediaSessionSource : IMediaSessionSource
{
    /// <summary>Upper bound for a thumbnail we are willing to read before it is resized.</summary>
    private const ulong MaxThumbnailBytes = 8UL * 1024 * 1024;

    private readonly GlobalSystemMediaTransportControlsSessionManager? _manager;

    private WindowsMediaSessionSource(GlobalSystemMediaTransportControlsSessionManager? manager) => _manager = manager;

    public bool IsAvailable => _manager != null;

    /// <summary>Never throws: if the media session service cannot be reached the source is simply unavailable.</summary>
    public static async Task<WindowsMediaSessionSource> CreateAsync()
    {
        try
        {
            return new WindowsMediaSessionSource(await GlobalSystemMediaTransportControlsSessionManager.RequestAsync());
        }
        catch
        {
            return new WindowsMediaSessionSource(null);
        }
    }

    public async Task<IReadOnlyList<MediaState>> GetSessionsAsync(CancellationToken cancellationToken = default)
    {
        if (_manager == null) return Array.Empty<MediaState>();

        var result = new List<MediaState>();
        try
        {
            string? currentId = _manager.GetCurrentSession()?.SourceAppUserModelId;
            // The system's own "current" session first: it is the tie-breaker the selection policy falls back on.
            var sessions = _manager.GetSessions()
                .OrderByDescending(s => string.Equals(s.SourceAppUserModelId, currentId, StringComparison.Ordinal))
                .ToList();

            foreach (var session in sessions)
            {
                cancellationToken.ThrowIfCancellationRequested();
                MediaState? state = await ReadSessionAsync(session).ConfigureAwait(false);
                if (state != null) result.Add(state);
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            // A session closing mid-read (or the service restarting) just means "no data this tick".
        }
        return result;
    }

    public async Task<byte[]?> GetArtworkAsync(string sessionId, CancellationToken cancellationToken = default)
    {
        if (_manager == null) return null;
        try
        {
            var session = _manager.GetSessions()
                .FirstOrDefault(s => string.Equals(s.SourceAppUserModelId, sessionId, StringComparison.Ordinal));
            if (session == null) return null;

            var properties = await session.TryGetMediaPropertiesAsync();
            IRandomAccessStreamReference? thumbnail = properties?.Thumbnail;
            if (thumbnail == null) return null;

            using IRandomAccessStreamWithContentType stream = await thumbnail.OpenReadAsync();
            ulong size = stream.Size;
            if (size == 0 || size > MaxThumbnailBytes) return null;

            using var reader = new DataReader(stream);
            await reader.LoadAsync((uint)size);
            var bytes = new byte[size];
            reader.ReadBytes(bytes);
            return bytes;
        }
        catch
        {
            return null;
        }
    }

    private static async Task<MediaState?> ReadSessionAsync(GlobalSystemMediaTransportControlsSession session)
    {
        try
        {
            var properties = await session.TryGetMediaPropertiesAsync();
            var playback = session.GetPlaybackInfo();
            var timeline = session.GetTimelineProperties();

            MediaPlaybackState state = playback?.PlaybackStatus switch
            {
                GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing => MediaPlaybackState.Playing,
                GlobalSystemMediaTransportControlsSessionPlaybackStatus.Paused => MediaPlaybackState.Paused,
                _ => MediaPlaybackState.Stopped
            };

            MediaTimeline.Result time = timeline == null
                ? default
                : MediaTimeline.Compute(
                    timeline.StartTime, timeline.EndTime, timeline.Position, timeline.LastUpdatedTime,
                    DateTimeOffset.Now, state == MediaPlaybackState.Playing, playback?.PlaybackRate);

            string aumid = session.SourceAppUserModelId ?? string.Empty;
            return new MediaState(
                SessionId: aumid,
                Source: MediaSourceNames.Normalize(aumid),
                PlaybackState: state,
                Title: NullIfBlank(properties?.Title),
                Artist: NullIfBlank(properties?.Artist),
                Album: NullIfBlank(properties?.AlbumTitle),
                PositionSeconds: time.PositionSeconds,
                DurationSeconds: time.DurationSeconds);
        }
        catch
        {
            return null;
        }
    }

    private static string? NullIfBlank(string? value) => string.IsNullOrWhiteSpace(value) ? null : value;
}
