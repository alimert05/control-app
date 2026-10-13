using System.Text;
using AliControlWindows.Artwork;
using AliControlWindows.Media;
using AliControlWindows.Protocol;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.Artwork;

public class ArtworkWireTests
{
    private const string Id = "0123456789abcdef";
    private static byte[] Utf8(string s) => Encoding.UTF8.GetBytes(s);

    // -- artwork_request --

    [Fact]
    public void DecodesAWellFormedRequest()
    {
        Assert.Equal(Id, ArtworkWire.DecodeRequest(Utf8($"{{\"type\":\"artwork_request\",\"artworkId\":\"{Id}\"}}")));
    }

    [Fact]
    public void FieldOrderDoesNotMatter()
    {
        Assert.Equal(Id, ArtworkWire.DecodeRequest(Utf8($"{{\"artworkId\":\"{Id}\",\"type\":\"artwork_request\"}}")));
    }

    [Theory]
    [InlineData("{\"type\":\"command\",\"artworkId\":\"0123456789abcdef\"}")]            // wrong type
    [InlineData("{\"type\":\"artwork_request\",\"artworkId\":\"abc\"}")]                 // too short
    [InlineData("{\"type\":\"artwork_request\",\"artworkId\":\"0123456789ABCDEF\"}")]    // upper case
    [InlineData("{\"type\":\"artwork_request\",\"artworkId\":\"012345678zzzzzzz\"}")]    // not hex
    [InlineData("{\"type\":\"artwork_request\",\"artworkId\":\"0123456789abcdef0\"}")]   // too long
    [InlineData("{\"type\":\"artwork_request\"}")]                                      // no id
    [InlineData("{\"type\":\"artwork_request\",\"artworkId\":42}")]                      // not a string
    [InlineData("{\"type\":\"artwork_request\",\"artworkId\":null}")]
    [InlineData("")]
    [InlineData("not json")]
    [InlineData("{\"type\":\"artwork_request\",\"artworkId\":\"0123456789abcd")]         // truncated
    public void RejectsAnythingThatIsNotExactlyAValidRequest(string json)
    {
        Assert.Null(ArtworkWire.DecodeRequest(Utf8(json)));
    }

    [Theory]
    [InlineData("/etc/passwd")]
    [InlineData("C:\\\\Windows\\\\System32\\\\config\\\\SAM")]
    [InlineData("https://example.com/a.jpg")]
    [InlineData("..\\\\..\\\\secret")]
    [InlineData("file:///c:/secret.txt")]
    public void AFilePathOrUrlDisguisedAsAnArtworkIdIsNeverAccepted(string disguised)
    {
        // The exact abuse the artwork protocol rules out: this must not look like "a known id".
        Assert.Null(ArtworkWire.DecodeRequest(Utf8($"{{\"type\":\"artwork_request\",\"artworkId\":\"{disguised}\"}}")));
    }

    [Theory]
    [InlineData("path")]
    [InlineData("url")]
    [InlineData("file")]
    [InlineData("filename")]
    [InlineData("anything")]
    public void ARequestCarryingAnyExtraFieldIsRejected(string extra)
    {
        string json = $"{{\"type\":\"artwork_request\",\"artworkId\":\"{Id}\",\"{extra}\":\"x\"}}";
        Assert.Null(ArtworkWire.DecodeRequest(Utf8(json)));
    }

    [Fact]
    public void AnOrdinaryCommandNeverParsesAsAnArtworkRequest()
    {
        Assert.Null(ArtworkWire.DecodeRequest(Utf8("{\"type\":\"command\",\"action\":\"volume_up\"}")));
    }

    [Fact]
    public void OversizedAndBinaryInputIsRejected()
    {
        Assert.Null(ArtworkWire.DecodeRequest(new byte[WireLimits.MaxInboundBytes + 1]));
        Assert.Null(ArtworkWire.DecodeRequest(new byte[] { 0xFF, 0xFE, 0x00 }));
        Assert.Null(ArtworkWire.DecodeRequest(ReadOnlySpan<byte>.Empty));
    }

