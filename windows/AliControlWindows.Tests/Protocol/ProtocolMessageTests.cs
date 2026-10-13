using System.Text;
using System.Text.Json;
using AliControlWindows.Protocol;

namespace AliControlWindows.Tests.Protocol;

/// <summary>
/// Cross-platform protocol fixtures. The literal JSON strings here must match the
/// ones in android/app/src/test/.../ble/ProtocolFixtureTest.kt: the two files exist
/// to catch wire-format drift between the tablet and the agent.
/// </summary>
public class ProtocolMessageTests
{
    private static byte[] Utf8(string s) => Encoding.UTF8.GetBytes(s);

    // -- encoding: exact bytes the tablet's reader expects --

    [Fact]
    public void CommandEncodesToCanonicalJson()
    {
        Assert.Equal("{\"type\":\"command\",\"action\":\"ping\"}", ProtocolMessage.Command("ping").ToJson());
    }

    [Fact]
    public void OkAckEncodesToCanonicalJson()
    {
        Assert.Equal("{\"type\":\"state\",\"action\":\"volume_up\",\"status\":\"ok\"}",
            ProtocolMessage.State("ok", "volume_up").ToJson());
    }

    [Fact]
    public void ErrorAckEncodesToCanonicalJson()
    {
        Assert.Equal("{\"type\":\"state\",\"action\":\"open_terminal\",\"status\":\"error\"}",
            ProtocolMessage.State("error", "open_terminal").ToJson());
    }

    [Fact]
    public void InitialConnectedStateOmitsActionEntirely()
    {
        Assert.Equal("{\"type\":\"state\",\"status\":\"connected\"}", ProtocolMessage.State("connected").ToJson());
    }

    [Fact]
    public void AbsentFieldsAreOmittedNeverNull()
    {
        string json = ProtocolMessage.Command("ping").ToJson();
        Assert.DoesNotContain("null", json);
        Assert.DoesNotContain("status", json);
    }

    [Fact]
    public void QuotesAndBackslashesAreEscapedAndRoundTrip()
    {
        ProtocolMessage original = ProtocolMessage.Command("say \"hi\" \\ bye");
        Assert.Equal(original, ProtocolMessage.Decode(original.Encode()));
    }

    [Fact]
    public void EncodingIsPlainUtf8WithoutBom()
    {
        byte[] bytes = ProtocolMessage.State("ok", "ping").Encode();
        Assert.Equal((byte)'{', bytes[0]);
    }

    // -- decoding what the tablet sends --

    [Theory]
    [InlineData("{\"type\":\"command\",\"action\":\"ping\"}", "ping")]
    [InlineData("{\"type\":\"command\",\"action\":\"volume_up\"}", "volume_up")]
    [InlineData("{\"type\":\"command\",\"action\":\"open_terminal\"}", "open_terminal")]
    [InlineData("{\"type\":\"command\",\"action\":\"windows_search\"}", "windows_search")]
    public void DecodesCommandsExactlyAsTheTabletProducesThem(string json, string action)
    {
        ProtocolMessage? decoded = ProtocolMessage.Decode(Utf8(json));
        Assert.Equal(ProtocolMessage.Command(action), decoded);
    }

    [Fact]
    public void EveryActionIdRoundTripsThroughTheEnvelope()
    {
        foreach (string action in AliControlActions.All)
        {
            ProtocolMessage? decoded = ProtocolMessage.Decode(Utf8($"{{\"type\":\"command\",\"action\":\"{action}\"}}"));
            Assert.Equal(action, decoded?.Action);
            Assert.Equal(ProtocolMessage.TypeCommand, decoded?.Type);
        }
    }

    [Fact]
    public void DoesNotDependOnKeyOrder()
    {
        Assert.Equal(
            new ProtocolMessage("state", null, "connected"),
            ProtocolMessage.Decode(Utf8("{\"status\":\"connected\",\"type\":\"state\"}")));
        Assert.Equal(
            ProtocolMessage.Command("ping"),
            ProtocolMessage.Decode(Utf8("{\"action\":\"ping\",\"type\":\"command\"}")));
    }

    [Fact]
    public void ToleratesWhitespaceAndUnknownExtraFields()
    {
        ProtocolMessage? decoded = ProtocolMessage.Decode(Utf8(" { \"type\" : \"command\" , \"action\" : \"ping\", \"future\": [1,2] } "));
        Assert.Equal(ProtocolMessage.Command("ping"), decoded);
    }

    [Fact]
    public void ProtocolLayerDoesNotValidateActionsAgainstTheAllowlist()
    {
        // Allowlist enforcement is the dispatcher's job, not the envelope's: an
        // unrecognised action must still parse so a clean error can be sent back.
        ProtocolMessage? decoded = ProtocolMessage.Decode(Utf8("{\"type\":\"command\",\"action\":\"shutdown\"}"));
        Assert.Equal("shutdown", decoded?.Action);
    }

