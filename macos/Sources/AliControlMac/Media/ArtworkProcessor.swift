import AppKit
import CryptoKit
import Foundation

/// One piece of artwork after it has been resized, compressed, and
/// identified — the only form that ever reaches [ArtworkStore] or the wire.
struct ProcessedArtwork: Equatable {
    let artworkId: String
    let data: Data
    let mimeType: String
}

/// Turns whatever raw picture bytes a media provider hands back into a small,
/// BLE-appropriate JPEG with a stable content identifier.
///
/// Pure and synchronous by design — it does no scripting and touches no BLE
/// API, so every step is directly unit-testable against a synthetic image.
/// Callers (see `ArtworkPipeline`) are responsible for running this off the
/// main thread and only when a track actually changes; the processor itself
/// has no opinion about when it's invoked.
enum ArtworkProcessor {

    /// 160×160 is the larger of TASK-009A's two suggested sizes — it reads
    /// noticeably better on the Media widget's largest layout than 128,
    /// while a resized/compressed JPEG at this size still lands well inside
    /// the 5-20 KB target (verified by `ArtworkProcessorTests` against
    /// several synthetic images, not just asserted).
    static let targetDimension: CGFloat = 160

    /// Starting JPEG quality. Chosen empirically (see the tests) as the
    /// highest quality that keeps a typical 160×160 photographic album cover
    /// comfortably under the target ceiling without an extra compression
    /// pass in the common case.
    static let initialJpegQuality: CGFloat = 0.6

    /// Quality steps tried, in order, if the previous attempt still exceeded
    /// [maxOutputBytes]. Never re-*resizes* — only re-compresses, since
    /// dimension is already minimal.
    static let qualityFallbacks: [CGFloat] = [0.6, 0.4, 0.25]

    /// Hard ceiling. An image that is still too large at the lowest quality
    /// step is skipped entirely rather than sent — TASK-009A is explicit
    /// that hundreds of kilobytes must never go over BLE, and metadata
    /// working without artwork is an explicitly acceptable outcome.
    static let maxOutputBytes = 20 * 1024

    /// artworkId byte length before hex-encoding (16 hex characters) — a
    /// truncated SHA-256, enough for corruption detection and cache identity
    /// without being cryptographic authentication (which TASK-009A
    /// explicitly does not ask for).
    static let artworkIdByteLength = 8

    /// Resizes, compresses and hashes [rawData]. Returns nil if the bytes
    /// don't decode as an image, or if no quality step gets under the size
    /// ceiling — both treated as "no artwork for this track", never as a
    /// crash or a partially-built result.
    static func process(rawData: Data) -> ProcessedArtwork? {
        guard let image = NSImage(data: rawData), image.isValid, image.size.width > 0, image.size.height > 0 else {
            return nil
        }
        guard let resized = resizedBitmap(image, to: targetDimension) else { return nil }

        for quality in qualityFallbacks {
            guard let jpeg = jpegData(resized, quality: quality) else { continue }
            if jpeg.count <= maxOutputBytes {
                return ProcessedArtwork(artworkId: artworkId(for: jpeg), data: jpeg, mimeType: "image/jpeg")
            }
        }
        return nil
    }

    /// Draws [image] into a fresh `targetSize` x `targetSize` bitmap,
    /// center-cropping to a square first so non-square source art (rare, but
    /// not impossible) doesn't come out stretched.
    static func resizedBitmap(_ image: NSImage, to targetSize: CGFloat) -> NSBitmapImageRep? {
        guard let sourceRep = bestRepresentation(of: image) else { return nil }
        let sourceWidth = CGFloat(sourceRep.pixelsWide)
        let sourceHeight = CGFloat(sourceRep.pixelsHigh)
        guard sourceWidth > 0, sourceHeight > 0 else { return nil }

        let cropSide = min(sourceWidth, sourceHeight)
        let cropRect = CGRect(
            x: (sourceWidth - cropSide) / 2,
            y: (sourceHeight - cropSide) / 2,
            width: cropSide,
            height: cropSide
        )

        guard let destination = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: Int(targetSize),
            pixelsHigh: Int(targetSize),
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else { return nil }

        let context = NSGraphicsContext(bitmapImageRep: destination)
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        context?.imageInterpolation = .high
        sourceRep.draw(
            in: CGRect(x: 0, y: 0, width: targetSize, height: targetSize),
            from: cropRect,
            operation: .copy,
            fraction: 1.0,
            respectFlipped: true,
            hints: nil
        )
        NSGraphicsContext.restoreGraphicsState()
        return destination
    }

    private static func bestRepresentation(of image: NSImage) -> NSBitmapImageRep? {
        if let existing = image.representations.compactMap({ $0 as? NSBitmapImageRep }).first {
            return existing
        }
        // Vector/other representations (rare for album art): rasterize via a
        // TIFF round trip rather than failing outright.
        guard let tiff = image.tiffRepresentation else { return nil }
        return NSBitmapImageRep(data: tiff)
    }

    private static func jpegData(_ bitmap: NSBitmapImageRep, quality: CGFloat) -> Data? {
        bitmap.representation(using: .jpeg, properties: [.compressionFactor: quality])
    }

    /// Pure: the first [artworkIdByteLength] bytes of SHA-256, as lowercase
    /// hex — identical algorithm and truncation Android performs on
    /// reassembled bytes to validate a completed transfer (see
    /// `ArtworkHash.kt`), so both sides must agree on this exact scheme.
    static func artworkId(for data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.prefix(artworkIdByteLength).map { String(format: "%02x", $0) }.joined()
    }
}
