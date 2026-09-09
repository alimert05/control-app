import Foundation

/// Builds the ordered sequence of encoded notification payloads for one
/// artwork transfer: begin, every chunk, end.
///
/// A pure, static function deliberately kept apart from `PeripheralManager`
/// — it does no CoreBluetooth I/O, so chunk-count math, header sizing and
/// the final-partial-chunk edge case are all directly testable against plain
/// byte counts instead of a real transmit queue.
enum ArtworkTransferSession {

    static func frames(_ frames: [Data], fitWithin maximumUpdateValueLength: Int) -> Bool {
        maximumUpdateValueLength > 0 && frames.allSatisfy { $0.count <= maximumUpdateValueLength }
    }

    /// Splits [artwork] into as many chunks as fit in [maxChunkPayloadBytes]
    /// each (the last one partial), and returns the complete, ordered list
    /// of already-encoded notification payloads: one `artwork_begin` JSON
    /// message, one binary frame per chunk, one `artwork_end` JSON message.
    ///
    /// Returns nil if the artworkId isn't well-formed, if the artwork is
    /// empty, or if [maxChunkPayloadBytes] is too small to carry a header at
    /// all (an extreme low-MTU edge case) — in every one of those cases the
    /// caller sends nothing rather than attempting a transfer that could
    /// never complete correctly.
    static func buildFrames(artworkId: String, mimeType: String, payload: Data, maxChunkPayloadBytes: Int) -> [Data]? {
        guard ArtworkWire.isValidArtworkId(artworkId), !payload.isEmpty, maxChunkPayloadBytes > 0 else { return nil }

        let chunkPayloads = chunk(payload, maxBytes: maxChunkPayloadBytes)
        guard !chunkPayloads.isEmpty else { return nil }

        let begin = ArtworkBeginMessage(artworkId: artworkId, size: payload.count, chunks: chunkPayloads.count, mime: mimeType)
        guard let beginData = begin.encodedLiveState() else { return nil }

        var frames: [Data] = [beginData]
        for (index, chunkPayload) in chunkPayloads.enumerated() {
            guard let frame = ArtworkChunkFrame(artworkId: artworkId, chunkIndex: index, payload: chunkPayload).encoded() else {
                return nil
            }
            frames.append(frame)
        }

        let end = ArtworkEndMessage(artworkId: artworkId)
        guard let endData = end.encodedLiveState() else { return nil }
        frames.append(endData)

        return frames
    }

    /// Pure: splits [data] into chunks of at most [maxBytes] each, in order.
    /// The last chunk is whatever remains (possibly smaller) — never padded,
    /// never merged with an adjacent chunk.
    static func chunk(_ data: Data, maxBytes: Int) -> [Data] {
        guard maxBytes > 0, !data.isEmpty else { return [] }
        var result: [Data] = []
        var offset = data.startIndex
        while offset < data.endIndex {
            let end = data.index(offset, offsetBy: maxBytes, limitedBy: data.endIndex) ?? data.endIndex
            result.append(data.subdata(in: offset..<end))
            offset = end
        }
        return result
    }

    /// The usable payload size per chunk given the central's actual
    /// negotiated notification limit — never a hardcoded byte count. Header
    /// overhead is reserved first; the result is also capped so one artwork
    /// notification never risks exceeding the same overall budget live-state
    /// messages already respect.
    static func chunkPayloadSize(maximumUpdateValueLength: Int, capAt maxNotificationBytes: Int = LiveWireLimits.maxNotificationBytes) -> Int {
        let effectiveLimit = min(maximumUpdateValueLength, maxNotificationBytes)
        return max(0, effectiveLimit - ArtworkWire.chunkHeaderSize)
    }
}