    [Fact]
    public void ArtworkIdValidation()
    {
        Assert.True(ArtworkWire.IsValidArtworkId(Id));
        Assert.False(ArtworkWire.IsValidArtworkId(null));
        Assert.False(ArtworkWire.IsValidArtworkId(Id.ToUpperInvariant()));
        Assert.False(ArtworkWire.IsValidArtworkId(Id[..15]));
    }

    // -- begin / end --

    [Fact]
    public void BeginMatchesTheFixtureTheTabletParses()
    {
        Assert.Equal(
            "{\"type\":\"artwork_begin\",\"artworkId\":\"0123456789abcdef\",\"size\":12480,\"chunks\":74,\"mime\":\"image/jpeg\"}",
            Encoding.UTF8.GetString(ArtworkWire.EncodeBegin(Id, 12480, 74, ArtworkWire.MimeJpeg)));
    }

    [Fact]
    public void EndMatchesTheFixtureTheTabletParses()
    {
        Assert.Equal("{\"type\":\"artwork_end\",\"artworkId\":\"0123456789abcdef\"}", Encoding.UTF8.GetString(ArtworkWire.EncodeEnd(Id)));
    }

    // -- binary frame --

    [Fact]
    public void ChunkFrameRoundTrips()
    {
        var payload = Enumerable.Range(0, 100).Select(i => (byte)i).ToArray();
        byte[] encoded = new ArtworkChunkFrame(Id, 5, payload).Encode()!;

        ArtworkChunkFrame decoded = ArtworkChunkFrame.Decode(encoded)!;

        Assert.Equal(Id, decoded.ArtworkId);
        Assert.Equal(5, decoded.ChunkIndex);
        Assert.Equal(payload, decoded.Payload);
    }

    [Fact]
    public void ChunkFrameLayoutIsExactlyWhatTheTabletDecodes()
    {
        byte[] frame = new ArtworkChunkFrame(Id, 0x0102, new byte[] { 0xAA, 0xBB }).Encode()!;

        Assert.Equal(ArtworkWire.ChunkHeaderSize + 2, frame.Length);
        Assert.Equal(0xAC, frame[0]);
        Assert.Equal(0x01, frame[1]);
        Assert.Equal(new byte[] { 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF }, frame[2..10]); // raw id bytes
        Assert.Equal(0x01, frame[10]); // index, big-endian
        Assert.Equal(0x02, frame[11]);
        Assert.Equal(new byte[] { 0xAA, 0xBB }, frame[12..]);
    }

    [Fact]
    public void ChunkFrameStartsWithTheMagicByteNeverAJsonBrace()
    {
        byte[] frame = new ArtworkChunkFrame(Id, 0, new byte[] { 0xFF }).Encode()!;
        Assert.Equal(ArtworkWire.ChunkMagicByte, frame[0]);
        Assert.NotEqual((byte)'{', frame[0]);
    }

    [Fact]
    public void ChunkIndexSupportsTheFullSixteenBitRange()
    {
        Assert.Equal(65535, ArtworkChunkFrame.Decode(new ArtworkChunkFrame(Id, 65535, new byte[] { 1 }).Encode()!)!.ChunkIndex);
        Assert.Null(new ArtworkChunkFrame(Id, 65536, new byte[] { 1 }).Encode());
        Assert.Null(new ArtworkChunkFrame(Id, -1, new byte[] { 1 }).Encode());
    }

    [Fact]
    public void ChunkFrameWithABadIdCannotBeEncoded()
    {
        Assert.Null(new ArtworkChunkFrame("nothex", 0, new byte[] { 1 }).Encode());
    }

