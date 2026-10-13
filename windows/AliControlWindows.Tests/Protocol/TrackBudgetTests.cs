using System.Text.Json;
using AliControlWindows.Live;
using AliControlWindows.Media;
using AliControlWindows.Protocol;

namespace AliControlWindows.Tests.Protocol;

/// <summary>
/// A track message that carries an artwork id has a large fixed part (field names
/// plus a 16-character id), so the three text fields cannot all use their nominal
/// maximum at once. These tests pin how the shared budget behaves.
/// </summary>
public class TrackBudgetTests
{
    private const string ArtworkId = "0123456789abcdef";

    private static MediaState State(string? title, string? artist, string? album) =>
        new("s", "spotify", MediaPlaybackState.Playing, title, artist, album);

    private static (string? Title, string? Artist, string? Album) Fields(byte[] json)
    {
        using JsonDocument document = JsonDocument.Parse(json);
        JsonElement root = document.RootElement;
        string? Read(string name) => root.TryGetProperty(name, out JsonElement e) ? e.GetString() : null;
        return (Read("title"), Read("artist"), Read("album"));
    }

    [Fact]
    public void WorstCaseNominalBudgetsPlusArtworkIdWouldNotFitWithoutSharing()
    {
        // 81 bytes of structure + 48 + 36 + 26 = 191 > 160: the reason sharing exists.
        int worstCase = 81 + WireLimits.MaxTitleBytes + WireLimits.MaxArtistBytes + WireLimits.MaxAlbumBytes;
        Assert.True(worstCase > WireLimits.MaxNotificationBytes);
    }

    [Fact]
    public void EveryCombinationOfLongFieldsFitsWithAnArtworkId()
    {
        string longText = new string('x', 200);
        foreach ((string? t, string? a, string? b) in new[]
                 {
                     (longText, longText, longText),
                     (longText, null, longText),
                     (longText, longText, null),
                     ("T", longText, longText),
                     (new string('東', 80), new string('ア', 80), new string('ル', 80)),
                     (new string('"', 100), new string('\\', 100), new string('"', 100))
                 })
        {
            byte[] data = LiveStateMessages.Track(State(t, a, b), ArtworkId);
            Assert.True(data.Length <= WireLimits.MaxNotificationBytes, $"{data.Length} bytes");
            using JsonDocument _ = JsonDocument.Parse(data); // still valid JSON
        }
    }

    [Fact]
    public void ShortMetadataIsNeverTruncatedEvenWithAnArtworkId()
    {
        (string? title, string? artist, string? album) = Fields(
            LiveStateMessages.Track(State("Bohemian Rhapsody", "Queen", "A Night at the Opera"), ArtworkId));

        Assert.Equal("Bohemian Rhapsody", title);
        Assert.Equal("Queen", artist);
        Assert.Equal("A Night at the Opera", album);
    }

    [Fact]
    public void ARoomyTitleBenefitsFromAShortArtistName()
    {
        string title = new string('t', 40);
        (string? t, string? a, string? b) = Fields(LiveStateMessages.Track(State(title, "U2", "War"), ArtworkId));

        Assert.Equal(title, t); // 40 bytes still fits because artist and album used almost nothing
        Assert.Equal("U2", a);
        Assert.Equal("War", b);
    }

    [Fact]
    public void WithoutAnArtworkIdTheNominalBudgetsApplyInFull()
    {
        string title = new string('t', 48), artist = new string('a', 36), album = new string('b', 26);
        (string? t, string? a, string? b) = Fields(LiveStateMessages.Track(State(title + "overflow", artist + "overflow", album + "overflow")));

        Assert.Equal(title, t);
        Assert.Equal(artist, a);
        Assert.Equal(album, b);
    }

    [Fact]
    public void ASmallerLimitShrinksTheFieldsFurtherInsteadOfOverflowing()
    {
        byte[] data = LiveStateMessages.Track(State(new string('x', 100), new string('y', 100), new string('z', 100)), ArtworkId, maxBytes: 120);
        Assert.True(data.Length <= 120, $"{data.Length} bytes");
    }

    // -- the allocator itself --

    [Fact]
    public void AllocatorGivesEveryFieldWhatItNeedsWhenThereIsRoom()
    {
        int[] allocation = WireText.AllocateBudgets(100, new[] { 10, 20, 30 }, new[] { 48, 36, 26 });
        Assert.Equal(new[] { 10, 20, 26 }, allocation); // album is capped at 26
    }

    [Fact]
    public void AllocatorNeverExceedsTheAvailableBytes()
    {
        var random = new Random(5);
        for (int i = 0; i < 500; i++)
        {
            int available = random.Next(0, 130);
            var demands = new[] { random.Next(0, 120), random.Next(0, 120), random.Next(0, 120) };
            int[] caps = { 48, 36, 26 };

            int[] allocation = WireText.AllocateBudgets(available, demands, caps);

            Assert.True(allocation.Sum() <= available);
            for (int f = 0; f < 3; f++)
            {
                Assert.InRange(allocation[f], 0, Math.Min(demands[f], caps[f]));
            }
        }
    }

    [Fact]
    public void AllocatorUsesTheWholeBudgetWhenDemandExceedsIt()
    {
        int[] allocation = WireText.AllocateBudgets(60, new[] { 100, 100, 100 }, new[] { 48, 36, 26 });
        Assert.Equal(60, allocation.Sum());
    }

    [Fact]
    public void AllocatorSpreadsBytesFairlyBetweenGreedyFields()
    {
        int[] allocation = WireText.AllocateBudgets(30, new[] { 100, 100 }, new[] { 48, 48 });
        Assert.Equal(new[] { 15, 15 }, allocation);
    }

    [Fact]
    public void AllocatorHandlesFewerBytesThanFields()
    {
        int[] allocation = WireText.AllocateBudgets(2, new[] { 10, 10, 10 }, new[] { 48, 36, 26 });
        Assert.Equal(new[] { 1, 1, 0 }, allocation);
    }

    [Fact]
    public void AllocatorHandlesZeroAndNegativeBudgets()
    {
        Assert.All(WireText.AllocateBudgets(0, new[] { 5, 5 }, new[] { 10, 10 }), v => Assert.Equal(0, v));
        Assert.All(WireText.AllocateBudgets(-20, new[] { 5, 5 }, new[] { 10, 10 }), v => Assert.Equal(0, v));
    }
}
