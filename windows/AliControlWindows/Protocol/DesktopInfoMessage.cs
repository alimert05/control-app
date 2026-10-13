namespace AliControlWindows.Protocol;

/// <summary>
/// Capability identifiers the agent can advertise. A capability is advertised
/// only when it is actually implemented and usable on this machine — the
/// tablet never has to guess what the desktop supports.
/// </summary>
public static class CapabilityIds
{
    public const string Actions = "actions";
    public const string TelemetryCpu = "telemetry.cpu";
    public const string TelemetryMemory = "telemetry.memory";
    public const string TelemetryGpu = "telemetry.gpu";
    public const string TelemetryTemperatureCpu = "telemetry.temperature.cpu";
    public const string TelemetryTemperatureGpu = "telemetry.temperature.gpu";
    public const string Media = "media";
    public const string MediaArtwork = "media.artwork";

    public static bool IsWellFormed(string id) =>
        id.Length is > 0 and <= 40 && id.All(c => c is (>= 'a' and <= 'z') or (>= '0' and <= '9') or '.' or '_');
}

/// <summary>
/// <c>{"type":"desktop_info","platform":"windows","protocol":2,"name":"ALI-PC"}</c>
///
/// Sent once, right after the tablet subscribes. It tells the tablet which
/// platform it is talking to so the saved (platform-neutral) dashboard can
/// enable or disable widgets against the connected desktop instead of an
/// assumption. Capabilities follow in one or more
/// <see cref="CapabilitiesMessage"/>s (see there for why they are separate).
/// </summary>
public static class DesktopInfoMessage
{
    public const string Type = "desktop_info";
    public const string PlatformWindows = "windows";

    /// <summary>
    /// 1 = the original action/state/live-state protocol (no desktop identity).
    /// 2 = adds <c>desktop_info</c> and <c>capabilities</c>; everything that existed before is unchanged.
    /// </summary>
    public const int ProtocolVersion = 2;

    public static byte[] Encode(string machineName)
    {
        byte[] encoded = new JsonWire()
            .String("type", Type)
            .String("platform", PlatformWindows)
            .Int("protocol", ProtocolVersion)
            .String("name", WireText.BoundedForWire(machineName, WireLimits.MaxDesktopNameBytes))
            .ToUtf8();
        return encoded.Length <= WireLimits.MaxNotificationBytes
            ? encoded
            : throw new InvalidOperationException("desktop_info exceeds the notification budget");
    }
}

/// <summary>
/// <c>{"type":"capabilities","ids":["actions","telemetry.cpu",…]}</c>
///
/// Capability lists are their own message type, and one logical list may be
/// split across several messages. Dotted IDs are descriptive but long: the
/// full set (all eight possible capabilities) does not fit inside the 160-byte
/// notification budget together with the identity fields, and the protocol
/// never truncates. The tablet treats every <c>capabilities</c> message as
/// additive and clears the set when a new <c>desktop_info</c> arrives.
/// </summary>
public static class CapabilitiesMessage
{
    public const string Type = "capabilities";

    public static IReadOnlyList<byte[]> EncodeAll(IReadOnlyList<string> ids, int maxBytes = WireLimits.MaxNotificationBytes)
    {
        foreach (string id in ids)
        {
            if (!CapabilityIds.IsWellFormed(id)) throw new ArgumentException($"Malformed capability id '{id}'", nameof(ids));
        }

        var messages = new List<byte[]>();
        var batch = new List<string>();
        foreach (string id in ids.Distinct(StringComparer.Ordinal))
        {
            batch.Add(id);
            if (Build(batch).Length <= maxBytes) continue;

            batch.RemoveAt(batch.Count - 1);
            if (batch.Count == 0) throw new InvalidOperationException($"Capability '{id}' cannot fit in {maxBytes} bytes");
            messages.Add(Build(batch));
            batch.Clear();
            batch.Add(id);
        }
        if (batch.Count > 0) messages.Add(Build(batch));
        if (messages.Any(message => message.Length > maxBytes))
        {
            throw new InvalidOperationException($"A capability message cannot fit in {maxBytes} bytes");
        }
        return messages;
    }

    private static byte[] Build(IReadOnlyList<string> batch) =>
        new JsonWire().String("type", Type).StringArray("ids", batch).ToUtf8();
}
