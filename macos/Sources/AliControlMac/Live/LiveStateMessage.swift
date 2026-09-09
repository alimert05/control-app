import Foundation

/// Size budget and field limits for desktop→tablet live-state notifications.
///
/// ## Why these numbers
///
/// Android negotiates an ATT MTU of 185 immediately on connect, leaving ~182
/// usable bytes per notification. Track metadata is the only part of this
/// protocol with unbounded length, so it is the only part that can overflow
/// that — and a notification that doesn't fit is truncated by the stack,
/// which would hand the tablet a half-JSON string, potentially split through
/// the middle of a UTF-8 sequence.
///
/// TASK-009 offers three ways out; this uses the first two together, which is
/// the simplest combination that is actually safe:
///
/// 1. **Bounded UTF-8 fields.** Title/artist/album are truncated on whole
///    grapheme boundaries to a fixed *escaped-byte* budget, so a multi-byte
///    character can never be cut in half and the JSON is always valid.
/// 2. **Two semantic messages.** Playback state (small, changes constantly)
///    is sent separately from track identity (larger, changes rarely). That
///    keeps every individual message far below the limit *and* means the
///    frequent message carries none of the unbounded data.
///
/// Application-level fragmentation was deliberately not built: it would add a
/// reassembly buffer, sequence numbers and a timeout policy to the BLE path —
/// real reliability risk — to carry text that is not useful at that length on
/// a 7-inch dashboard tile anyway.
///
/// `PeripheralManager` additionally refuses, at send time, to write any
/// notification larger than the connected central's own
/// `maximumUpdateValueLength`. Bounded fields make oversize impossible by
/// construction; that check is the belt-and-braces backstop.
enum LiveWireLimits {

    /// Hard cap every live-state notification is designed to stay under,
    /// verified by tests. Comfortably below the ~182 usable bytes at the
    /// negotiated MTU of 185.
    static let maxNotificationBytes = 160

    static let maxTitleBytes = 48
    static let maxArtistBytes = 36
    static let maxAlbumBytes = 26
}

extension String {

    /// Truncates so the JSON-escaped UTF-8 encoding of this string fits in
    /// `maxEscapedBytes`, dropping whole `Character`s (grapheme clusters)
    /// from the end.
    ///
    /// Working in graphemes rather than bytes or unicode scalars is what
    /// guarantees no mid-UTF-8 truncation *and* no broken emoji: "👨‍👩‍👧" is
    /// one Character made of several scalars and 18 bytes, and cutting it
    /// anywhere inside would produce a different (or invalid) glyph.
    ///
    /// The budget is measured against the *escaped* form because that is what
    /// actually travels: a title full of quotes doubles in size once encoded.
    func boundedForWire(maxEscapedBytes: Int) -> String {
        guard maxEscapedBytes > 0 else { return "" }
        guard Self.escapedByteCount(self) > maxEscapedBytes else { return self }

        var result = ""
        var budget = maxEscapedBytes
        for character in self {
            let cost = Self.escapedByteCount(String(character))
            if cost > budget { break }
            budget -= cost
            result.append(character)
        }
        return result
    }

    /// Conservative upper bound on the bytes this string occupies once
    /// JSON-escaped: quotes and backslashes double, other control characters
    /// become a 6-byte `\uXXXX` escape, everything else costs its UTF-8
    /// length.
    static func escapedByteCount(_ value: String) -> Int {
        var total = 0
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\"", "\\":
                total += 2
            case let control where control.value < 0x20:
                total += 6
            default:
                total += String(scalar).utf8.count
            }
        }
        return total
    }
}

/// System telemetry: `{"type":"metrics","cpu":34,"memory":48}`.
///
/// Percentages are whole numbers — a dashboard tile shows `34%`, so decimals
/// would cost payload bytes to display nothing. Unavailable metrics are
/// **omitted entirely** rather than sent as null or zero, which is what lets
/// the tablet distinguish "no GPU data" from "GPU at 0%".
struct MetricsMessage: Encodable, Equatable {

    static let type = "metrics"

    let type = MetricsMessage.type
    let cpu: Int?
    let memory: Int?
    let gpu: Int?
    let cpuTemperature: Int?
    let gpuTemperature: Int?

    enum CodingKeys: String, CodingKey {
        case type
        case cpu
        case memory
        case gpu
        case cpuTemperature = "cpu_temp"
        case gpuTemperature = "gpu_temp"
    }

    init(_ metrics: SystemMetrics) {
        self.cpu = metrics.cpuPercent.map { Int($0.rounded()) }
        self.memory = metrics.memoryPercent.map { Int($0.rounded()) }
        self.gpu = metrics.gpuPercent.map { Int($0.rounded()) }
        self.cpuTemperature = metrics.cpuTemperatureCelsius.map { Int($0.rounded()) }
        self.gpuTemperature = metrics.gpuTemperatureCelsius.map { Int($0.rounded()) }
    }
}

/// Playback state: `{"type":"media","available":true,"source":"spotify","playing":true,"position":83,"duration":214}`
/// or, when nothing is playable, simply `{"type":"media","available":false}`.
///
/// Carries no track text at all, so its size is bounded by construction and
/// it can be sent on every sampling tick without ever approaching the MTU.
struct PlaybackMessage: Encodable, Equatable {

    static let type = "media"

    let type = PlaybackMessage.type
    let available: Bool
    let source: String?
    let playing: Bool?
    let position: Int?
    let duration: Int?

    init(_ state: MediaState?) {
        guard let state, state.hasTrack else {
            self.available = false
            self.source = nil
            self.playing = nil
            self.position = nil
            self.duration = nil
            return
        }
        self.available = true
        self.source = state.source.rawValue
        self.playing = state.isPlaying
        self.position = state.positionSeconds.map { Int($0.rounded()) }
        self.duration = state.durationSeconds.map { Int($0.rounded()) }
    }
}

/// Track identity: `{"type":"track","title":"…","artist":"…","album":"…","artworkId":"…"}`.
///
/// Sent only when the track actually changes, and every text field is
/// bounded per `LiveWireLimits`. `artworkId` (TASK-009A) identifies the
/// track's processed artwork, if any was retrievable — the tablet uses it to
/// check its own cache and, only on a miss, request the image bytes
/// separately over the dedicated artwork channel. It is never the artwork
/// itself, so it costs this message nothing close to what an image would.
struct TrackMessage: Encodable, Equatable {

    static let type = "track"

    let type = TrackMessage.type
    let title: String?
    let artist: String?
    let album: String?
    let artworkId: String?

    init(_ state: MediaState, artworkId: String? = nil) {
        self.title = state.title?.boundedForWire(maxEscapedBytes: LiveWireLimits.maxTitleBytes)
        self.artist = state.artist?.boundedForWire(maxEscapedBytes: LiveWireLimits.maxArtistBytes)
        self.album = state.album?.boundedForWire(maxEscapedBytes: LiveWireLimits.maxAlbumBytes)
        self.artworkId = artworkId
    }
}

extension Encodable {
    /// Compact JSON bytes for a live-state message. Returns nil rather than
    /// empty data if encoding somehow fails, so a caller can never send a
    /// zero-length "message".
    func encodedLiveState() -> Data? {
        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(self), !data.isEmpty else { return nil }
        return data
    }
}
