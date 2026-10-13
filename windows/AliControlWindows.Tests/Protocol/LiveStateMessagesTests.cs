using System.Text;
using System.Text.Json;
using AliControlWindows.Live;
using AliControlWindows.Media;
using AliControlWindows.Protocol;
using AliControlWindows.Telemetry;

namespace AliControlWindows.Tests.Protocol;

/// <summary>
/// The desktop→tablet live-state wire format: what it serialises to, and — most
/// importantly — that it can never exceed the BLE payload budget or cut a UTF-8
/// sequence in half. The literal strings must match the tablet's fixtures.
/// </summary>
public class LiveStateMessagesTests
{
    private static JsonElement Json(byte[] bytes) => JsonDocument.Parse(bytes).RootElement.Clone();

    private static MediaState Track(string title = "Song", string? artist = "Artist", string? album = "Album",
        MediaPlaybackState state = MediaPlaybackState.Playing, double? position = null, double? duration = null,
        string source = "spotify") =>
        new("session", source, state, title, artist, album, position, duration);

    // -- metrics --

    [Fact]
    public void MetricsMatchTheCanonicalFixtureTheTabletParses()
    {
        Assert.Equal("{\"type\":\"metrics\",\"cpu\":34,\"memory\":48}",
            Encoding.UTF8.GetString(LiveStateMessages.Metrics(new SystemMetrics(CpuPercent: 34, MemoryPercent: 48))));
    }

    [Fact]
    public void MetricsAreWholePercentages()
    {
        JsonElement json = Json(LiveStateMessages.Metrics(new SystemMetrics(CpuPercent: 34.6, MemoryPercent: 47.4)));
        Assert.Equal(35, json.GetProperty("cpu").GetInt32());
        Assert.Equal(47, json.GetProperty("memory").GetInt32());
    }

    [Fact]
    public void UnavailableMetricsAreOmittedRatherThanSentAsZero()
    {
        JsonElement json = Json(LiveStateMessages.Metrics(new SystemMetrics(CpuPercent: 10, MemoryPercent: 20)));
        Assert.False(json.TryGetProperty("gpu", out _));
        Assert.False(json.TryGetProperty("cpu_temp", out _));
        Assert.False(json.TryGetProperty("gpu_temp", out _));
        Assert.Equal(3, json.EnumerateObject().Count());
    }

    [Fact]
    public void GpuAndTemperatureFieldsSerialiseWhenAProviderSuppliesThem()
    {
        JsonElement json = Json(LiveStateMessages.Metrics(new SystemMetrics(1, 2, 55.5, 61.2, 58.9)));
        Assert.Equal(56, json.GetProperty("gpu").GetInt32());
        Assert.Equal(61, json.GetProperty("cpu_temp").GetInt32());
        Assert.Equal(59, json.GetProperty("gpu_temp").GetInt32());
    }

    [Fact]
    public void ZeroPercentIsSentAsZeroNotOmitted()
    {
        JsonElement json = Json(LiveStateMessages.Metrics(new SystemMetrics(CpuPercent: 0, MemoryPercent: 0, GpuPercent: 0)));
        Assert.Equal(0, json.GetProperty("cpu").GetInt32());
        Assert.Equal(0, json.GetProperty("gpu").GetInt32());
    }

    [Fact]
    public void NonFiniteMetricsAreOmitted()
    {
        JsonElement json = Json(LiveStateMessages.Metrics(new SystemMetrics(CpuPercent: double.NaN, MemoryPercent: double.PositiveInfinity)));
        Assert.False(json.TryGetProperty("cpu", out _));
        Assert.False(json.TryGetProperty("memory", out _));
    }

    // -- playback --

    [Fact]
    public void PlaybackMatchesTheCanonicalFixtureTheTabletParses()
    {
        string json = Encoding.UTF8.GetString(LiveStateMessages.Playback(Track(position: 83, duration: 214)));
        Assert.Equal("{\"type\":\"media\",\"available\":true,\"source\":\"spotify\",\"playing\":true,\"position\":83,\"duration\":214}", json);
    }

    [Fact]
    public void UnavailableMediaIsExplicit()
    {
        Assert.Equal("{\"type\":\"media\",\"available\":false}", Encoding.UTF8.GetString(LiveStateMessages.Playback(null)));
    }

    [Fact]
    public void ASessionWithNoTrackIsAlsoUnavailable()
    {
        MediaState stopped = new("s", "spotify", MediaPlaybackState.Stopped);
        Assert.Equal("{\"type\":\"media\",\"available\":false}", Encoding.UTF8.GetString(LiveStateMessages.Playback(stopped)));
    }

    [Fact]
    public void PausedPlaybackIsAvailableButNotPlaying()
    {
        JsonElement json = Json(LiveStateMessages.Playback(Track(state: MediaPlaybackState.Paused)));
        Assert.True(json.GetProperty("available").GetBoolean());
        Assert.False(json.GetProperty("playing").GetBoolean());
    }

    [Fact]
    public void PlaybackCarriesNoTrackTextAtAll()
    {
        JsonElement json = Json(LiveStateMessages.Playback(Track()));
        Assert.False(json.TryGetProperty("title", out _));
        Assert.False(json.TryGetProperty("artist", out _));
        Assert.False(json.TryGetProperty("album", out _));
    }

