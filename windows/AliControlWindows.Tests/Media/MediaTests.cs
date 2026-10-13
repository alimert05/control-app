using AliControlWindows.Media;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.Media;

/// <summary>Session selection, metadata, playback state and timeline — through fakes only. No test needs any media app installed, running or playing.</summary>
public class MediaTests
{
    private static MediaState S(string id, MediaPlaybackState state, string? title = "Song", string source = "spotify",
        string? artist = "Artist", double? position = null, double? duration = null) =>
        new(id, source, state, title, artist, null, position, duration);

    // -- model --

    [Fact]
    public void PlayingAndPausedSessionsHaveATrackButStoppedOnesDoNot()
    {
        Assert.True(S("a", MediaPlaybackState.Playing).HasTrack);
        Assert.True(S("a", MediaPlaybackState.Paused).HasTrack);
        Assert.False(S("a", MediaPlaybackState.Stopped).HasTrack);
    }

    [Fact]
    public void ASessionWithoutATitleHasNoTrackToShow()
    {
        Assert.False(S("a", MediaPlaybackState.Playing, title: null).HasTrack);
        Assert.False(S("a", MediaPlaybackState.Playing, title: "").HasTrack);
    }

    [Fact]
    public void TrackIdentityChangesWhenTheTrackDoesNotWhenOnlyTheTimelineMoves()
    {
        var a = S("a", MediaPlaybackState.Playing, position: 10);
        var sameTrackLater = S("a", MediaPlaybackState.Playing, position: 50);
        var otherTrack = S("a", MediaPlaybackState.Playing, title: "Other", position: 10);

        Assert.Equal(a.TrackIdentity, sameTrackLater.TrackIdentity);
        Assert.NotEqual(a.TrackIdentity, otherTrack.TrackIdentity);
    }

    [Fact]
    public void PlayingFlagFollowsPlaybackState()
    {
        Assert.True(S("a", MediaPlaybackState.Playing).IsPlaying);
        Assert.False(S("a", MediaPlaybackState.Paused).IsPlaying);
    }

    // -- selection policy --

    [Fact]
    public void APlayingSessionWinsOverAPausedOne()
    {
        var chosen = MediaSessionCoordinator.Select(new[]
        {
            S("music", MediaPlaybackState.Paused, source: "media_player"),
            S("spotify", MediaPlaybackState.Playing)
        }, previousSessionId: null);
        Assert.Equal("spotify", chosen?.SessionId);
    }

    [Fact]
    public void WhenTwoSessionsPlayThePreviouslyChosenOneKeepsTheWidget()
    {
        var states = new[] { S("first", MediaPlaybackState.Playing), S("second", MediaPlaybackState.Playing) };
        Assert.Equal("second", MediaSessionCoordinator.Select(states, "second")?.SessionId);
        Assert.Equal("first", MediaSessionCoordinator.Select(states, "first")?.SessionId);
    }

    [Fact]
    public void WithNoPreviousChoiceTheSystemsCurrentSessionDecides()
    {
        var states = new[] { S("current", MediaPlaybackState.Playing), S("other", MediaPlaybackState.Playing) };
        Assert.Equal("current", MediaSessionCoordinator.Select(states, null)?.SessionId);
    }

    [Fact]
    public void PausingDoesNotHandTheWidgetToAnotherIdlePlayer()
    {
        var states = new[] { S("a", MediaPlaybackState.Paused), S("b", MediaPlaybackState.Paused) };
        Assert.Equal("b", MediaSessionCoordinator.Select(states, "b")?.SessionId);
    }

    [Fact]
    public void ASessionWithoutATrackIsNeverSelected()
    {
        Assert.Null(MediaSessionCoordinator.Select(new[] { S("a", MediaPlaybackState.Stopped) }, "a"));
        Assert.Null(MediaSessionCoordinator.Select(new[] { S("a", MediaPlaybackState.Playing, title: null) }, "a"));
    }

    [Fact]
    public void NoSessionsMeansMediaIsUnavailable()
    {
        Assert.Null(MediaSessionCoordinator.Select(Array.Empty<MediaState>(), "x"));
    }

    [Fact]
    public void AStickySessionThatDisappearedFallsBackToTheFirstWithATrack()
    {
        var states = new[] { S("a", MediaPlaybackState.Paused), S("b", MediaPlaybackState.Paused) };
        Assert.Equal("a", MediaSessionCoordinator.Select(states, "gone")?.SessionId);
    }

    // -- coordinator --

    [Fact]
    public async Task CoordinatorRemembersItsChoiceAcrossSamples()
    {
        var source = new FakeMediaSource();
        source.Sessions = new() { S("a", MediaPlaybackState.Playing), S("b", MediaPlaybackState.Playing) };
        var coordinator = new MediaSessionCoordinator(source);

        Assert.Equal("a", (await coordinator.CurrentStateAsync())?.SessionId);

        // "b" is now the system's current session and both still play: "a" keeps the widget.
        source.Sessions = new() { S("b", MediaPlaybackState.Playing), S("a", MediaPlaybackState.Playing) };
        Assert.Equal("a", (await coordinator.CurrentStateAsync())?.SessionId);
    }

