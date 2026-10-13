using System.Text;
using System.Text.Json;
using AliControlWindows.Protocol;

namespace AliControlWindows.Tests.Protocol;

public class DesktopInfoTests
{
    private static JsonDocument Parse(byte[] bytes) => JsonDocument.Parse(bytes);

    [Fact]
    public void DesktopInfoDeclaresPlatformProtocolAndName()
    {
        using JsonDocument document = Parse(DesktopInfoMessage.Encode("ALI-PC"));
        JsonElement root = document.RootElement;
        Assert.Equal("desktop_info", root.GetProperty("type").GetString());
        Assert.Equal("windows", root.GetProperty("platform").GetString());
        Assert.Equal(2, root.GetProperty("protocol").GetInt32());
        Assert.Equal("ALI-PC", root.GetProperty("name").GetString());
    }

    [Fact]
    public void DesktopInfoWireFormIsExactlyWhatTheTabletParses()
    {
        Assert.Equal(
            "{\"type\":\"desktop_info\",\"platform\":\"windows\",\"protocol\":2,\"name\":\"ALI-PC\"}",
            Encoding.UTF8.GetString(DesktopInfoMessage.Encode("ALI-PC")));
    }

    [Fact]
    public void ALongOrOddMachineNameIsBoundedAndNeverBreaksTheMessage()
    {
        string nasty = new string('é', 60) + "\"\\\n";
        byte[] encoded = DesktopInfoMessage.Encode(nasty);

        Assert.True(encoded.Length <= WireLimits.MaxNotificationBytes);
        using JsonDocument document = Parse(encoded);
        string name = document.RootElement.GetProperty("name").GetString()!;
        Assert.True(WireText.EscapedByteCount(name) <= WireLimits.MaxDesktopNameBytes);
        Assert.DoesNotContain('�', name);
    }

    [Fact]
    public void EmptyMachineNameStillProducesValidInfo()
    {
        using JsonDocument document = Parse(DesktopInfoMessage.Encode(""));
        Assert.Equal("", document.RootElement.GetProperty("name").GetString());
    }

    [Fact]
    public void CapabilitiesFitOneMessageWhenTheyCan()
    {
        var ids = new[]
        {
            CapabilityIds.Actions, CapabilityIds.TelemetryCpu, CapabilityIds.TelemetryMemory,
            CapabilityIds.TelemetryGpu, CapabilityIds.Media, CapabilityIds.MediaArtwork
        };
        IReadOnlyList<byte[]> messages = CapabilitiesMessage.EncodeAll(ids);

        Assert.Single(messages);
        Assert.Equal(
            "{\"type\":\"capabilities\",\"ids\":[\"actions\",\"telemetry.cpu\",\"telemetry.memory\",\"telemetry.gpu\",\"media\",\"media.artwork\"]}",
            Encoding.UTF8.GetString(messages[0]));
    }

    [Fact]
    public void TheFullPossibleCapabilitySetIsSplitRatherThanTruncated()
    {
        var all = new[]
        {
            CapabilityIds.Actions, CapabilityIds.TelemetryCpu, CapabilityIds.TelemetryMemory, CapabilityIds.TelemetryGpu,
            CapabilityIds.TelemetryTemperatureCpu, CapabilityIds.TelemetryTemperatureGpu, CapabilityIds.Media, CapabilityIds.MediaArtwork
        };

        IReadOnlyList<byte[]> messages = CapabilitiesMessage.EncodeAll(all);

        Assert.True(messages.Count > 1);
        Assert.All(messages, m => Assert.True(m.Length <= WireLimits.MaxNotificationBytes));

        var received = new List<string>();
        foreach (byte[] message in messages)
        {
            using JsonDocument document = Parse(message);
            Assert.Equal("capabilities", document.RootElement.GetProperty("type").GetString());
            received.AddRange(document.RootElement.GetProperty("ids").EnumerateArray().Select(e => e.GetString()!));
        }
        Assert.Equal(all, received); // nothing lost, nothing reordered
    }

    [Fact]
    public void NoCapabilitiesProducesNoMessages()
    {
        Assert.Empty(CapabilitiesMessage.EncodeAll(Array.Empty<string>()));
    }

    [Fact]
    public void DuplicateCapabilitiesAreSentOnce()
    {
        IReadOnlyList<byte[]> messages = CapabilitiesMessage.EncodeAll(new[] { "actions", "actions" });
        using JsonDocument document = Parse(messages.Single());
        Assert.Single(document.RootElement.GetProperty("ids").EnumerateArray());
    }

    [Theory]
    [InlineData("")]
    [InlineData("Actions")]
    [InlineData("has space")]
    [InlineData("quote\"")]
    public void MalformedCapabilityIdsAreRefused(string id)
    {
        Assert.Throws<ArgumentException>(() => CapabilitiesMessage.EncodeAll(new[] { id }));
    }

    [Fact]
    public void AMessageThatCannotFitTheBudgetIsAnErrorNotATruncation()
    {
        Assert.Throws<InvalidOperationException>(() => CapabilitiesMessage.EncodeAll(new[] { "actions" }, maxBytes: 20));
    }

    [Fact]
    public void OnlyImplementedCapabilitiesAreDefinedAsTemperaturesNotAdvertised()
    {
        // The ids exist so the wire/tablet model can name them, but the agent's
        // composition root only ever advertises what it probed as working.
        Assert.Equal("telemetry.temperature.cpu", CapabilityIds.TelemetryTemperatureCpu);
        Assert.Equal("telemetry.temperature.gpu", CapabilityIds.TelemetryTemperatureGpu);
    }
}