    [Fact]
    public void DecodeRejectsShortFramesAndWrongMagic()
    {
        Assert.Null(ArtworkChunkFrame.Decode(new byte[] { 0xAC, 0x01, 0x00 }));
        byte[] frame = new ArtworkChunkFrame(Id, 0, new byte[] { 1 }).Encode()!;
        frame[0] = 0x00;
        Assert.Null(ArtworkChunkFrame.Decode(frame));
    }
}

public class ArtworkTransferSessionTests
{
    private const string Id = "0123456789abcdef";

    [Fact]
    public void ChunkingSplitsEvenlyWithAPartialLastChunk()
    {
        Assert.Equal(new[] { 10, 10, 5 }, ArtworkTransferSession.Chunk(new byte[25], 10).Select(c => c.Length));
    }

    [Fact]
    public void ChunkingDataThatDividesEvenlyHasNoTrailingEmptyChunk()
    {
        Assert.Equal(new[] { 10, 10 }, ArtworkTransferSession.Chunk(new byte[20], 10).Select(c => c.Length));
    }

    [Fact]
    public void ChunkPayloadSizeIsDerivedFromTheNegotiatedLimitNotHardcoded()
    {
        int at182 = ArtworkTransferSession.ChunkPayloadSize(182, capAt: 1000);
        int at100 = ArtworkTransferSession.ChunkPayloadSize(100, capAt: 1000);

        Assert.Equal(182 - ArtworkWire.ChunkHeaderSize, at182);
        Assert.Equal(100 - ArtworkWire.ChunkHeaderSize, at100);
    }

    [Fact]
    public void ChunkPayloadSizeRespectsTheOverallNotificationCap()
    {
        Assert.Equal(160 - ArtworkWire.ChunkHeaderSize, ArtworkTransferSession.ChunkPayloadSize(5000, capAt: 160));
        Assert.Equal(160 - ArtworkWire.ChunkHeaderSize, ArtworkTransferSession.ChunkPayloadSize(5000));
    }

    [Fact]
    public void ChunkPayloadSizeNeverGoesNegative()
    {
        Assert.Equal(0, ArtworkTransferSession.ChunkPayloadSize(5, capAt: 5));
    }

    [Fact]
    public void FramesAreBeginThenExactlyTheRightNumberOfChunksThenEnd()
    {
        IReadOnlyList<byte[]> frames = ArtworkTransferSession.BuildFrames(Id, "image/jpeg", Enumerable.Repeat((byte)0xAB, 250).ToArray(), 100)!;

        Assert.Equal(1 + 3 + 1, frames.Count); // 100 + 100 + 50
        Assert.Equal((byte)'{', frames[0][0]);
        Assert.Equal((byte)'{', frames[^1][0]);
        Assert.Contains("\"chunks\":3", Encoding.UTF8.GetString(frames[0]));
        Assert.Contains("\"size\":250", Encoding.UTF8.GetString(frames[0]));
    }

    [Fact]
    public void TheLastChunkIsThePartialRemainder()
    {
        var frames = ArtworkTransferSession.BuildFrames(Id, "image/jpeg", new byte[250], 100)!;
        ArtworkChunkFrame last = ArtworkChunkFrame.Decode(frames[^2])!;
        Assert.Equal(2, last.ChunkIndex);
        Assert.Equal(50, last.Payload.Length);
    }

    [Fact]
    public void ReassemblingTheChunksReproducesThePayloadExactly()
    {
        byte[] payload = Enumerable.Range(0, 777).Select(i => (byte)(i % 256)).ToArray();
        var frames = ArtworkTransferSession.BuildFrames(Id, "image/jpeg", payload, 47)!;

        byte[] reassembled = frames.Skip(1).Take(frames.Count - 2)
            .Select(f => ArtworkChunkFrame.Decode(f)!)
            .OrderBy(f => f.ChunkIndex)
            .SelectMany(f => f.Payload)
            .ToArray();

        Assert.Equal(payload, reassembled);
    }

