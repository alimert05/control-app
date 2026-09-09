import XCTest
@testable import AliControlMac

/// The wire protocol (request/begin/chunk/end) and the chunk-splitting/
/// frame-building logic — all pure, no CoreBluetooth involved.
final class ArtworkTransferTests: XCTestCase {

    // MARK: - artwork_request

    func testDecodesAWellFormedArtworkRequest() {
        let json = "{\"type\":\"artwork_request\",\"artworkId\":\"0123456789abcdef\"}"

        let message = ArtworkRequestMessage.decode(Data(json.utf8))

        XCTAssertEqual(message?.artworkId, "0123456789abcdef")
    }

    func testRejectsRequestsWithTheWrongType() {
        let json = "{\"type\":\"command\",\"artworkId\":\"0123456789abcdef\"}"

        XCTAssertNil(ArtworkRequestMessage.decode(Data(json.utf8)))
    }

    func testRejectsMalformedArtworkIds() {
        let tooShort = "{\"type\":\"artwork_request\",\"artworkId\":\"abc\"}"
        let uppercase = "{\"type\":\"artwork_request\",\"artworkId\":\"0123456789ABCDEF\"}"
        let nonHex = "{\"type\":\"artwork_request\",\"artworkId\":\"012345678zzzzzzz\"}"

        XCTAssertNil(ArtworkRequestMessage.decode(Data(tooShort.utf8)))
        XCTAssertNil(ArtworkRequestMessage.decode(Data(uppercase.utf8)))
        XCTAssertNil(ArtworkRequestMessage.decode(Data(nonHex.utf8)))
    }

    func testRejectsAFilePathOrUrlDisguisedAsAnArtworkId() {
        // The exact abuse TASK-009A rules out: this must never be treated as
        // "a known artworkId" just because it parses as JSON.
        let path = "{\"type\":\"artwork_request\",\"artworkId\":\"/etc/passwd\"}"
        let url = "{\"type\":\"artwork_request\",\"artworkId\":\"https://example.com/a.jpg\"}"

        XCTAssertNil(ArtworkRequestMessage.decode(Data(path.utf8)))
        XCTAssertNil(ArtworkRequestMessage.decode(Data(url.utf8)))
    }

    func testAnOrdinaryCommandMessageNeverParsesAsAnArtworkRequest() {
        let json = "{\"type\":\"command\",\"action\":\"volume_up\"}"

        XCTAssertNil(ArtworkRequestMessage.decode(Data(json.utf8)))
    }

    // MARK: - begin / end

    func testBeginMessageSerializesRequiredMetadata() throws {
        let begin = ArtworkBeginMessage(artworkId: "0123456789abcdef", size: 12480, chunks: 74, mime: "image/jpeg")
        let data = try XCTUnwrap(begin.encodedLiveState())
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(object["type"] as? String, "artwork_begin")
        XCTAssertEqual(object["artworkId"] as? String, "0123456789abcdef")
        XCTAssertEqual(object["size"] as? Int, 12480)
        XCTAssertEqual(object["chunks"] as? Int, 74)
        XCTAssertEqual(object["mime"] as? String, "image/jpeg")
    }

    func testEndMessageSerializesTheArtworkId() throws {
        let end = ArtworkEndMessage(artworkId: "0123456789abcdef")
        let data = try XCTUnwrap(end.encodedLiveState())
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(object["type"] as? String, "artwork_end")
        XCTAssertEqual(object["artworkId"] as? String, "0123456789abcdef")
    }

    // MARK: - binary chunk frame

    func testChunkFrameRoundTripsThroughEncodeAndDecode() throws {
        let payload = Data((0..<100).map { UInt8($0) })
        let frame = ArtworkChunkFrame(artworkId: "0123456789abcdef", chunkIndex: 5, payload: payload)

        let encoded = try XCTUnwrap(frame.encoded())
        let decoded = try XCTUnwrap(ArtworkChunkFrame.decode(encoded))

        XCTAssertEqual(decoded, frame)
    }

    func testChunkFrameHeaderIsExactlyTwelveBytes() throws {
        let frame = ArtworkChunkFrame(artworkId: "0123456789abcdef", chunkIndex: 0, payload: Data([0x01, 0x02, 0x03]))
        let encoded = try XCTUnwrap(frame.encoded())

        XCTAssertEqual(encoded.count, ArtworkWire.chunkHeaderSize + 3)
    }

