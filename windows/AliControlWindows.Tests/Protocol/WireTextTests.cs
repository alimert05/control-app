using System.Text;
using System.Text.Json;
using AliControlWindows.Protocol;

namespace AliControlWindows.Tests.Protocol;

public class WireTextTests
{
    [Fact]
    public void ShortTextIsLeftCompletelyUntouched()
    {
        Assert.Equal("Bohemian Rhapsody", WireText.BoundedForWire("Bohemian Rhapsody", WireLimits.MaxTitleBytes));
    }

    [Fact]
    public void AZeroBudgetProducesAnEmptyStringRatherThanCrashing()
    {
        Assert.Equal("", WireText.BoundedForWire("anything", 0));
        Assert.Equal("", WireText.BoundedForWire(null, 10));
    }

    [Fact]
    public void ByteCostFollowsTheProtocolEscapingRules()
    {
        Assert.Equal(1, WireText.EscapedByteCount("a"));
        Assert.Equal(2, WireText.EscapedByteCount("é"));
        Assert.Equal(3, WireText.EscapedByteCount("東"));
        Assert.Equal(4, WireText.EscapedByteCount("🎧"));
        Assert.Equal(2, WireText.EscapedByteCount("\""));
        Assert.Equal(2, WireText.EscapedByteCount("\\"));
        Assert.Equal(6, WireText.EscapedByteCount("\u0001"));
    }

    [Fact]
    public void LongMultibyteTextIsCutOnACharacterBoundary()
    {
        string bounded = WireText.BoundedForWire(string.Concat(Enumerable.Repeat("東京の夜", 30)), WireLimits.MaxTitleBytes);

        Assert.True(WireText.EscapedByteCount(bounded) <= WireLimits.MaxTitleBytes);
        Assert.StartsWith("東京", bounded);
        Assert.DoesNotContain('�', bounded);
        // Round-trips through strict UTF-8: the cut never split a sequence.
        byte[] utf8 = Encoding.UTF8.GetBytes(bounded);
        Assert.Equal(bounded, new UTF8Encoding(false, true).GetString(utf8));
    }

    [Fact]
    public void EmojiClustersAreNeverSplit()
    {
        const string family = "👨‍👩‍👧‍👦";
        string title = string.Concat(Enumerable.Repeat(family, 10));

        string bounded = WireText.BoundedForWire(title, WireLimits.MaxTitleBytes);

        Assert.True(WireText.EscapedByteCount(bounded) <= WireLimits.MaxTitleBytes);
        // Whole clusters only: the result is a run of complete families (or empty).
        Assert.True(bounded.Length % family.Length == 0);
        Assert.True(bounded.Length == 0 || bounded == string.Concat(Enumerable.Repeat(family, bounded.Length / family.Length)));
    }

    [Fact]
    public void QuotesAndBackslashesAreBudgetedAtTheirEscapedSize()
    {
        string bounded = WireText.BoundedForWire(string.Concat(Enumerable.Repeat("\"\\", 60)), WireLimits.MaxTitleBytes);
        Assert.True(WireText.EscapedByteCount(bounded) <= WireLimits.MaxTitleBytes);
    }

    [Fact]
    public void ControlCharactersAreReplacedWithASpace()
    {
        Assert.Equal("a b c d", WireText.Sanitize("a\nb\tc\u0001d"));
        Assert.Equal("a b", WireText.BoundedForWire("a\nb", 10));
    }

    [Fact]
    public void LoneSurrogatesBecomeTheReplacementCharacter()
    {
        string withLoneHigh = "ab\uD83Dcd";
        string cleaned = WireText.Sanitize(withLoneHigh);
        Assert.Equal("ab�cd", cleaned);
        Assert.Equal("a�b", WireText.Sanitize("a\uDE00b"));
    }

    [Fact]
    public void ValidSurrogatePairsSurviveSanitising()
    {
        Assert.Equal("🎧 song", WireText.Sanitize("🎧 song"));
    }

    [Fact]
    public void BoundedTextAlwaysSurvivesAStandardJsonRoundTrip()
    {
        var random = new Random(77);
        string alphabet = "aé東🎧\"\\ /\n\u0007";
        for (int i = 0; i < 200; i++)
        {
            var sb = new StringBuilder();
            for (int c = 0; c < random.Next(1, 60); c++)
            {
                // alphabet contains a surrogate pair, so index by text elements
                sb.Append(alphabet[random.Next(alphabet.Length)]);
            }
            string bounded = WireText.BoundedForWire(sb.ToString(), WireLimits.MaxArtistBytes);
            byte[] json = Encoding.UTF8.GetBytes(new StringBuilder().Append("{\"v\":\"").Append(bounded.Replace("\\", "\\\\").Replace("\"", "\\\"")).Append("\"}").ToString());
            using JsonDocument document = JsonDocument.Parse(json);
            Assert.Equal(bounded, document.RootElement.GetProperty("v").GetString());
        }
    }
}