    [Fact]
    public void ChunkIndexesAreSequentialFromZero()
    {
        var frames = ArtworkTransferSession.BuildFrames(Id, "image/jpeg", new byte[1000], 100)!;
        var indexes = frames.Skip(1).Take(frames.Count - 2).Select(f => ArtworkChunkFrame.Decode(f)!.ChunkIndex);
        Assert.Equal(Enumerable.Range(0, 10), indexes);
    }

    [Theory]
    [InlineData("not-hex", 1, 10)]
    [InlineData(Id, 0, 10)]
    [InlineData(Id, 1, 0)]
    [InlineData(Id, 1, -4)]
    public void InvalidInputsBuildNothing(string id, int payloadBytes, int chunkSize)
    {
        Assert.Null(ArtworkTransferSession.BuildFrames(id, "image/jpeg", new byte[payloadBytes], chunkSize));
    }

    [Fact]
    public void ATransferNeedingMoreChunksThanTheTabletAcceptsBuildsNothing()
    {
        // 20 KiB at 8 bytes per chunk = 2560 chunks > 512.
        Assert.Null(ArtworkTransferSession.BuildFrames(Id, "image/jpeg", new byte[20 * 1024], 8));
    }

    [Fact]
    public void EveryFrameFitsTheNotificationBudget()
    {
        int chunkPayload = ArtworkTransferSession.ChunkPayloadSize(182);
        var frames = ArtworkTransferSession.BuildFrames(Id, "image/jpeg", new byte[12_480], chunkPayload)!;
        Assert.All(frames, f => Assert.True(f.Length <= WireLimits.MaxNotificationBytes));
    }

    [Fact]
    public void FramesThatDoNotFitAnUnusuallySmallLimitAreDetected()
    {
        var frames = ArtworkTransferSession.BuildFrames(Id, "image/jpeg", new byte[50], 8)!;
        Assert.False(ArtworkTransferSession.FramesFitWithin(frames, 20));
        Assert.True(ArtworkTransferSession.FramesFitWithin(frames, 160));
        Assert.False(ArtworkTransferSession.FramesFitWithin(frames, 0));
    }
}

public class ArtworkProcessorTests
{
    [Fact]
    public async Task TheFirstQualityThatFitsIsUsed()
    {
        var codec = new FakeImageCodec { SizeForQuality = _ => 5_000 };
        var result = await new ArtworkProcessor(codec).ProcessAsync(new byte[] { 1, 2, 3 });

        Assert.NotNull(result);
        Assert.Equal(new[] { 0.6 }, codec.EncodedQualities);
        Assert.Equal(5_000, result!.Data.Length);
        Assert.Equal("image/jpeg", result.MimeType);
    }

    [Fact]
    public async Task QualityStepsDownUntilTheImageFitsTheCeiling()
    {
        var codec = new FakeImageCodec { SizeForQuality = q => q >= 0.6 ? 30_000 : q >= 0.4 ? 25_000 : 18_000 };
        var result = await new ArtworkProcessor(codec).ProcessAsync(new byte[] { 1 });

        Assert.Equal(new[] { 0.6, 0.4, 0.25 }, codec.EncodedQualities);
        Assert.Equal(18_000, result!.Data.Length);
    }

    [Fact]
    public async Task AnImageThatNeverFitsIsSkippedNotSentOversized()
    {
        var codec = new FakeImageCodec { SizeForQuality = _ => ArtworkProcessor.MaxOutputBytes + 1 };
        Assert.Null(await new ArtworkProcessor(codec).ProcessAsync(new byte[] { 1 }));
        Assert.Equal(3, codec.EncodedQualities.Count);
    }

    [Fact]
    public async Task TheCeilingIsInclusive()
    {
        var codec = new FakeImageCodec { SizeForQuality = _ => ArtworkProcessor.MaxOutputBytes };
        Assert.NotNull(await new ArtworkProcessor(codec).ProcessAsync(new byte[] { 1 }));
    }

    [Fact]
    public async Task UndecodableBytesProduceNoArtwork()
    {
        var codec = new FakeImageCodec { Decodable = false };
        Assert.Null(await new ArtworkProcessor(codec).ProcessAsync(new byte[] { 1, 2 }));
        Assert.Empty(codec.EncodedQualities);
    }