    func testChunkFrameStartsWithTheMagicByteNeverAJsonBrace() throws {
        let frame = ArtworkChunkFrame(artworkId: "0123456789abcdef", chunkIndex: 0, payload: Data([0xFF]))
        let encoded = try XCTUnwrap(frame.encoded())

        XCTAssertEqual(encoded.first, ArtworkWire.chunkMagicByte)
        XCTAssertNotEqual(encoded.first, UInt8(ascii: "{"))
    }

    func testChunkFrameSupportsAnEmptyPayload() throws {
        // Shouldn't occur in practice (chunking never emits a zero-byte
        // slice), but the encoder itself must not choke on it.
        let frame = ArtworkChunkFrame(artworkId: "0123456789abcdef", chunkIndex: 0, payload: Data())
        let encoded = try XCTUnwrap(frame.encoded())

        XCTAssertEqual(encoded.count, ArtworkWire.chunkHeaderSize)
        XCTAssertEqual(ArtworkChunkFrame.decode(encoded)?.payload, Data())
    }

    func testDecodeRejectsDataShorterThanTheHeader() {
        XCTAssertNil(ArtworkChunkFrame.decode(Data([0xAC, 0x01, 0x00])))
    }

    func testDecodeRejectsDataWithTheWrongMagicByte() throws {
        let frame = ArtworkChunkFrame(artworkId: "0123456789abcdef", chunkIndex: 0, payload: Data([0x01]))
        var encoded = try XCTUnwrap(frame.encoded())
        encoded[0] = 0x00

        XCTAssertNil(ArtworkChunkFrame.decode(encoded))
    }

    func testChunkIndexRoundTripsUpToTheSixteenBitLimit() throws {
        let frame = ArtworkChunkFrame(artworkId: "0123456789abcdef", chunkIndex: 65535, payload: Data([0x01]))
        let encoded = try XCTUnwrap(frame.encoded())

        XCTAssertEqual(ArtworkChunkFrame.decode(encoded)?.chunkIndex, 65535)
    }

    func testEncodingFailsForAChunkIndexBeyondTheProtocolLimit() {
        let frame = ArtworkChunkFrame(artworkId: "0123456789abcdef", chunkIndex: 65536, payload: Data([0x01]))

        XCTAssertNil(frame.encoded())
    }

    // MARK: - chunking

    func testChunkSplitsDataIntoEqualSizedPiecesWithAPartialLast() {
        let data = Data(repeating: 0xFF, count: 25)

        let chunks = ArtworkTransferSession.chunk(data, maxBytes: 10)

        XCTAssertEqual(chunks.map(\.count), [10, 10, 5])
    }

    func testChunkOfDataThatDividesEvenlyHasNoTrailingEmptyChunk() {
        let data = Data(repeating: 0xFF, count: 20)

        let chunks = ArtworkTransferSession.chunk(data, maxBytes: 10)

        XCTAssertEqual(chunks.map(\.count), [10, 10])
    }

    func testChunkPayloadSizeIsDerivedFromMaximumUpdateValueLengthNotHardcoded() {
        let size183 = ArtworkTransferSession.chunkPayloadSize(maximumUpdateValueLength: 183, capAt: 1000)
        let size100 = ArtworkTransferSession.chunkPayloadSize(maximumUpdateValueLength: 100, capAt: 1000)

        XCTAssertEqual(size183, 183 - ArtworkWire.chunkHeaderSize)
        XCTAssertEqual(size100, 100 - ArtworkWire.chunkHeaderSize)
        XCTAssertNotEqual(size183, size100, "must scale with the real negotiated limit, not a fixed constant")
    }

    func testChunkPayloadSizeRespectsTheOverallNotificationCapEvenIfMtuIsHuge() {
        let size = ArtworkTransferSession.chunkPayloadSize(maximumUpdateValueLength: 5000, capAt: 160)

        XCTAssertEqual(size, 160 - ArtworkWire.chunkHeaderSize)
    }

    func testChunkPayloadSizeNeverGoesNegative() {
        let size = ArtworkTransferSession.chunkPayloadSize(maximumUpdateValueLength: 5, capAt: 5)

        XCTAssertEqual(size, 0)
    }

    // MARK: - session frame building

