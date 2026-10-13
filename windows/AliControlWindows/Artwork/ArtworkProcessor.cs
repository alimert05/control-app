using System.Security.Cryptography;

namespace AliControlWindows.Artwork;

/// <summary>
/// One piece of artwork after it has been resized, compressed and identified —
/// the only form that ever reaches <see cref="ArtworkStore"/> or the wire.
/// </summary>
public sealed class ProcessedArtwork
{
    public ProcessedArtwork(string artworkId, byte[] data, string mimeType)
    {
        ArtworkId = artworkId;
        Data = data;
        MimeType = mimeType;
    }

    public string ArtworkId { get; }
    public byte[] Data { get; }
    public string MimeType { get; }
}

/// <summary>Decoded 8-bit BGRA pixels (<c>Width × Height × 4</c> bytes).</summary>
public readonly record struct PixelBuffer(int Width, int Height, byte[] Bgra);

/// <summary>
/// Image decoding/encoding, abstracted so the compression ladder, size ceiling
/// and identity logic can be tested with fake images. The real implementation
/// is <see cref="WinRtImageCodec"/> (Windows Imaging Component).
/// </summary>
public interface IImageCodec
{
    /// <summary>Decodes <paramref name="encoded"/>, centre-crops to a square and resizes to <paramref name="size"/>². Null if it is not a decodable image.</summary>
    Task<PixelBuffer?> DecodeSquareAsync(byte[] encoded, int size);

    /// <summary>JPEG-encodes at <paramref name="quality"/> (0..1). Null on failure.</summary>
    Task<byte[]?> EncodeJpegAsync(PixelBuffer pixels, double quality);
}

/// <summary>
/// Turns whatever picture bytes a media session hands back into a small,
/// BLE-appropriate JPEG with a stable content identifier.
///
/// <para>160×160 centre-cropped square, JPEG at quality 0.60, then 0.40, then
/// 0.25 until the result is at most 20 KiB; if even the lowest quality is too big
/// the artwork is skipped (never hundreds of kilobytes over BLE — metadata works
/// perfectly well without it). The <c>artworkId</c> is the lowercase hex of the
/// first eight bytes of SHA-256 over the <i>final compressed bytes</i>, so it is
/// stable across tracks sharing an image and independently verifiable on the
/// tablet.</para>
///
/// Callers decide *when* to invoke this (once per track change); the processor
/// itself has no opinion and does no caching (see <see cref="ArtworkStore"/>).
/// </summary>
public sealed class ArtworkProcessor
{
    public const int TargetDimension = 160;
    public const int MaxOutputBytes = ArtworkWire.MaxCompressedBytes;

    /// <summary>Quality steps tried in order. Never re-resizes: dimensions are already minimal.</summary>
    public static readonly IReadOnlyList<double> QualityLadder = new[] { 0.6, 0.4, 0.25 };

    private readonly IImageCodec _codec;

    public ArtworkProcessor(IImageCodec codec) => _codec = codec;

    /// <summary>Null when the bytes don't decode, or no quality step gets under the ceiling — both mean "no artwork for this track".</summary>
    public async Task<ProcessedArtwork?> ProcessAsync(byte[] rawData)
    {
        if (rawData.Length == 0) return null;
        try
        {
            PixelBuffer? pixels = await _codec.DecodeSquareAsync(rawData, TargetDimension).ConfigureAwait(false);
            if (pixels == null) return null;

            foreach (double quality in QualityLadder)
            {
                byte[]? jpeg = await _codec.EncodeJpegAsync(pixels.Value, quality).ConfigureAwait(false);
                if (jpeg is { Length: > 0 } && jpeg.Length <= MaxOutputBytes)
                {
                    return new ProcessedArtwork(ArtworkIdFor(jpeg), jpeg, ArtworkWire.MimeJpeg);
                }
            }
            return null;
        }
        catch
        {
            // A corrupt or exotic image must never take the live-state loop down.
            return null;
        }
    }

    /// <summary>
    /// Pure: first <see cref="ArtworkWire.ArtworkIdByteLength"/> bytes of SHA-256 as lowercase hex —
    /// the same algorithm and truncation the tablet applies to reassembled bytes
    /// to validate a completed transfer (<c>ArtworkHash.kt</c>).
    /// </summary>
    public static string ArtworkIdFor(byte[] data) =>
        Convert.ToHexString(SHA256.HashData(data).AsSpan(0, ArtworkWire.ArtworkIdByteLength)).ToLowerInvariant();
}