    [Fact]
    public async Task EmptyInputProducesNoArtworkWithoutTouchingTheCodec()
    {
        var codec = new FakeImageCodec();
        Assert.Null(await new ArtworkProcessor(codec).ProcessAsync(Array.Empty<byte>()));
        Assert.Equal(0, codec.DecodeCount);
    }

    [Fact]
    public async Task ACodecThatThrowsNeverTakesTheCallerDown()
    {
        var processor = new ArtworkProcessor(new ThrowingCodec());
        Assert.Null(await processor.ProcessAsync(new byte[] { 1 }));
    }

    private sealed class ThrowingCodec : IImageCodec
    {
        public Task<PixelBuffer?> DecodeSquareAsync(byte[] encoded, int size) => throw new InvalidOperationException();
        public Task<byte[]?> EncodeJpegAsync(PixelBuffer pixels, double quality) => throw new InvalidOperationException();
    }

    [Fact]
    public async Task ArtworkIdIsDeterministicSameImageSameId()
    {
        var processor = new ArtworkProcessor(new FakeImageCodec());
        var a = await processor.ProcessAsync(new byte[] { 1, 2, 3 });
        var b = await processor.ProcessAsync(new byte[] { 1, 2, 3 });
        Assert.Equal(a!.ArtworkId, b!.ArtworkId);
    }

    [Fact]
    public async Task ADifferentImageGetsADifferentId()
    {
        var processor = new ArtworkProcessor(new FakeImageCodec());
        var a = await processor.ProcessAsync(new byte[] { 1, 2, 3 });
        var b = await processor.ProcessAsync(new byte[] { 9, 9, 9 });
        Assert.NotEqual(a!.ArtworkId, b!.ArtworkId);
    }

    [Fact]
    public void ArtworkIdIsTheFirstEightBytesOfSha256AsLowercaseHex()
    {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        Assert.Equal("ba7816bf8f01cfea", ArtworkProcessor.ArtworkIdFor(Encoding.ASCII.GetBytes("abc")));
        Assert.True(ArtworkWire.IsValidArtworkId(ArtworkProcessor.ArtworkIdFor(new byte[] { 1 })));
    }

    [Fact]
    public void ArtworkIdMatchesTheSha256FixtureSharedWithTheTablet()
    {
        // The tablet's ArtworkProtocolTest asserts the exact same id for the exact same bytes
        // (java.security.MessageDigest) — proof both sides agree on "first 8 SHA-256 bytes, lowercase hex".
        Assert.Equal("f09bb5200116bd26", ArtworkProcessor.ArtworkIdFor(Encoding.UTF8.GetBytes("ali-control-artwork-fixture")));
    }

    [Fact]
    public void TheTargetIsTheSizeTheTabletsDecoderExpects()
    {
        Assert.Equal(160, ArtworkProcessor.TargetDimension);
        Assert.Equal(20 * 1024, ArtworkProcessor.MaxOutputBytes);
    }
}

public class ArtworkStoreAndPipelineTests
{
    private static ProcessedArtwork Art(string id, int size = 10) => new(id, new byte[size], "image/jpeg");
    private static string Id(int n) => n.ToString("x16");

    // -- store --

    [Fact]
    public void StoredArtworkIsFoundByIdAndByTrack()
    {
        var store = new ArtworkStore();
        store.Store(Art(Id(1)), "track-1");

        Assert.Equal(Id(1), store.Find(Id(1))?.ArtworkId);
        Assert.True(store.TryGetCachedResult("track-1", out var cached));
        Assert.Equal(Id(1), cached?.ArtworkId);
    }

    [Fact]
    public void UnknownIdsAndUncheckedTracksAreNotFound()
    {
        var store = new ArtworkStore();
        Assert.Null(store.Find(Id(99)));
        Assert.False(store.TryGetCachedResult("never-seen", out _));
    }

