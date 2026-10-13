using System.Text;
using System.Text.Json;

namespace AliControlWindows.Protocol;

/// <summary>
/// Minimal, transport-independent message envelope — the C# side of the
/// protocol the Android tablet speaks (<c>ProtocolMessage.kt</c>). Wire format
/// is compact JSON with <c>type</c>, optional <c>action</c>, optional
/// <c>status</c>; absent fields are omitted (never <c>null</c>), and no side
/// depends on JSON key order.
///
/// Decoding is strict: a payload that is oversized, not valid UTF-8, not a
/// complete JSON object, missing <c>type</c>, or carrying a non-string
/// <c>type</c>/<c>action</c>/<c>status</c> is rejected (null) rather than
/// half-parsed. That is what turns a truncated BLE write into a clean error
/// acknowledgement instead of a misread command.
/// </summary>
public sealed record ProtocolMessage(string Type, string? Action = null, string? Status = null)
{
    public const string TypeCommand = "command";
    public const string TypeState = "state";

    public const string StatusOk = "ok";
    public const string StatusError = "error";
    public const string StatusConnected = "connected";

    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    public static ProtocolMessage Command(string action) => new(TypeCommand, action);

    /// <summary>
    /// <paramref name="action"/> ties a state message back to the command it
    /// acknowledges; it is omitted for unsolicited messages such as the
    /// post-subscribe handshake.
    /// </summary>
    public static ProtocolMessage State(string status, string? action = null) => new(TypeState, action, status);

    public string ToJson() => new JsonWire()
        .String("type", Type)
        .String("action", Action)
        .String("status", Status)
        .ToString();

    public byte[] Encode() => new UTF8Encoding(false).GetBytes(ToJson());

    /// <summary>Returns null for anything that is not a complete, well-formed protocol object.</summary>
    public static ProtocolMessage? Decode(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty || data.Length > WireLimits.MaxInboundBytes) return null;

        string text;
        try
        {
            text = StrictUtf8.GetString(data);
        }
        catch (DecoderFallbackException)
        {
            return null;
        }
        return Decode(text);
    }

    public static ProtocolMessage? Decode(string json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try
        {
            using JsonDocument document = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                MaxDepth = 8,
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow
            });
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;

            if (!TryReadString(root, "type", required: true, out string? type) || string.IsNullOrEmpty(type)) return null;
            if (!TryReadString(root, "action", required: false, out string? action)) return null;
            if (!TryReadString(root, "status", required: false, out string? status)) return null;
            return new ProtocolMessage(type, action, status);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static bool TryReadString(JsonElement root, string name, bool required, out string? value)
    {
        value = null;
        if (!root.TryGetProperty(name, out JsonElement element)) return !required;
        if (element.ValueKind == JsonValueKind.Null) return !required;
        if (element.ValueKind != JsonValueKind.String) return false;
        value = element.GetString();
        return true;
    }
}