    [Fact]
    public async Task ResetClearsTheStickyPreferenceForTheNextSession()
    {
        var source = new FakeMediaSource();
        source.Sessions = new() { S("a", MediaPlaybackState.Playing), S("b", MediaPlaybackState.Playing) };
        var coordinator = new MediaSessionCoordinator(source);
        await coordinator.CurrentStateAsync();

        source.Sessions = new() { S("b", MediaPlaybackState.Playing), S("a", MediaPlaybackState.Playing) };
        coordinator.Reset();

        Assert.Equal("b", (await coordinator.CurrentStateAsync())?.SessionId);
    }

    [Fact]
    public async Task NoSessionsReportsNothing()
    {
        var coordinator = new MediaSessionCoordinator(new FakeMediaSource());
        Assert.Null(await coordinator.CurrentStateAsync());
    }

    [Fact]
    public async Task AnUnavailableMediaServiceReportsNothing()
    {
        var coordinator = new MediaSessionCoordinator(new FakeMediaSource { IsAvailable = false });
        Assert.False(coordinator.IsAvailable);
        Assert.Null(await coordinator.CurrentStateAsync());
    }

    // -- artwork fetch --

    [Fact]
    public async Task ArtworkIsReturnedForTheSelectedSessionWhenTheTrackStaysTheSame()
    {
        var source = new FakeMediaSource { Artwork = new byte[] { 1, 2, 3 } };
        source.Sessions = new() { S("a", MediaPlaybackState.Playing) };
        var coordinator = new MediaSessionCoordinator(source);
        var state = (await coordinator.CurrentStateAsync())!;

        var result = await coordinator.FetchArtworkAsync(state);

        Assert.Equal(MediaSessionCoordinator.ArtworkFetchKind.Data, result.Kind);
        Assert.Equal(new byte[] { 1, 2, 3 }, result.Data);
    }

    [Fact]
    public async Task ASessionThatHasNoArtworkIsReportedUnavailableNotAsAnError()
    {
        var source = new FakeMediaSource { Artwork = null };
        source.Sessions = new() { S("a", MediaPlaybackState.Playing) };
        var coordinator = new MediaSessionCoordinator(source);
        var state = (await coordinator.CurrentStateAsync())!;

        Assert.Equal(MediaSessionCoordinator.ArtworkFetchKind.Unavailable, (await coordinator.FetchArtworkAsync(state)).Kind);
    }

    [Fact]
    public async Task ATrackThatChangesDuringTheFetchNeverReceivesTheOldBytes()
    {
        var source = new FakeMediaSource { Artwork = new byte[] { 9 } };
        source.Sessions = new() { S("a", MediaPlaybackState.Playing, title: "First") };
        source.OnArtworkFetch = () => source.Sessions = new() { S("a", MediaPlaybackState.Playing, title: "Second") };
        var coordinator = new MediaSessionCoordinator(source);
        var state = (await coordinator.CurrentStateAsync())!;

        var result = await coordinator.FetchArtworkAsync(state);

        Assert.Equal(MediaSessionCoordinator.ArtworkFetchKind.TrackChanged, result.Kind);
        Assert.Null(result.Data);
    }

    [Fact]
    public async Task ArtworkIsNeverFetchedForASessionThatIsNotTheSelectedOne()
    {
        var source = new FakeMediaSource { Artwork = new byte[] { 1 } };
        source.Sessions = new() { S("a", MediaPlaybackState.Playing), S("b", MediaPlaybackState.Paused) };
        var coordinator = new MediaSessionCoordinator(source);
        await coordinator.CurrentStateAsync(); // selects "a"

        var result = await coordinator.FetchArtworkAsync(S("b", MediaPlaybackState.Paused));

        Assert.Equal(MediaSessionCoordinator.ArtworkFetchKind.TrackChanged, result.Kind);
        Assert.Equal(0, source.ArtworkFetchCount);
    }

    [Fact]
    public async Task ASessionThatVanishedMidFetchCountsAsAChange()
    {
        var source = new FakeMediaSource { Artwork = new byte[] { 1 } };
        source.Sessions = new() { S("a", MediaPlaybackState.Playing) };
        source.OnArtworkFetch = () => source.Sessions = new();
        var coordinator = new MediaSessionCoordinator(source);
        var state = (await coordinator.CurrentStateAsync())!;

        Assert.Equal(MediaSessionCoordinator.ArtworkFetchKind.TrackChanged, (await coordinator.FetchArtworkAsync(state)).Kind);
    }

    // -- timeline --

    private static readonly DateTimeOffset Now = new(2026, 10, 7, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public void PositionAndDurationComeFromTheTimeline()
    {
        var r = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.FromSeconds(214), TimeSpan.FromSeconds(83), Now, Now, false, 1);
        Assert.Equal(83d, r.PositionSeconds!.Value, 3);
        Assert.Equal(214d, r.DurationSeconds!.Value, 3);
    }

