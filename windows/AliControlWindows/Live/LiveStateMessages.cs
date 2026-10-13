using AliControlWindows.Media;
using AliControlWindows.Protocol;
using AliControlWindows.Telemetry;

namespace AliControlWindows.Live;

/// <summary>
/// The desktop→tablet live-state wire messages. They travel over the same state
/// characteristic as command acknowledgements and are told apart by <c>type</c>:
///
/// <code>
/// {"type":"metrics","cpu":34,"memory":48}
/// {"type":"media","available":true,"source":"spotify","playing":true,"position":83,"duration":214}
/// {"type":"track","title":"Song","artist":"Artist","album":"Album","artworkId":"0123456789abcdef"}
/// </code>
///
/// An <b>omitted metric means unavailable</b>, never zero — that distinction is
/// what lets a widget choose between <c>0%</c> and <c>--</c>. Percentages are whole
/// numbers (a dashboard tile shows <c>34%</c>; decimals would cost bytes to display
/// nothing). Every message is designed to stay under <see cref="WireLimits.MaxNotificationBytes"/>.
/// </summary>
public static class LiveStateMessages
{
    public const string MetricsType = "metrics";
    public const string MediaType = "media";
    public const string TrackType = "track";

    public static byte[] Metrics(SystemMetrics metrics) => new JsonWire()
        .String("type", MetricsType)
        .Int("cpu", Round(metrics.CpuPercent))
        .Int("memory", Round(metrics.MemoryPercent))
        .Int("gpu", Round(metrics.GpuPercent))
        .Int("cpu_temp", Round(metrics.CpuTemperatureCelsius))
        .Int("gpu_temp", Round(metrics.GpuTemperatureCelsius))
        .ToUtf8();

    /// <summary>
    /// Playback state — no track text at all, so its size is bounded by
    /// construction and it can be sent on every tick. <c>null</c>, or a session
    /// with nothing playable, is announced as <c>{"type":"media","available":false}</c>.
    /// </summary>
    public static byte[] Playback(MediaState? state)
    {
        if (state == null || !state.HasTrack)
        {
            return new JsonWire().String("type", MediaType).Bool("available", false).ToUtf8();
        }
        return new JsonWire()
            .String("type", MediaType)
            .Bool("available", true)
            .String("source", WireText.BoundedForWire(state.Source, WireLimits.MaxSourceBytes))
            .Bool("playing", state.IsPlaying)
            .Int("position", Round(state.PositionSeconds))
            .Int("duration", Round(state.DurationSeconds))
            .ToUtf8();
    }

    /// <summary>
    /// Track identity — sent only when the track changes. Each text field is
    /// bounded to its escaped-byte budget on grapheme boundaries. <paramref name="artworkId"/>
    /// only *identifies* the artwork; the tablet requests the image bytes
    /// separately, and only on a cache miss.
    /// </summary>
    public static byte[] Track(MediaState state, string? artworkId = null, int maxBytes = WireLimits.MaxNotificationBytes)
    {
        string? title = state.Title;
        string? artist = state.Artist;
        string? album = state.Album;

        // The message must fit `maxBytes` whatever the metadata looks like. The
        // fixed part (field names, braces, the artwork id) is measured by encoding
        // the message with empty text fields; what is left is shared between the
        // three text fields, each capped at its own nominal budget and none given
        // more than it needs. With an artwork id present the fixed part is large,
        // so a very long title + artist + album are shortened proportionally
        // instead of the whole message being too big to send.
        int overhead = BuildTrack(title == null ? null : "", artist == null ? null : "", album == null ? null : "", artworkId).Length;
        int[] budgets = WireText.AllocateBudgets(
            maxBytes - overhead,
            new[] { Demand(title), Demand(artist), Demand(album) },
            new[] { WireLimits.MaxTitleBytes, WireLimits.MaxArtistBytes, WireLimits.MaxAlbumBytes });

        return BuildTrack(
            title == null ? null : WireText.BoundedForWire(title, budgets[0]),
            artist == null ? null : WireText.BoundedForWire(artist, budgets[1]),
            album == null ? null : WireText.BoundedForWire(album, budgets[2]),
            artworkId);
    }

    private static byte[] BuildTrack(string? title, string? artist, string? album, string? artworkId) => new JsonWire()
        .String("type", TrackType)
        .String("title", title)
        .String("artist", artist)
        .String("album", album)
        .String("artworkId", artworkId)
        .ToUtf8();

    private static int Demand(string? value) => value == null ? 0 : WireText.EscapedByteCount(WireText.Sanitize(value));

    private static int? Round(double? value) =>
        value is { } v && double.IsFinite(v) ? (int)Math.Round(v, MidpointRounding.AwayFromZero) : null;
}
