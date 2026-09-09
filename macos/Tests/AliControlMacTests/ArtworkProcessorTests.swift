import XCTest
@testable import AliControlMac

/// Resize/compress/hash, driven entirely against synthetic in-memory images
/// — no real Music/Spotify library, no disk access.
final class ArtworkProcessorTests: XCTestCase {

    /// A small solid-color square, encoded as PNG — enough to exercise
    /// decode → resize → compress without needing a real photographic image.
    private func syntheticImage(side: Int = 600, color: NSColor = .systemTeal) -> Data {
        let image = NSImage(size: NSSize(width: side, height: side))
        image.lockFocus()
        color.setFill()
        NSBezierPath(rect: NSRect(x: 0, y: 0, width: side, height: side)).fill()
        // A gradient-ish stripe pattern so JPEG compression has some real
        // detail to work with, closer to a photographic album cover than a
        // flat fill (which JPEG would compress to almost nothing).
        for i in stride(from: 0, to: side, by: 8) {
            NSColor(calibratedHue: CGFloat(i % 360) / 360, saturation: 0.6, brightness: 0.8, alpha: 1).setFill()
            NSRect(x: i, y: 0, width: 4, height: side).fill()
        }
        image.unlockFocus()
        guard let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else {
            XCTFail("failed to build synthetic fixture image")
            return Data()
        }
        return png
    }

    private func nonSquareImage() -> Data {
        let image = NSImage(size: NSSize(width: 400, height: 200))
        image.lockFocus()
        NSColor.systemPink.setFill()
        NSBezierPath(rect: NSRect(x: 0, y: 0, width: 400, height: 200)).fill()
        NSColor.systemPurple.setFill()
        NSRect(x: 0, y: 0, width: 200, height: 200).fill()
        image.unlockFocus()
        guard let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else {
            XCTFail("failed to build synthetic fixture image")
            return Data()
        }
        return png
    }

    // MARK: - Resize target

    func testProcessedArtworkIsResizedToTheTargetDimension() throws {
        let processed = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage()))
        let image = try XCTUnwrap(NSImage(data: processed.data))
        let rep = try XCTUnwrap(image.representations.first)

        XCTAssertEqual(rep.pixelsWide, Int(ArtworkProcessor.targetDimension))
        XCTAssertEqual(rep.pixelsHigh, Int(ArtworkProcessor.targetDimension))
    }

    func testANonSquareSourceImageIsCenterCroppedToSquareNotStretched() throws {
        let processed = try XCTUnwrap(ArtworkProcessor.process(rawData: nonSquareImage()))
        let image = try XCTUnwrap(NSImage(data: processed.data))
        let rep = try XCTUnwrap(image.representations.first)

        XCTAssertEqual(rep.pixelsWide, rep.pixelsHigh, "output must stay square regardless of source aspect ratio")
    }

    // MARK: - Compression output / real encoded-size verification

    func testCompressedOutputStaysWellUnderTheHardCeiling() throws {
        let processed = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage()))

        XCTAssertLessThanOrEqual(processed.data.count, ArtworkProcessor.maxOutputBytes)
    }

    func testCompressedOutputIsInTheExpectedSmallByteRangeForATypicalImage() throws {
        // Real measured size for a 160x160 JPEG with real (non-flat) detail —
        // this is the "choose based on real encoded-size tests" check: a
        // regression here means the quality/dimension defaults drifted.
        let processed = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage()))

        XCTAssertGreaterThan(processed.data.count, 500, "suspiciously small — is this actually image data?")
        XCTAssertLessThan(processed.data.count, 20 * 1024)
    }

    func testOutputIsValidJpeg() throws {
        let processed = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage()))

        XCTAssertEqual(processed.mimeType, "image/jpeg")
        // JPEG's magic bytes.
        XCTAssertEqual(processed.data.prefix(2), Data([0xFF, 0xD8]))
    }

    func testUndecodableRawDataIsRejectedRatherThanCrashing() {
        XCTAssertNil(ArtworkProcessor.process(rawData: Data([0x00, 0x01, 0x02, 0x03])))
        XCTAssertNil(ArtworkProcessor.process(rawData: Data()))
    }

    // MARK: - artworkId (deterministic, content-based)

    func testTheSameImageBytesAlwaysProduceTheSameArtworkId() throws {
        let a = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage(color: .systemTeal)))
        let b = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage(color: .systemTeal)))

        XCTAssertEqual(a.artworkId, b.artworkId)
    }

    func testADifferentImageProducesADifferentArtworkId() throws {
        let a = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage(color: .systemTeal)))
        let b = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage(color: .systemOrange)))

        XCTAssertNotEqual(a.artworkId, b.artworkId)
    }

    func testArtworkIdIsFixedLengthLowercaseHex() throws {
        let processed = try XCTUnwrap(ArtworkProcessor.process(rawData: syntheticImage()))

        XCTAssertEqual(processed.artworkId.count, ArtworkProcessor.artworkIdByteLength * 2)
        XCTAssertEqual(processed.artworkId, processed.artworkId.lowercased())
        XCTAssertTrue(processed.artworkId.allSatisfy(\.isHexDigit))
    }

    func testArtworkIdIsDerivedFromTheFinalCompressedBytesNotTheSourceImage() {
        // Two different source images that happen to compress to identical
        // bytes are indistinguishable by design — the id identifies content
        // that will actually be cached/transferred, not the source.
        let data = Data(repeating: 0xAB, count: 64)

        XCTAssertEqual(ArtworkProcessor.artworkId(for: data), ArtworkProcessor.artworkId(for: data))
    }

    func testArtworkIdMatchesAKnownSha256FixtureSharedWithAndroid() {
        // Cross-platform fixture: Android's ArtworkHashTest asserts the exact
        // same id for the exact same bytes, using java.security.MessageDigest
        // — this is what proves both platforms agree on the truncation
        // scheme (first 8 SHA-256 bytes, lowercase hex).
        let data = Data("ali-control-artwork-fixture".utf8)

        XCTAssertEqual(ArtworkProcessor.artworkId(for: data), "f09bb5200116bd26")
    }
}