    [Fact]
    public void PositionAndDurationAreRounded()
    {
        JsonElement json = Json(LiveStateMessages.Playback(Track(position: 83.4, duration: 214.6)));
        Assert.Equal(83, json.GetProperty("position").GetInt32());
        Assert.Equal(215, json.GetProperty("duration").GetInt32());
    }

    [Fact]
    public void MissingTimelineIsOmitted()
    {
        JsonElement json = Json(LiveStateMessages.Playback(Track()));
        Assert.False(json.TryGetProperty("position", out _));
        Assert.False(json.TryGetProperty("duration", out _));
    }

    // -- track --

    [Fact]
    public void TrackMatchesTheCanonicalFixtureTheTabletParses()
    {
        Assert.Equal("{\"type\":\"track\",\"title\":\"Song\",\"artist\":\"Artist\",\"album\":\"Album\"}",
            Encoding.UTF8.GetString(LiveStateMessages.Track(Track())));
    }

    [Fact]
    public void TrackCarriesTheArtworkIdWhenThereIsOne()
    {
        JsonElement json = Json(LiveStateMessages.Track(Track(), "0123456789abcdef"));
        Assert.Equal("0123456789abcdef", json.GetProperty("artworkId").GetString());
    }

    [Fact]
    public void TrackOmitsArtworkIdWhenThereIsNone()
    {
        Assert.False(Json(LiveStateMessages.Track(Track())).TryGetProperty("artworkId", out _));
    }

    [Fact]
    public void TrackOmitsMissingArtistAndAlbum()
    {
        JsonElement json = Json(LiveStateMessages.Track(Track(artist: null, album: null)));
        Assert.False(json.TryGetProperty("artist", out _));
        Assert.False(json.TryGetProperty("album", out _));
    }

    // -- size safety --

    [Fact]
    public void EveryFixedSizeMessageStaysInsideTheNotificationBudget()
    {
        byte[][] messages =
        {
            LiveStateMessages.Metrics(new SystemMetrics(100, 100, 100, 100, 100)),
            LiveStateMessages.Playback(Track(position: 99_999, duration: 99_999)),
            LiveStateMessages.Playback(null)
        };
        Assert.All(messages, m => Assert.True(m.Length <= WireLimits.MaxNotificationBytes));
    }

    [Fact]
    public void AnAbsurdlyLongTrackStillFitsInOneNotification()
    {
        MediaState state = Track(
            title: string.Concat(Enumerable.Repeat("A very long song title indeed ", 40)),
            artist: string.Concat(Enumerable.Repeat("An extremely long artist name ", 40)),
            album: string.Concat(Enumerable.Repeat("A ridiculously long album name ", 40)));

        byte[] data = LiveStateMessages.Track(state, "0123456789abcdef");

        Assert.True(data.Length <= WireLimits.MaxNotificationBytes, $"{data.Length} bytes");
        _ = Json(data); // still valid JSON
    }

    [Fact]
    public void LongMultibyteMetadataIsTruncatedWithoutBreakingUtf8OrJson()
    {
        MediaState state = Track(
            title: string.Concat(Enumerable.Repeat("東京の夜", 30)),
            artist: string.Concat(Enumerable.Repeat("アーティスト", 30)),
            album: string.Concat(Enumerable.Repeat("アルバム", 30)));

        byte[] data = LiveStateMessages.Track(state, "0123456789abcdef");

        Assert.True(data.Length <= WireLimits.MaxNotificationBytes, $"{data.Length} bytes");
        string title = Json(data).GetProperty("title").GetString()!;
        Assert.StartsWith("東京", title);
        Assert.DoesNotContain('�', title);
    }

    [Fact]
    public void QuotesAndBackslashesCannotPushATrackOverTheBudget()
    {
        MediaState state = Track(
            title: string.Concat(Enumerable.Repeat("\"\\", 60)),
            artist: new string('"', 60),
            album: new string('\\', 60));

        byte[] data = LiveStateMessages.Track(state, "0123456789abcdef");

        Assert.True(data.Length <= WireLimits.MaxNotificationBytes, $"{data.Length} bytes");
        _ = Json(data); // still valid JSON
    }

    [Fact]
    public void ForwardSlashesAndNonAsciiAreWrittenRaw()
    {
        // The tablet's reader only understands \" and \\ escapes, so nothing else may be escaped.
        string json = Encoding.UTF8.GetString(LiveStateMessages.Track(Track(title: "AC/DC — Über", artist: null, album: null)));
        Assert.Contains("AC/DC — Über", json);
        Assert.DoesNotContain("\\/", json);
        Assert.DoesNotContain("\\u", json);
    }

    [Fact]
    public void ControlCharactersInMetadataNeverReachTheWire()
    {
        string json = Encoding.UTF8.GetString(LiveStateMessages.Track(Track(title: "Line1\nLine2\tTabbed", artist: null, album: null)));
        Assert.Contains("Line1 Line2 Tabbed", json);
        Assert.DoesNotContain("\\n", json);
    }

    [Fact]
    public void ALongSourceNameIsBounded()
    {
        byte[] data = LiveStateMessages.Playback(Track(source: new string('x', 200)));
        Assert.True(data.Length <= WireLimits.MaxNotificationBytes);
    }
}
