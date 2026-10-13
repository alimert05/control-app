using System.Text;
using System.Text.Json;
using AliControlWindows.Protocol;

namespace AliControlWindows.Artwork;

/// <summary>
/// Wire format of the dedicated artwork channel — deliberately separate from the
/// small metrics/media/track JSON messages, because artwork is large, binary and
/// transferred on request rather than pushed every sample.
///
/// <list type="bullet">
/// <item><b><c>artwork_request</c></b> (tablet → desktop, JSON, on the command
/// characteristic): the only input the tablet ever sends here, and exactly one
/// field, <c>artworkId</c> — validated as a fixed-length lowercase hex string and
/// looked up in the processed-artwork cache. A path, filename, URL or an id this
/// agent never produced is simply not found and nothing is sent. There is no
/// way to ask the agent for an arbitrary file.</item>
/// <item><b><c>artwork_begin</c> / <c>artwork_end</c></b> (desktop → tablet, JSON,
/// on the state characteristic): small and infrequent.</item>
/// <item><b>chunk</b> (desktop → tablet, compact <i>binary</i>, state
/// characteristic): <c>0xAC | 0x01 | artworkId (8 raw bytes) | index (uint16 BE) | JPEG bytes</c>.
/// The first byte can never be mistaken for the start of a JSON object (<c>{</c>
/// is 0x7B).</item>
/// </list>
/// </summary>
public static class ArtworkWire
{
    public const string RequestType = "artwork_request";
    public const string BeginType = "artwork_begin";
    public const string EndType = "artwork_end";
    public const string MimeJpeg = "image/jpeg";

    public const int ArtworkIdByteLength = 8;
    public const int ArtworkIdHexLength = ArtworkIdByteLength * 2;

    public const byte ChunkMagicByte = 0xAC;
    public const byte ChunkFrameType = 0x01;

    /// <summary>magic(1) + frameType(1) + artworkId(8) + chunkIndex(2).</summary>
    public const int ChunkHeaderSize = 12;

    /// <summary>Largest compressed artwork the tablet accepts (20 KiB) and the most chunks it accepts per transfer.</summary>
    public const int MaxCompressedBytes = 20 * 1024;
    public const int MaxChunks = 512;

    public static bool IsValidArtworkId(string? artworkId) =>
        artworkId is { Length: ArtworkIdHexLength } && artworkId.All(c => c is (>= '0' and <= '9') or (>= 'a' and <= 'f'));

    private static readonly UTF8Encoding StrictUtf8 = new(false, throwOnInvalidBytes: true);

    /// <summary>
    /// Returns the requested artworkId, or null for anything that is not exactly
    /// <c>{"type":"artwork_request","artworkId":"&lt;16 lowercase hex&gt;"}</c> —
    /// including an ordinary command (no <c>artworkId</c>) and a request carrying
    /// any extra field.
    /// </summary>
    public static string? DecodeRequest(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty || data.Length > WireLimits.MaxInboundBytes) return null;
        try
        {
            using JsonDocument document = JsonDocument.Parse(StrictUtf8.GetString(data), new JsonDocumentOptions { MaxDepth = 4 });
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;

            string? type = null, artworkId = null;
            foreach (JsonProperty property in root.EnumerateObject())
            {
                if (property.NameEquals("type") && property.Value.ValueKind == JsonValueKind.String) type = property.Value.GetString();
                else if (property.NameEquals("artworkId") && property.Value.ValueKind == JsonValueKind.String) artworkId = property.Value.GetString();
                else return null; // closed message: unknown or mistyped fields are rejected
            }
            return type == RequestType && IsValidArtworkId(artworkId) ? artworkId : null;
        }
        catch (Exception e) when (e is JsonException or DecoderFallbackException)
        {
            return null;
        }
    }

    public static byte[] EncodeBegin(string artworkId, int size, int chunks, string mime) =>
        new JsonWire()
            .String("type", BeginType)
            .String("artworkId", artworkId)
            .Int("size", size)
            .Int("chunks", chunks)
            .String("mime", mime)
            .ToUtf8();

    public static byte[] EncodeEnd(string artworkId) =>
        new JsonWire().String("type", EndType).String("artworkId", artworkId).ToUtf8();
}

/// <summary>One binary chunk frame: a fixed header directly in front of a slice of raw JPEG bytes (no base64).</summary>
public sealed record ArtworkChunkFrame(string ArtworkId, int ChunkIndex, byte[] Payload)
{
    /// <summary>Null for a malformed id or a chunk index beyond the 16-bit protocol limit.</summary>
    public byte[]? Encode()
    {
        if (!ArtworkWire.IsValidArtworkId(ArtworkId)) return null;
        if (ChunkIndex is < 0 or > ushort.MaxValue) return null;

        var frame = new byte[ArtworkWire.ChunkHeaderSize + Payload.Length];
        frame[0] = ArtworkWire.ChunkMagicByte;
        frame[1] = ArtworkWire.ChunkFrameType;
        Convert.FromHexString(ArtworkId).CopyTo(frame, 2);
        frame[10] = (byte)(ChunkIndex >> 8);
        frame[11] = (byte)(ChunkIndex & 0xFF);
        Payload.CopyTo(frame, ArtworkWire.ChunkHeaderSize);
        return frame;
    }

    /// <summary>The inverse of <see cref="Encode"/>. Only used by tests on this side (the agent never receives chunk frames).</summary>
    public static ArtworkChunkFrame? Decode(ReadOnlySpan<byte> data)
    {
        if (data.Length < ArtworkWire.ChunkHeaderSize) return null;
        if (data[0] != ArtworkWire.ChunkMagicByte || data[1] != ArtworkWire.ChunkFrameType) return null;
        string id = Convert.ToHexString(data.Slice(2, ArtworkWire.ArtworkIdByteLength)).ToLowerInvariant();
        int index = (data[10] << 8) | data[11];
        return new ArtworkChunkFrame(id, index, data[ArtworkWire.ChunkHeaderSize..].ToArray());
    }

    public bool Equals(ArtworkChunkFrame? other) =>
        other != null && ArtworkId == other.ArtworkId && ChunkIndex == other.ChunkIndex && Payload.AsSpan().SequenceEqual(other.Payload);

    public override int GetHashCode() => HashCode.Combine(ArtworkId, ChunkIndex, Payload.Length);
}