    [Fact]
    public void ATrackWithNoArtworkIsRememberedSoItIsNotAskedAgain()
    {
        var store = new ArtworkStore();
        store.StoreNoArtwork("track-x");

        Assert.True(store.TryGetCachedResult("track-x", out var cached));
        Assert.Null(cached);
    }

    [Fact]
    public void TheCacheIsBoundedAndEvictsTheLeastRecentlyUsed()
    {
        var store = new ArtworkStore(capacity: 3);
        for (int i = 1; i <= 3; i++) store.Store(Art(Id(i)), $"t{i}");
        store.Find(Id(1)); // touch 1 -> 2 becomes the oldest
        store.Store(Art(Id(4)), "t4");

        Assert.NotNull(store.Find(Id(1)));
        Assert.Null(store.Find(Id(2)));
        Assert.NotNull(store.Find(Id(3)));
        Assert.NotNull(store.Find(Id(4)));
        Assert.Equal(3, store.CachedArtworkIds.Count);
    }

    [Fact]
    public void TheTrackIdentityCacheIsBoundedIndependentlyOfTheArtworkIdCache()
    {
        var store = new ArtworkStore(capacity: 2);
        store.StoreNoArtwork("t1");
        store.StoreNoArtwork("t2");
        store.StoreNoArtwork("t3");

        Assert.False(store.TryGetCachedResult("t1", out _)); // evicted
        Assert.True(store.TryGetCachedResult("t3", out ProcessedArtwork? none));
        Assert.Null(none);
    }

    [Fact]
    public void ReStoringTheSameArtworkDoesNotEvictAnythingElse()
    {
        var store = new ArtworkStore(capacity: 2);
        store.Store(Art(Id(1)), "t1");
        store.Store(Art(Id(2)), "t2");
        store.Store(Art(Id(2)), "t2");

        Assert.NotNull(store.Find(Id(1)));
        Assert.NotNull(store.Find(Id(2)));
    }

    // -- pipeline --

    private static MediaState Playing(string title = "Song") => new("a", "spotify", MediaPlaybackState.Playing, title, "Artist", "Album");

    private static (ArtworkPipeline Pipeline, FakeMediaSource Source, MediaSessionCoordinator Coordinator, int[] ProcessCount) Create(
        byte[]? artwork, Func<byte[], ProcessedArtwork?>? processor = null)
    {
        var source = new FakeMediaSource { Artwork = artwork };
        source.Sessions = new() { Playing() };
        var coordinator = new MediaSessionCoordinator(source);
        var count = new[] { 0 };
        var pipeline = new ArtworkPipeline(new ArtworkStore(), raw =>
        {
            count[0]++;
            return Task.FromResult(processor != null ? processor(raw) : Art(Id(7)));
        });
        return (pipeline, source, coordinator, count);
    }

    [Fact]
    public async Task ArtworkIsFetchedAndProcessedOncePerTrack()
    {
        var (pipeline, source, coordinator, count) = Create(new byte[] { 1 });
        var state = (await coordinator.CurrentStateAsync())!;

        var first = await pipeline.GetArtworkAsync(state, coordinator);
        var second = await pipeline.GetArtworkAsync(state, coordinator);

        Assert.Equal(ArtworkPipeline.ResultKind.Ready, first.Kind);
        Assert.Equal(ArtworkPipeline.ResultKind.Ready, second.Kind);
        Assert.Equal(1, source.ArtworkFetchCount);
        Assert.Equal(1, count[0]);
    }

    [Fact]
    public async Task ATrackWithoutArtworkIsOnlyAskedAboutOnce()
    {
        var (pipeline, source, coordinator, count) = Create(artwork: null);
        var state = (await coordinator.CurrentStateAsync())!;

        Assert.Equal(ArtworkPipeline.ResultKind.Unavailable, (await pipeline.GetArtworkAsync(state, coordinator)).Kind);
        Assert.Equal(ArtworkPipeline.ResultKind.Unavailable, (await pipeline.GetArtworkAsync(state, coordinator)).Kind);
        Assert.Equal(1, source.ArtworkFetchCount);
        Assert.Equal(0, count[0]);
    }

