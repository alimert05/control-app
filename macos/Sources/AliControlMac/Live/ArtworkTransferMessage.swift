import Foundation

/// Wire format for TASK-009A's dedicated artwork channel — deliberately kept
/// separate from `LiveStateMessage`'s small metrics/media/track JSON
/// messages, since artwork is fundamentally different: large, binary, and
/// transferred on request rather than pushed every sample.
///
/// Three message kinds:
///
/// - **`artwork_request`** (Android → macOS, JSON, over the *command*
///   characteristic — the same one existing commands use): the only input
///   Android ever sends here, and it is exactly one field, `artworkId`. It is
///   validated as a fixed-length hex string and looked up in
///   `ArtworkPipeline`'s cache; anything else — a path, a filename, a URL, an
///   id this Mac never produced — is simply not found and nothing is sent.
///   There is no way to ask this Mac for an arbitrary file.
/// - **`artwork_begin` / `artwork_end`** (macOS → Android, JSON, over the
///   *state* characteristic — same one as metrics/media/track and command
///   acks): small, infrequent, so JSON is the simplest correct choice, same
///   as everywhere else in this protocol.
/// - **chunk** (macOS → Android, compact **binary**, same state
///   characteristic): the one high-frequency, payload-heavy message, so this
///   is the one place base64-in-JSON is deliberately avoided in favor of a
///   fixed 12-byte header directly in front of raw JPEG bytes.
enum ArtworkWire {

    static let requestType = "artwork_request"
    static let beginType = "artwork_begin"
    static let endType = "artwork_end"

    /// artworkId is always exactly this many lowercase hex characters (see
    /// `ArtworkProcessor.artworkIdByteLength`). Anything else can never be a
    /// real id and is rejected before even touching the cache.
    static let artworkIdHexLength = ArtworkProcessor.artworkIdByteLength * 2

    /// First byte of a chunk frame — chosen so it can never be mistaken for
    /// the start of a JSON object (`{` is 0x7B) or valid UTF-8 continuing
    /// straight into printable text, making "is this JSON or a chunk frame"
    /// a single, unambiguous byte check on the receiving side.
    static let chunkMagicByte: UInt8 = 0xAC

    static let chunkFrameType: UInt8 = 0x01

    /// magic(1) + frameType(1) + artworkId(8 raw bytes) + chunkIndex(2, big-endian).
    static let chunkHeaderSize = 12

    static func isValidArtworkId(_ artworkId: String) -> Bool {
        artworkId.count == artworkIdHexLength && artworkId.allSatisfy(\.isHexDigit) && artworkId == artworkId.lowercased()
    }
}

/// `{"type":"artwork_request","artworkId":"..."}` — the only message Android
/// sends on this channel, and the only thing macOS ever treats as a request.
struct ArtworkRequestMessage: Codable, Equatable {
    let type: String
    let artworkId: String

    init(artworkId: String) {
        self.type = ArtworkWire.requestType
        self.artworkId = artworkId
    }

    /// Returns nil for anything that isn't exactly this shape with a
    /// well-formed artworkId — including a plain command message, which has
    /// no `artworkId` field at all and so never accidentally parses as this.
    static func decode(_ data: Data) -> ArtworkRequestMessage? {
        guard let message = try? JSONDecoder().decode(ArtworkRequestMessage.self, from: data),
              message.type == ArtworkWire.requestType,
              ArtworkWire.isValidArtworkId(message.artworkId)
        else { return nil }
        return message
    }
}

/// `{"type":"artwork_begin","artworkId":"...","size":12480,"chunks":74,"mime":"image/jpeg"}`
struct ArtworkBeginMessage: Encodable, Equatable {
    let type = ArtworkWire.beginType
    let artworkId: String
    let size: Int
    let chunks: Int
    let mime: String
}

/// `{"type":"artwork_end","artworkId":"..."}`
struct ArtworkEndMessage: Encodable, Equatable {
    let type = ArtworkWire.endType
    let artworkId: String
}

/// One binary chunk frame: a fixed header directly in front of a slice of
/// raw (already-JPEG-compressed) artwork bytes. No base64, no JSON — see
/// `ArtworkWire`'s documentation for why.
struct ArtworkChunkFrame: Equatable {
    let artworkId: String
    let chunkIndex: Int
    let payload: Data

    /// Encodes the fixed header plus [payload] as one contiguous `Data`,
    /// ready to hand straight to a BLE notification.
    func encoded() -> Data? {
        guard let idBytes = Self.hexToBytes(artworkId), idBytes.count == ArtworkProcessor.artworkIdByteLength else { return nil }
        guard chunkIndex >= 0, chunkIndex <= Int(UInt16.max) else { return nil }

        var data = Data(capacity: ArtworkWire.chunkHeaderSize + payload.count)
        data.append(ArtworkWire.chunkMagicByte)
        data.append(ArtworkWire.chunkFrameType)
        data.append(idBytes)
        data.append(UInt8((chunkIndex >> 8) & 0xFF))
        data.append(UInt8(chunkIndex & 0xFF))
        data.append(payload)
        return data
    }

    /// Decodes a frame previously produced by [encoded()]. Only used by
    /// tests on this side (macOS never receives chunk frames), which is what
    /// actually verifies the header round-trips byte-for-byte — the same
    /// decode logic Android implements independently against this format.
    static func decode(_ data: Data) -> ArtworkChunkFrame? {
        guard data.count >= ArtworkWire.chunkHeaderSize else { return nil }
        let bytes = [UInt8](data)
        guard bytes[0] == ArtworkWire.chunkMagicByte, bytes[1] == ArtworkWire.chunkFrameType else { return nil }
        let idBytes = Data(bytes[2..<10])
        let chunkIndex = (Int(bytes[10]) << 8) | Int(bytes[11])
        let payload = Data(bytes[ArtworkWire.chunkHeaderSize...])
        return ArtworkChunkFrame(artworkId: idBytes.map { String(format: "%02x", $0) }.joined(), chunkIndex: chunkIndex, payload: payload)
    }

    private static func hexToBytes(_ hex: String) -> Data? {
        guard hex.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        return Data(bytes)
    }
}