    [Fact]
    public void WhilePlayingTheTimeSinceTheLastUpdateIsAdded()
    {
        var r = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.FromSeconds(214), TimeSpan.FromSeconds(80),
            lastUpdated: Now - TimeSpan.FromSeconds(3), Now, isPlaying: true, playbackRate: 1);
        Assert.Equal(83d, r.PositionSeconds!.Value, 3);
    }

    [Fact]
    public void WhilePausedThePositionDoesNotAdvance()
    {
        var r = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.FromSeconds(214), TimeSpan.FromSeconds(80),
            lastUpdated: Now - TimeSpan.FromSeconds(30), Now, isPlaying: false, playbackRate: 1);
        Assert.Equal(80d, r.PositionSeconds!.Value, 3);
    }

    [Fact]
    public void ThePlaybackRateScalesTheElapsedTime()
    {
        var r = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.FromSeconds(214), TimeSpan.FromSeconds(80),
            Now - TimeSpan.FromSeconds(4), Now, true, playbackRate: 1.5);
        Assert.Equal(86d, r.PositionSeconds!.Value, 3);
    }

    [Fact]
    public void ANonsensePlaybackRateFallsBackToRealTime()
    {
        var r = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.FromSeconds(214), TimeSpan.FromSeconds(80),
            Now - TimeSpan.FromSeconds(2), Now, true, playbackRate: -3);
        Assert.Equal(82d, r.PositionSeconds!.Value, 3);
    }

    [Fact]
    public void PositionIsClampedIntoTheTrack()
    {
        var past = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.FromSeconds(100), TimeSpan.FromSeconds(95),
            Now - TimeSpan.FromSeconds(60), Now, true, 1);
        Assert.Equal(100d, past.PositionSeconds!.Value, 3);

        var before = MediaTimeline.Compute(TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(100), TimeSpan.Zero, Now, Now, false, 1);
        Assert.Equal(0d, before.PositionSeconds!.Value, 3);
    }

    [Fact]
    public void ARangeStartingAfterZeroIsMeasuredFromItsStart()
    {
        var r = MediaTimeline.Compute(TimeSpan.FromSeconds(60), TimeSpan.FromSeconds(160), TimeSpan.FromSeconds(90), Now, Now, false, 1);
        Assert.Equal(30d, r.PositionSeconds!.Value, 3);
        Assert.Equal(100d, r.DurationSeconds!.Value, 3);
    }

    [Fact]
    public void LiveStreamsAndAppsWithoutATimelineReportNeitherValue()
    {
        var empty = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.Zero, TimeSpan.Zero, default, Now, true, 1);
        Assert.Null(empty.PositionSeconds);
        Assert.Null(empty.DurationSeconds);

        var inverted = MediaTimeline.Compute(TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(5), TimeSpan.Zero, Now, Now, true, 1);
        Assert.Null(inverted.DurationSeconds);
    }

    [Fact]
    public void AnUnsetLastUpdatedTimeDoesNotInventElapsedTime()
    {
        var r = MediaTimeline.Compute(TimeSpan.Zero, TimeSpan.FromSeconds(214), TimeSpan.FromSeconds(80), default, Now, true, 1);
        Assert.Equal(80d, r.PositionSeconds!.Value, 3);
    }

    // -- source names --

    [Theory]
    [InlineData("Spotify.exe", "spotify")]
    [InlineData("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", "spotify")]
    [InlineData("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic", "media_player")]
    [InlineData("chrome.exe", "chrome")]
    [InlineData("MSEdge", "edge")]
    [InlineData("firefox.exe", "firefox")]
    [InlineData("vlc.exe", "vlc")]
    public void KnownAppsGetShortStableNames(string aumid, string expected)
    {
        Assert.Equal(expected, MediaSourceNames.Normalize(aumid));
    }

    [Theory]
    [InlineData("SomePlayer.exe", "someplayer")]
    [InlineData("Vendor.App_abc123!MyPlayer", "myplayer")]
    [InlineData(@"C:\Program Files\Cool Player\coolplayer.exe", "coolplayer")]
    public void UnknownAppsAreReducedToASafeIdentifier(string aumid, string expected)
    {
        Assert.Equal(expected, MediaSourceNames.Normalize(aumid));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("!!!")]
    public void NothingUsableFallsBackToGenericMedia(string? aumid)
    {
        Assert.Equal("media", MediaSourceNames.Normalize(aumid));
    }

    [Fact]
    public void NormalisedNamesAreAlwaysWireSafeAndShort()
    {
        string name = MediaSourceNames.Normalize("Some \"Weird\" App With Spaces & Symbols \\ / 東京 and a very long name indeed");
        Assert.True(name.Length <= 24);
        Assert.All(name, c => Assert.True(c is (>= 'a' and <= 'z') or (>= '0' and <= '9') or '.' or '_' or '-'));
    }
}