    [Fact]
    public async Task ArtworkThatCannotBeProcessedIsRememberedAsNone()
    {
        var (pipeline, source, coordinator, count) = Create(new byte[] { 1 }, processor: _ => null);
        var state = (await coordinator.CurrentStateAsync())!;

        Assert.Equal(ArtworkPipeline.ResultKind.Unavailable, (await pipeline.GetArtworkAsync(state, coordinator)).Kind);
        Assert.Equal(ArtworkPipeline.ResultKind.Unavailable, (await pipeline.GetArtworkAsync(state, coordinator)).Kind);
        Assert.Equal(1, count[0]);
    }

    [Fact]
    public async Task ATrackChangeMidFetchIsReportedAndNothingIsCached()
    {
        var (pipeline, source, coordinator, count) = Create(new byte[] { 1 });
        source.OnArtworkFetch = () => source.Sessions = new() { Playing("Other") };
        var state = (await coordinator.CurrentStateAsync())!;

        Assert.Equal(ArtworkPipeline.ResultKind.TrackChanged, (await pipeline.GetArtworkAsync(state, coordinator)).Kind);
        Assert.Equal(0, count[0]);
    }

    [Fact]
    public async Task ADifferentTrackIsProcessedIndependently()
    {
        var (pipeline, source, coordinator, count) = Create(new byte[] { 1 });
        var first = (await coordinator.CurrentStateAsync())!;
        await pipeline.GetArtworkAsync(first, coordinator);

        source.Sessions = new() { Playing("Another song") };
        var second = (await coordinator.CurrentStateAsync())!;
        await pipeline.GetArtworkAsync(second, coordinator);

        Assert.Equal(2, count[0]);
        Assert.Equal(2, source.ArtworkFetchCount);
    }

    [Fact]
    public async Task LookupOnlyReturnsArtworkTheAgentItselfProduced()
    {
        var (pipeline, _, coordinator, _) = Create(new byte[] { 1 });
        var state = (await coordinator.CurrentStateAsync())!;
        await pipeline.GetArtworkAsync(state, coordinator);

        Assert.NotNull(pipeline.Lookup(Id(7)));
        Assert.Null(pipeline.Lookup(Id(8)));
        Assert.Null(pipeline.Lookup("/etc/passwd"));
    }
}

