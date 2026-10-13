using System.Text.Json;
using AliControlWindows.Artwork;
using AliControlWindows.Live;
using AliControlWindows.Media;
using AliControlWindows.Protocol;
using AliControlWindows.Telemetry;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.Live;

/// <summary>
/// The sampler's behaviour: what it emits, when it stays quiet, and that it only runs
/// while a tablet is subscribed. Tests drive <c>TickAsync()</c> directly rather than
/// waiting on the real timer, so the suite is deterministic and fast.
/// </summary>
public class LiveStatePublisherTests
{
    private sealed class Rig
    {
        public readonly FakeTelemetry Telemetry = new();
        public readonly FakeMediaSource Source = new();
        public readonly List<JsonElement> Messages = new();
        public readonly List<string> Events = new();
        public readonly LiveStatePublisher Publisher;
        public readonly ArtworkStore Store = new();
        public Func<byte[], ProcessedArtwork?> Processor = raw => new ProcessedArtwork(ArtworkProcessor.ArtworkIdFor(raw), raw, "image/jpeg");

        public Rig(TimeSpan? interval = null)
        {
            var pipeline = new ArtworkPipeline(Store, raw => Task.FromResult(Processor(raw)));
            Publisher = new LiveStatePublisher(Telemetry, new MediaSessionCoordinator(Source), pipeline, interval ?? TimeSpan.FromMilliseconds(20));
            Publisher.MessageReady += data =>
            {
                lock (Messages) Messages.Add(JsonDocument.Parse(data).RootElement.Clone());
                Events.Add(Type(Messages[^1]));
            };
            Publisher.ArtworkChanged += id => Events.Add($"artwork:{id ?? "null"}");
        }

        public List<JsonElement> OfType(string type)
        {
            lock (Messages) return Messages.Where(m => Type(m) == type).ToList();
        }

        public static string Type(JsonElement e) => e.GetProperty("type").GetString()!;
    }

    private static MediaState Playing(string title, double position = 0, string session = "spotify") =>
        new(session, "spotify", MediaPlaybackState.Playing, title, "Artist", "Album", position, 200);

    [Fact]
    public async Task ATickWithMetricsEmitsAMetricsMessage()
    {
        var rig = new Rig();
        rig.Telemetry.Next = new SystemMetrics(CpuPercent: 12, MemoryPercent: 34);

        await rig.Publisher.TickAsync();

        JsonElement metrics = rig.OfType("metrics").Single();
        Assert.Equal(12, metrics.GetProperty("cpu").GetInt32());
        Assert.Equal(34, metrics.GetProperty("memory").GetInt32());
    }

    [Fact]
    public async Task ATickWithNothingMeasurableEmitsNoMetricsMessage()
    {
        var rig = new Rig();
        await rig.Publisher.TickAsync();
        Assert.Empty(rig.OfType("metrics"));
    }

