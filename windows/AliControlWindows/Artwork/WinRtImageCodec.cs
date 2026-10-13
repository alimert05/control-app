using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace AliControlWindows.Artwork;

/// <summary>
/// Image codec over WinRT imaging (Windows Imaging Component): no third-party
/// image library and no GDI+/System.Drawing dependency. Everything happens on
/// in-memory streams — nothing is read from or written to disk.
/// </summary>
public sealed class WinRtImageCodec : IImageCodec
{
    public async Task<PixelBuffer?> DecodeSquareAsync(byte[] encoded, int size)
    {
        if (encoded.Length == 0 || size <= 0) return null;
        try
        {
            using var input = new InMemoryRandomAccessStream();
            await WriteAllAsync(input, encoded);
            input.Seek(0);

            BitmapDecoder decoder = await BitmapDecoder.CreateAsync(input);
            uint width = decoder.PixelWidth, height = decoder.PixelHeight;
            if (width == 0 || height == 0 || width > 16384 || height > 16384) return null;

            // BitmapTransform applies scaling first and the crop (Bounds) to the
            // *scaled* image, so scale the short side to `size` and crop the
            // long side's centre to a square.
            double scale = (double)size / Math.Min(width, height);
            uint scaledWidth = Math.Max((uint)size, (uint)Math.Round(width * scale));
            uint scaledHeight = Math.Max((uint)size, (uint)Math.Round(height * scale));
            var transform = new BitmapTransform
            {
                ScaledWidth = scaledWidth,
                ScaledHeight = scaledHeight,
                InterpolationMode = BitmapInterpolationMode.Fant,
                Bounds = new BitmapBounds
                {
                    X = (scaledWidth - (uint)size) / 2,
                    Y = (scaledHeight - (uint)size) / 2,
                    Width = (uint)size,
                    Height = (uint)size
                }
            };

            PixelDataProvider pixels = await decoder.GetPixelDataAsync(
                BitmapPixelFormat.Bgra8,
                BitmapAlphaMode.Ignore,
                transform,
                ExifOrientationMode.IgnoreExifOrientation,
                ColorManagementMode.DoNotColorManage);
            byte[] bytes = pixels.DetachPixelData();
            return bytes.Length == size * size * 4 ? new PixelBuffer(size, size, bytes) : null;
        }
        catch
        {
            return null;
        }
    }

    public async Task<byte[]?> EncodeJpegAsync(PixelBuffer pixels, double quality)
    {
        if (pixels.Bgra.Length != pixels.Width * pixels.Height * 4) return null;
        try
        {
            using var output = new InMemoryRandomAccessStream();
            var options = new BitmapPropertySet
            {
                { "ImageQuality", new BitmapTypedValue((float)Math.Clamp(quality, 0.01, 1.0), PropertyType.Single) }
            };
            BitmapEncoder encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, output, options);
            encoder.SetPixelData(
                BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore,
                (uint)pixels.Width, (uint)pixels.Height, 96, 96, pixels.Bgra);
            await encoder.FlushAsync();

            return await ReadAllAsync(output);
        }
        catch
        {
            return null;
        }
    }

    private static async Task WriteAllAsync(IRandomAccessStream stream, byte[] data)
    {
        using var writer = new DataWriter(stream);
        writer.WriteBytes(data);
        await writer.StoreAsync();
        await writer.FlushAsync();
        writer.DetachStream();
    }

    private static async Task<byte[]> ReadAllAsync(IRandomAccessStream stream)
    {
        stream.Seek(0);
        ulong size = stream.Size;
        using var reader = new DataReader(stream.GetInputStreamAt(0));
        await reader.LoadAsync((uint)size);
        var bytes = new byte[size];
        reader.ReadBytes(bytes);
        return bytes;
    }
}