    // -- malformed / hostile input fails safely --

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("not json")]
    [InlineData("{\"action\":\"ping\"}")] // no type
    [InlineData("{\"type\":\"\"}")]
    [InlineData("[]")]
    [InlineData("\"command\"")]
    [InlineData("42")]
    [InlineData("null")]
    public void MalformedPayloadsDecodeToNull(string json)
    {
        Assert.Null(ProtocolMessage.Decode(Utf8(json)));
    }

    [Fact]
    public void EmptyBufferDecodesToNull()
    {
        Assert.Null(ProtocolMessage.Decode(ReadOnlySpan<byte>.Empty));
    }

    [Theory]
    [InlineData("{\"type\":\"command\",\"a")]            // exactly what a 20-byte ATT payload used to truncate to
    [InlineData("{\"type\":\"command\",\"action\":\"vol")]
    [InlineData("{\"type\":\"command\",\"action\":\"volume_up\"")]
    [InlineData("{\"type\":\"command\",\"action\":\"volume_up\"}}")]
    public void TruncatedOrBrokenPayloadsNeverHalfParse(string json)
    {
        Assert.Null(ProtocolMessage.Decode(Utf8(json)));
    }

    [Fact]
    public void WrongTypedFieldsAreRejected()
    {
        Assert.Null(ProtocolMessage.Decode(Utf8("{\"type\":7}")));
        Assert.Null(ProtocolMessage.Decode(Utf8("{\"type\":\"command\",\"action\":5}")));
        Assert.Null(ProtocolMessage.Decode(Utf8("{\"type\":\"command\",\"action\":[\"ping\"]}")));
        Assert.Null(ProtocolMessage.Decode(Utf8("{\"type\":\"state\",\"status\":{}}")));
    }

    [Fact]
    public void NullActionIsTreatedAsAbsent()
    {
        ProtocolMessage? decoded = ProtocolMessage.Decode(Utf8("{\"type\":\"command\",\"action\":null}"));
        Assert.NotNull(decoded);
        Assert.Null(decoded!.Action);
    }

    [Fact]
    public void InvalidUtf8IsRejectedNotReplaced()
    {
        byte[] bad = Utf8("{\"type\":\"command\",\"action\":\"ping\"}");
        bad[24] = 0xFF; // corrupt one byte inside the string value
        Assert.Null(ProtocolMessage.Decode(bad));
    }

    [Fact]
    public void BinaryGarbageNeverThrows()
    {
        var random = new Random(1234);
        for (int i = 0; i < 500; i++)
        {
            var bytes = new byte[random.Next(0, 80)];
            random.NextBytes(bytes);
            Exception? failure = Record.Exception(() => ProtocolMessage.Decode(bytes));
            Assert.Null(failure);
        }
    }

    [Fact]
    public void OversizedPayloadsAreRejectedBeforeParsing()
    {
        string padding = new('a', WireLimits.MaxInboundBytes);
        byte[] oversized = Utf8($"{{\"type\":\"command\",\"action\":\"{padding}\"}}");
        Assert.True(oversized.Length > WireLimits.MaxInboundBytes);
        Assert.Null(ProtocolMessage.Decode(oversized));
    }

    [Fact]
    public void ThePayloadLimitIsInclusive()
    {
        string json = "{\"type\":\"command\",\"action\":\"" + new string('a', WireLimits.MaxInboundBytes - 30) + "\"}";
        Assert.Equal(WireLimits.MaxInboundBytes, Utf8(json).Length);
        Assert.NotNull(ProtocolMessage.Decode(Utf8(json)));
    }

    [Fact]
    public void DeeplyNestedJsonIsRejected()
    {
        string nested = "{\"type\":\"command\",\"x\":" + new string('[', 40) + new string(']', 40) + "}";
        Assert.Null(ProtocolMessage.Decode(Utf8(nested)));
    }

    [Fact]
    public void LiveStateTypeNamesNeverCollideWithTheCommandProtocol()
    {
        // The tablet tells message families apart by `type` alone.
        var liveTypes = new[]
        {
            "metrics", "media", "track", DesktopInfoMessage.Type, CapabilitiesMessage.Type,
            "artwork_begin", "artwork_end"
        };
        Assert.DoesNotContain(ProtocolMessage.TypeCommand, liveTypes);
        Assert.DoesNotContain(ProtocolMessage.TypeState, liveTypes);
        Assert.Equal(liveTypes.Length, liveTypes.Distinct().Count());
    }

    [Fact]
    public void EncodedMessagesAreValidJsonForAnyStandardParser()
    {
        foreach (byte[] bytes in new[]
                 {
                     ProtocolMessage.State("ok", "volume_up").Encode(),
                     ProtocolMessage.State("connected").Encode(),
                     DesktopInfoMessage.Encode("ALI-PC")
                 })
        {
            using JsonDocument document = JsonDocument.Parse(bytes);
            Assert.Equal(JsonValueKind.Object, document.RootElement.ValueKind);
        }
    }
}