    func testBuildFramesProducesBeginThenExactChunkCountThenEnd() {
        let payload = Data(repeating: 0xAB, count: 250)

        let frames = ArtworkTransferSession.buildFrames(
            artworkId: "0123456789abcdef", mimeType: "image/jpeg", payload: payload, maxChunkPayloadBytes: 100
        )!

        // 250 bytes / 100 per chunk = 3 chunks (100, 100, 50).
        XCTAssertEqual(frames.count, 1 + 3 + 1)
    }

    func testBuildFramesBeginDeclaresTheExactChunkCountAndTotalSize() throws {
        let payload = Data(repeating: 0xAB, count: 250)

        let frames = try XCTUnwrap(
            ArtworkTransferSession.buildFrames(artworkId: "0123456789abcdef", mimeType: "image/jpeg", payload: payload, maxChunkPayloadBytes: 100)
        )
        let beginObject = try XCTUnwrap(try JSONSerialization.jsonObject(with: frames.first!) as? [String: Any])

        XCTAssertEqual(beginObject["chunks"] as? Int, 3)
        XCTAssertEqual(beginObject["size"] as? Int, 250)
    }

    func testBuildFramesLastChunkIsThePartialRemainder() throws {
        let payload = Data(repeating: 0xAB, count: 250)

        let frames = try XCTUnwrap(
            ArtworkTransferSession.buildFrames(artworkId: "0123456789abcdef", mimeType: "image/jpeg", payload: payload, maxChunkPayloadBytes: 100)
        )
        let lastChunkFrame = try XCTUnwrap(ArtworkChunkFrame.decode(frames[frames.count - 2]))

        XCTAssertEqual(lastChunkFrame.chunkIndex, 2)
        XCTAssertEqual(lastChunkFrame.payload.count, 50)
    }

    func testBuildFramesReassembledPayloadExactlyMatchesTheOriginal() throws {
        let payload = Data((0..<777).map { UInt8($0 % 256) })

        let frames = try XCTUnwrap(
            ArtworkTransferSession.buildFrames(artworkId: "0123456789abcdef", mimeType: "image/jpeg", payload: payload, maxChunkPayloadBytes: 47)
        )
        let chunkFrames = frames[1..<(frames.count - 1)].compactMap { ArtworkChunkFrame.decode($0) }
        let reassembled = chunkFrames.sorted { $0.chunkIndex < $1.chunkIndex }.reduce(Data()) { $0 + $1.payload }

        XCTAssertEqual(reassembled, payload)
    }

    func testBuildFramesRejectsAnInvalidArtworkId() {
        XCTAssertNil(ArtworkTransferSession.buildFrames(artworkId: "not-hex", mimeType: "image/jpeg", payload: Data([0x01]), maxChunkPayloadBytes: 10))
    }

    func testBuildFramesRejectsEmptyPayload() {
        XCTAssertNil(ArtworkTransferSession.buildFrames(artworkId: "0123456789abcdef", mimeType: "image/jpeg", payload: Data(), maxChunkPayloadBytes: 10))
    }

    func testBuildFramesRejectsANonPositiveChunkSize() {
        XCTAssertNil(ArtworkTransferSession.buildFrames(artworkId: "0123456789abcdef", mimeType: "image/jpeg", payload: Data([0x01]), maxChunkPayloadBytes: 0))
    }

    // MARK: - every frame stays inside the notification budget

    func testEveryProducedFrameFitsInsideTheChunkPayloadBudgetPlusHeader() throws {
        let payload = Data(repeating: 0xCD, count: 12_480)
        let maxChunkPayloadBytes = ArtworkTransferSession.chunkPayloadSize(maximumUpdateValueLength: 185)

        let frames = try XCTUnwrap(
            ArtworkTransferSession.buildFrames(artworkId: "0123456789abcdef", mimeType: "image/jpeg", payload: payload, maxChunkPayloadBytes: maxChunkPayloadBytes)
        )

        for frame in frames {
            XCTAssertLessThanOrEqual(frame.count, LiveWireLimits.maxNotificationBytes)
        }
    }

    func testSessionRejectsControlFramesThatDoNotFitAnUnusuallySmallNegotiatedLimit() throws {
        let frames = try XCTUnwrap(
            ArtworkTransferSession.buildFrames(
                artworkId: "0123456789abcdef", mimeType: "image/jpeg",
                payload: Data(repeating: 0xCD, count: 50), maxChunkPayloadBytes: 8
            )
        )

        XCTAssertFalse(ArtworkTransferSession.frames(frames, fitWithin: 20))
        XCTAssertTrue(ArtworkTransferSession.frames(frames, fitWithin: 160))
    }
}
