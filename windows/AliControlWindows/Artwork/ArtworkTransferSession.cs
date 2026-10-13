using AliControlWindows.Protocol;

namespace AliControlWindows.Artwork;

/// <summary>
/// Builds the ordered sequence of encoded notification payloads for one artwork
/// transfer: <c>artwork_begin</c>, every chunk, <c>artwork_end</c>.
///
/// A pure static class kept apart from the BLE code — it performs no I/O, so
/// chunk-count math, header sizing and the final-partial-chunk edge case are all
/// directly testable against plain byte counts.
/// </summary>
public static class ArtworkTransferSession
{
    /// <summary>Whether every frame fits within the client's real maximum notification size.</summary>
    public static bool FramesFitWithin(IReadOnlyList<byte[]> frames, int maxNotificationBytes) =>
        maxNotificationBytes > 0 && frames.All(frame => frame.Length <= maxNotificationBytes);

    /// <summary>
    /// The usable payload size per chunk given the client's actual notification
    /// limit — never a hardcoded byte count. Header overhead is reserved first,
    /// and the result is also capped so one artwork notification never exceeds the
    /// overall budget live-state messages already respect.
    /// </summary>
    public static int ChunkPayloadSize(int maxNotificationBytes, int capAt = WireLimits.MaxNotificationBytes)
    {
        int effective = Math.Min(maxNotificationBytes, capAt);
        return Math.Max(0, effective - ArtworkWire.ChunkHeaderSize);
    }

    /// <summary>
    /// Splits <paramref name="payload"/> into chunks of at most
    /// <paramref name="maxChunkPayloadBytes"/> (the last one partial) and returns
    /// the complete ordered frame list. Returns null for a malformed artworkId, an
    /// empty payload, a non-positive chunk size, or a chunk count beyond the
    /// protocol's 16-bit index — in each case the caller sends nothing rather than
    /// attempt a transfer that could never complete correctly.
    /// </summary>
    public static IReadOnlyList<byte[]>? BuildFrames(string artworkId, string mimeType, byte[] payload, int maxChunkPayloadBytes)
    {
        if (!ArtworkWire.IsValidArtworkId(artworkId) || payload.Length == 0 || maxChunkPayloadBytes <= 0) return null;

        IReadOnlyList<byte[]> chunks = Chunk(payload, maxChunkPayloadBytes);
        // The tablet refuses transfers declaring more chunks than this; sending one anyway would only waste the link.
        if (chunks.Count == 0 || chunks.Count > ArtworkWire.MaxChunks) return null;

        var frames = new List<byte[]>(chunks.Count + 2)
        {
            ArtworkWire.EncodeBegin(artworkId, payload.Length, chunks.Count, mimeType)
        };
        for (int index = 0; index < chunks.Count; index++)
        {
            byte[]? frame = new ArtworkChunkFrame(artworkId, index, chunks[index]).Encode();
            if (frame == null) return null;
            frames.Add(frame);
        }
        frames.Add(ArtworkWire.EncodeEnd(artworkId));
        return frames;
    }

    /// <summary>Pure: splits <paramref name="data"/> into chunks of at most <paramref name="maxBytes"/>, in order, last one partial, never padded.</summary>
    public static IReadOnlyList<byte[]> Chunk(byte[] data, int maxBytes)
    {
        if (maxBytes <= 0 || data.Length == 0) return Array.Empty<byte[]>();
        var result = new List<byte[]>((data.Length + maxBytes - 1) / maxBytes);
        for (int offset = 0; offset < data.Length; offset += maxBytes)
        {
            int length = Math.Min(maxBytes, data.Length - offset);
            result.Add(data.AsSpan(offset, length).ToArray());
        }
        return result;
    }
}