    [Fact]
    public async Task TrackMetadataIsSentOnceAndOnlyResentWhenTheTrackChanges()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("First") };

        await rig.Publisher.TickAsync();
        await rig.Publisher.TickAsync();
        await rig.Publisher.TickAsync();
        Assert.Single(rig.OfType("track"));

        rig.Source.Sessions = new() { Playing("Second") };
        await rig.Publisher.TickAsync();

        var tracks = rig.OfType("track");
        Assert.Equal(2, tracks.Count);
        Assert.Equal("Second", tracks[1].GetProperty("title").GetString());
    }

    [Fact]
    public async Task PlaybackIsResentWhileThePositionAdvancesButGoesQuietWhenNothingChanges()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("Song", 10) };
        await rig.Publisher.TickAsync();
        rig.Source.Sessions = new() { Playing("Song", 12) };
        await rig.Publisher.TickAsync();
        await rig.Publisher.TickAsync(); // same position twice: nothing new to say

        var playback = rig.OfType("media");
        Assert.Equal(2, playback.Count);
        Assert.Equal(12, playback[1].GetProperty("position").GetInt32());
    }

    [Fact]
    public async Task MediaBecomingUnavailableIsAnnouncedOnceThenStaysQuiet()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("Song") };
        await rig.Publisher.TickAsync();

        rig.Source.Sessions = new();
        await rig.Publisher.TickAsync();
        await rig.Publisher.TickAsync();

        Assert.Single(rig.OfType("media"), m => !m.GetProperty("available").GetBoolean());
    }

    [Fact]
    public async Task TheSameTrackIsResentAfterMediaBecomesAvailableAgain()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("Song") };
        await rig.Publisher.TickAsync();
        rig.Source.Sessions = new();
        await rig.Publisher.TickAsync();
        rig.Source.Sessions = new() { Playing("Song") };
        await rig.Publisher.TickAsync();

        Assert.Equal(2, rig.OfType("track").Count);
    }

    [Fact]
    public async Task NoMediaMeansOneUnavailableMessageAndNoTrackMessages()
    {
        var rig = new Rig();
        await rig.Publisher.TickAsync();
        await rig.Publisher.TickAsync();

        Assert.Empty(rig.OfType("track"));
        var playback = rig.OfType("media").Single();
        Assert.False(playback.GetProperty("available").GetBoolean());
    }

    [Fact]
    public async Task ATrackChangeAnnouncesTheArtworkIdentityBeforeTheTrackMessage()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("First") };
        rig.Source.Artwork = new byte[] { 1, 2, 3 };

        await rig.Publisher.TickAsync();

        int artworkAt = rig.Events.FindIndex(e => e.StartsWith("artwork:"));
        int trackAt = rig.Events.IndexOf("track");
        Assert.True(artworkAt >= 0 && trackAt > artworkAt, string.Join(",", rig.Events));
    }

    [Fact]
    public async Task TheTrackMessageCarriesTheArtworkIdOnlyWhenThereIsArtwork()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("First") };
        rig.Source.Artwork = new byte[] { 1, 2, 3 };
        await rig.Publisher.TickAsync();
        string id = rig.OfType("track").Single().GetProperty("artworkId").GetString()!;
        Assert.True(ArtworkWire.IsValidArtworkId(id));
        Assert.NotNull(rig.Publisher.ProcessedArtwork(id));

        rig.Source.Sessions = new() { Playing("No art") };
        rig.Source.Artwork = null;
        await rig.Publisher.TickAsync();
        Assert.False(rig.OfType("track")[1].TryGetProperty("artworkId", out _));
    }

    [Fact]
    public async Task ArtworkIsNotFetchedAgainOnEveryTick()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("Song", 1) };
        rig.Source.Artwork = new byte[] { 1 };

        for (int i = 0; i < 5; i++)
        {
            rig.Source.Sessions = new() { Playing("Song", i) };
            await rig.Publisher.TickAsync();
        }

        Assert.Equal(1, rig.Source.ArtworkFetchCount);
    }

    [Fact]
    public async Task ATrackChangingDuringTheArtworkFetchSuppressesTheStaleTrackAndPlayback()
    {
        var rig = new Rig();
        rig.Source.Sessions = new() { Playing("First") };
        rig.Source.Artwork = new byte[] { 1 };
        rig.Source.OnArtworkFetch = () => rig.Source.Sessions = new() { Playing("Second") };

        await rig.Publisher.TickAsync();

        Assert.Empty(rig.OfType("track"));
        Assert.Empty(rig.OfType("media"));

        // The next normal tick samples the new track coherently.
        rig.Source.OnArtworkFetch = null;
        await rig.Publisher.TickAsync();
        Assert.Equal("Second", rig.OfType("track").Single().GetProperty("title").GetString());
    }

    [Fact]
    public async Task EveryEmittedMessageFitsTheNotificationBudget()
    {
        var rig = new Rig();
        rig.Telemetry.Next = new SystemMetrics(99, 99, 99);
        rig.Source.Sessions = new()
        {
            new MediaState("s", "spotify", MediaPlaybackState.Playing,
                string.Concat(Enumerable.Repeat("長い曲名", 50)),
                string.Concat(Enumerable.Repeat("アーティスト名", 50)),
                string.Concat(Enumerable.Repeat("アルバム名", 50)), 12_345, 54_321)
        };
        rig.Source.Artwork = new byte[] { 1, 2, 3 };
        var sizes = new List<int>();
        rig.Publisher.MessageReady += d => sizes.Add(d.Length);

        await rig.Publisher.TickAsync();

        Assert.NotEmpty(sizes);
        Assert.All(sizes, size => Assert.True(size <= WireLimits.MaxNotificationBytes, $"{size} bytes"));
    }

    [Fact]
    public async Task AnArtworkProcessorFailureNeverStopsTheTrackFromBeingPublished()
    {
        var rig = new Rig { Processor = _ => null };
        rig.Source.Sessions = new() { Playing("Song") };
        rig.Source.Artwork = new byte[] { 1 };

        await rig.Publisher.TickAsync();

        Assert.False(rig.OfType("track").Single().TryGetProperty("artworkId", out _));
    }

    // -- lifecycle --

    [Fact]
    public async Task SamplingOnlyRunsBetweenStartAndStop()
    {
        var rig = new Rig();
        Assert.False(rig.Publisher.IsRunning);

        rig.Publisher.Start();
        Assert.True(rig.Publisher.IsRunning);
        await WaitUntil(() => rig.Telemetry.SampleCount >= 3); // 1 priming sample + ticks

        await rig.Publisher.StopAndWaitAsync();
        Assert.False(rig.Publisher.IsRunning);
        int after = rig.Telemetry.SampleCount;
        await Task.Delay(120);
        Assert.Equal(after, rig.Telemetry.SampleCount); // nothing runs once stopped
    }

    [Fact]
    public async Task StartingTwiceDoesNotCreateASecondSampler()
    {
        var rig = new Rig(TimeSpan.FromMilliseconds(200));
        rig.Publisher.Start();
        rig.Publisher.Start();
        await WaitUntil(() => rig.Telemetry.SampleCount >= 1);

        Assert.Equal(1, rig.Telemetry.SampleCount); // exactly one priming sample: one loop only
        await rig.Publisher.StopAndWaitAsync();
    }

    [Fact]
    public async Task StartingPrimesTheTelemetryBaseline()
    {
        var rig = new Rig(TimeSpan.FromMinutes(5));
        rig.Publisher.Start();
        await WaitUntil(() => rig.Telemetry.SampleCount == 1);
        await rig.Publisher.StopAndWaitAsync();
        Assert.True(rig.Telemetry.ResetCount >= 1);
    }

    [Fact]
    public async Task AReconnectResendsCurrentStateInsteadOfDiffingAgainstThePreviousSession()
    {
        var rig = new Rig(TimeSpan.FromMilliseconds(15));
        rig.Source.Sessions = new() { Playing("Song") };

        rig.Publisher.Start();
        await WaitUntil(() => rig.OfType("track").Count == 1);
        await rig.Publisher.StopAndWaitAsync();

        // The tablet drops and comes back with the same track still playing.
        rig.Publisher.Start();
        await WaitUntil(() => rig.OfType("track").Count == 2);
        await rig.Publisher.StopAndWaitAsync();

        Assert.Equal(2, rig.OfType("track").Count);
    }

    [Fact]
    public async Task ATickInFlightWhenTheTabletLeavesNeverLeaksIntoTheNextConnection()
    {
        var rig = new Rig(TimeSpan.FromMilliseconds(10));
        var release = new TaskCompletionSource();
        rig.Source.Sessions = new() { Playing("Stale") };
        rig.Source.Artwork = new byte[] { 1 };
        rig.Source.OnArtworkFetch = () => release.Task.Wait(TimeSpan.FromSeconds(5)); // hold the first tick mid-artwork

        rig.Publisher.Start();
        await WaitUntil(() => rig.Source.ArtworkFetchCount == 1);
        rig.Publisher.Stop();                 // tablet disconnects while that tick is in flight
        int before = rig.Messages.Count(m => Rig.Type(m) is "track" or "media");
        release.SetResult();                  // the old tick now finishes
        await rig.Publisher.StopAndWaitAsync();

        int after = rig.Messages.Count(m => Rig.Type(m) is "track" or "media");
        Assert.Equal(before, after); // the stale track/playback were suppressed by the generation guard
    }

    [Fact]
    public async Task AFailureInOneTickDoesNotStopTheLoop()
    {
        var logged = new List<string>();
        var telemetry = new ThrowOnceTelemetry();
        var publisher = new LiveStatePublisher(
            telemetry, new MediaSessionCoordinator(new FakeMediaSource()),
            new ArtworkPipeline(new ArtworkStore(), _ => Task.FromResult<ProcessedArtwork?>(null)),
            TimeSpan.FromMilliseconds(10), logged.Add);
        var received = new List<byte[]>();
        publisher.MessageReady += received.Add;

        publisher.Start();
        await WaitUntil(() => received.Count > 0);
        await publisher.StopAndWaitAsync();

        Assert.NotEmpty(logged);
        Assert.NotEmpty(received);
    }

    private sealed class ThrowOnceTelemetry : ITelemetryProvider
    {
        private int _calls;

        public SystemMetrics Sample()
        {
            // call 1 = priming, call 2 = the first tick (throws), later ticks work
            return Interlocked.Increment(ref _calls) == 2 ? throw new InvalidOperationException("boom") : new SystemMetrics(MemoryPercent: 5);
        }

        public void Reset() { }
    }

    private static async Task WaitUntil(Func<bool> condition)
    {
        DateTime deadline = DateTime.UtcNow.AddSeconds(5);
        while (!condition())
        {
            if (DateTime.UtcNow > deadline) throw new TimeoutException("condition not met");
            await Task.Delay(5);
        }
    }
}