/// <summary>The real WinRT imaging codec, on in-memory data only (no disk, no network, no system state).</summary>
public class WinRtImageCodecTests
{
    /// <summary>A valid 24-bit BMP of a left-half-red / right-half-blue image, so crop and scale can be checked by pixel.</summary>
    private static byte[] TwoColourBmp(int width, int height)
    {
        int rowSize = (width * 3 + 3) / 4 * 4;
        int pixelBytes = rowSize * height;
        using var ms = new MemoryStream();
        using var w = new BinaryWriter(ms);
        w.Write((byte)'B'); w.Write((byte)'M');
        w.Write(54 + pixelBytes); w.Write(0); w.Write(54);
        w.Write(40); w.Write(width); w.Write(height); w.Write((short)1); w.Write((short)24);
        w.Write(0); w.Write(pixelBytes); w.Write(2835); w.Write(2835); w.Write(0); w.Write(0);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                bool left = x < width / 2;
                w.Write((byte)(left ? 0 : 255));   // B
                w.Write((byte)0);                  // G
                w.Write((byte)(left ? 255 : 0));   // R
            }
            for (int pad = width * 3; pad < rowSize; pad++) w.Write((byte)0);
        }
        return ms.ToArray();
    }

    [Fact]
    public async Task DecodesCropsAndResizesToASquare()
    {
        var codec = new WinRtImageCodec();

        PixelBuffer? pixels = await codec.DecodeSquareAsync(TwoColourBmp(300, 200), 160);

        Assert.NotNull(pixels);
        Assert.Equal(160, pixels!.Value.Width);
        Assert.Equal(160, pixels.Value.Height);
        Assert.Equal(160 * 160 * 4, pixels.Value.Bgra.Length);

        // A centred crop of a 300x200 left-red/right-blue image: the vertical split stays in the middle.
        byte[] bgra = pixels.Value.Bgra;
        int At(int x, int y) => (y * 160 + x) * 4;
        Assert.True(bgra[At(10, 80) + 2] > 200 && bgra[At(10, 80)] < 60, "left side should be red");
        Assert.True(bgra[At(150, 80)] > 200 && bgra[At(150, 80) + 2] < 60, "right side should be blue");
    }

    [Fact]
    public async Task EncodesAValidSmallJpeg()
    {
        var codec = new WinRtImageCodec();
        PixelBuffer pixels = (await codec.DecodeSquareAsync(TwoColourBmp(400, 400), 160))!.Value;

        byte[]? jpeg = await codec.EncodeJpegAsync(pixels, 0.6);

        Assert.NotNull(jpeg);
        Assert.Equal(0xFF, jpeg![0]);
        Assert.Equal(0xD8, jpeg[1]); // JPEG start-of-image
        Assert.True(jpeg.Length > 100 && jpeg.Length <= ArtworkProcessor.MaxOutputBytes, $"{jpeg.Length} bytes");
    }

    [Fact]
    public async Task LowerQualityProducesASmallerFile()
    {
        var codec = new WinRtImageCodec();
        // Noise-like content so quality really matters.
        var noise = new byte[160 * 160 * 4];
        new Random(3).NextBytes(noise);
        var pixels = new PixelBuffer(160, 160, noise);

        byte[] high = (await codec.EncodeJpegAsync(pixels, 0.9))!;
        byte[] low = (await codec.EncodeJpegAsync(pixels, 0.25))!;

        Assert.True(low.Length < high.Length);
    }

    [Fact]
    public async Task EndToEndProcessingProducesAnArtworkTheTabletCanReassembleAndVerify()
    {
        var processor = new ArtworkProcessor(new WinRtImageCodec());

        ProcessedArtwork? artwork = await processor.ProcessAsync(TwoColourBmp(500, 320));

        Assert.NotNull(artwork);
        Assert.True(ArtworkWire.IsValidArtworkId(artwork!.ArtworkId));
        Assert.Equal(ArtworkProcessor.ArtworkIdFor(artwork.Data), artwork.ArtworkId);
        Assert.True(artwork.Data.Length <= ArtworkProcessor.MaxOutputBytes);

        // And round-trips through the transfer framing byte for byte.
        var frames = ArtworkTransferSession.BuildFrames(artwork.ArtworkId, artwork.MimeType, artwork.Data,
            ArtworkTransferSession.ChunkPayloadSize(182))!;
        byte[] reassembled = frames.Skip(1).Take(frames.Count - 2).SelectMany(f => ArtworkChunkFrame.Decode(f)!.Payload).ToArray();
        Assert.Equal(artwork.Data, reassembled);
    }

    [Fact]
    public async Task GarbageIsRejectedNotCrashedOn()
    {
        var codec = new WinRtImageCodec();
        Assert.Null(await codec.DecodeSquareAsync(new byte[] { 1, 2, 3, 4, 5 }, 160));
        Assert.Null(await codec.DecodeSquareAsync(Array.Empty<byte>(), 160));
        Assert.Null(await new ArtworkProcessor(codec).ProcessAsync(Encoding.ASCII.GetBytes("this is not an image")));
    }

    [Fact]
    public async Task AMalformedPixelBufferIsRejected()
    {
        Assert.Null(await new WinRtImageCodec().EncodeJpegAsync(new PixelBuffer(160, 160, new byte[10]), 0.6));
    }
}
