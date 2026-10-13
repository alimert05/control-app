# TASK-009A — Media Artwork over BLE

> **Historical document.** Written when macOS was the reference platform; the wire protocol and behaviour described here are unchanged. On Windows, CPU/RAM/GPU come from native counters and media/artwork from the system media session instead of Mach APIs and AppleScript. See [docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md) §7 and §10.

## Goal

Add real album artwork support to the existing Media widget without breaking the reliable BLE live-state system from TASK-009.

Current media state already supports:

- title
- artist
- album
- playback state
- position
- duration

TASK-009A adds:

- artwork retrieval on macOS
- artwork resizing/compression
- BLE-safe chunked transfer
- Android reassembly
- artwork caching
- Media widget rendering

The implementation must remain local and BLE-only.

Do not add HTTP, TCP, cloud storage, or remote image URLs.

---

## Core Principles

Artwork is fundamentally different from normal metadata.

Do not attempt to force artwork into the existing small JSON notification messages.

The implementation must:

- transfer artwork only when necessary
- avoid repeated transfer for the same track
- remain safe under BLE disconnect/reconnect
- never block normal command acknowledgements
- never silently truncate binary data
- validate chunk ordering and sizes
- fail gracefully
- keep memory usage low on the Android tablet

---

## Artwork Sources

Support artwork where locally available from:

- Apple Music
- Spotify

Prefer provider-specific local retrieval.

Do not introduce cloud APIs or require Spotify Web API authentication.

If artwork cannot be retrieved for a track:

- metadata must continue to work
- Media widget should fall back to its normal no-artwork state
- do not treat missing artwork as a media failure

---

## Media State Extension

Extend the media state with an artwork identity.

Suggested field:

```text
artworkId
```

Example conceptual media state:

```json
{
  "type": "track",
  "title": "Song",
  "artist": "Artist",
  "album": "Album",
  "artworkId": "abc123"
}
```

`artworkId` should identify the artwork content, not just the track position.

A stable hash of the final resized/compressed artwork bytes is acceptable.

Do not transfer the same artwork again if Android already has it cached.

---

## Artwork Processing on macOS

Before transfer:

1. retrieve artwork locally
2. decode it
3. resize it
4. compress it
5. calculate artworkId
6. send only if needed

Recommended initial target:

```text
128 × 128
```

or:

```text
160 × 160
```

Choose the smallest size that still looks good on the 7-inch tablet.

Avoid transferring original-resolution artwork.

---

## Image Format

Use a compact format that can be decoded reliably on Android API 27.

Preferred candidates:

- JPEG
- PNG if image is already very small

JPEG is preferred for photographic album art if it gives significantly smaller payloads.

Do not introduce a heavy image codec dependency solely for WebP/AVIF.

Use native macOS image APIs.

---

## Compression Target

Aim for artwork files roughly in the range:

```text
5 KB – 20 KB
```

where practical.

Do not enforce an unrealistic exact byte target.

If an image remains excessively large after reasonable resize/compression:

- reduce quality
- reduce dimensions
- or skip artwork

Do not send hundreds of kilobytes over BLE.

---

## Transfer Architecture

Introduce an application-level artwork transfer protocol.

Suggested flow:

```text
track metadata
↓
artworkId known
↓
Android checks cache
↓
Android requests artwork if missing
↓
macOS sends artwork_begin
↓
chunk 0
chunk 1
chunk 2
...
↓
artwork_end
↓
Android validates/reassembles
↓
decode
↓
cache
↓
render
```

Avoid automatically blasting artwork on every track change if Android already has it cached.

A request/response model is preferred.

---

## Artwork Request

Add a safe predefined protocol message.

Example:

```json
{
  "type": "artwork_request",
  "artworkId": "abc123"
}
```

This is not an arbitrary file request.

macOS may only respond with artwork currently known through its media provider/cache.

Do not allow arbitrary filesystem paths.

---

## Artwork Begin Message

Suggested conceptual message:

```json
{
  "type": "artwork_begin",
  "artworkId": "abc123",
  "size": 12480,
  "chunks": 74,
  "mime": "image/jpeg"
}
```

The exact wire structure may differ.

Required metadata:

- artworkId
- total byte size
- chunk count
- format/MIME type if needed

Android must validate size before allocating memory.

---

## Artwork Chunk Message

Binary transfer is preferred if compatible with the existing BLE characteristic design.

If the current characteristic architecture strongly favors encoded payloads, a compact binary frame is still preferred over JSON + base64.

Do not base64-encode the artwork unless there is a strong architectural reason.

Base64 adds unnecessary size overhead.

Suggested conceptual binary frame:

```text
message type
artwork transfer id/hash
chunk index
payload bytes
```

Exact binary encoding is up to the implementation.

---

## Chunk Size

Chunk payload must respect the actual negotiated BLE notification limit.

Use:

```text
maximumUpdateValueLength
```

on macOS.

Do not hardcode 182 bytes.

Reserve space for chunk metadata/header.

Example:

```text
chunkPayloadSize =
maximumUpdateValueLength - protocolHeaderSize
```

Never send an oversized notification.

---

## Flow Control

CoreBluetooth may return `false` from:

```text
updateValue(...)
```

when its transmit queue is full.

Artwork transfer must correctly pause and resume using:

```text
peripheralManagerIsReady(toUpdateSubscribers:)
```

or equivalent CoreBluetooth callback.

Do not:

- busy-loop
- drop chunks
- repeatedly retry immediately
- block the main thread

This is required.

---

## Transfer Ordering

Artwork chunks must have deterministic ordering.

Android must reject:

- duplicate transfer starts
- wrong artworkId
- invalid chunk indexes
- duplicate chunks if unsafe
- missing chunks
- chunks beyond expected count
- payload beyond declared size

Out-of-order handling may either:

- support reordering safely
- or reject/reset the transfer

Choose the simplest robust strategy.

BLE notifications are normally ordered, so strict sequential acceptance is acceptable if documented.

---

## Artwork End

After all chunks are delivered, send an explicit completion signal if useful.

Example:

```json
{
  "type": "artwork_end",
  "artworkId": "abc123"
}
```

Android must validate:

- expected chunk count received
- exact expected total size
- artworkId matches
- decoded image is valid

Only then publish the artwork to the UI/cache.

---

## Checksum / Validation

Because `artworkId` may be derived from a content hash, use it to validate the completed image when practical.

A SHA-256-derived compact identifier is acceptable.

Do not introduce complex cryptographic protocol machinery.

Goal:

- corruption detection
- cache identity

not authentication.

---

## Android Artwork State

Keep artwork separate from dashboard layout persistence.

Suggested:

```text
MediaArtworkState
├── artworkId
├── status
├── bitmap?
└── transferProgress?
```

Statuses may include:

```text
NONE
REQUESTED
RECEIVING
READY
FAILED
```

Do not persist live transfer state.

---

## Android Artwork Cache

Add a small local cache.

Preferred first version:

- in-memory LRU cache

Optionally:

- lightweight disk cache if easy and safe

Do not introduce a database.

The cache key must be:

```text
artworkId
```

Memory must be bounded.

For example:

```text
max 8–16 artwork bitmaps
```

or a byte-budgeted LRU.

Avoid leaking Bitmaps.

---

## Artwork Request Behaviour

When MediaState changes to a track with `artworkId`:

1. check cache
2. if cached → display immediately
3. if missing → request once
4. show placeholder while receiving
5. render after successful validation

Do not repeatedly request artwork every 2-second media tick.

Track metadata messages should only trigger a new request when `artworkId` changes.

---

## Disconnect Behaviour

On BLE disconnect:

- cancel any in-progress artwork transfer
- release transfer buffers
- keep completed cache entries if desired
- do not display partially transferred artwork

When reconnecting:

- current track state arrives
- Android checks artwork cache
- requests artwork again only if missing

---

## Track Change During Transfer

If track changes while artwork is still transferring:

- cancel/ignore the old transfer
- begin/request the new artwork
- never render old artwork for the new track

Use artworkId matching everywhere.

---

## Media Widget UI

When artwork is available, update the Media widget.

Suggested large layout:

```text
┌────────────────────────────────┐
│          NOW PLAYING           │
│                                │
│  ┌──────────┐  Song Title      │
│  │ artwork  │  Artist          │
│  │          │  Album           │
│  └──────────┘                  │
│                                │
│  1:23 ━━━━━━━●━━━━━━ 3:34      │
│                                │
│       ⏮    ▶/⏸    ⏭          │
└────────────────────────────────┘
```

When artwork is missing:

- retain the existing polished placeholder
- do not collapse the whole widget awkwardly

For smaller Media widgets, artwork may be hidden if there is insufficient space.

---

## Responsive Artwork Rendering

Examples:

### Large MEDIA widget

Show:

- artwork
- title
- artist
- album
- progress
- controls

### Medium MEDIA widget

Show:

- artwork
- title
- artist
- controls

### Small MEDIA widget

Artwork may be omitted.

Do not make text unreadably small to force artwork into every layout.

---

## Main Dashboard

The main Dashboard currently uses compact media controls rather than the full Media widget.

Do not add artwork to the compact action group.

Artwork belongs to MEDIA widgets.

---

## Protocol Priority

Artwork transfer is lower priority than:

- command acknowledgements
- connection state
- live playback state

A large artwork transfer must not make normal buttons feel unresponsive.

If necessary, artwork chunk sending should yield between notifications and respect CoreBluetooth backpressure.

Do not create a second BLE connection.

---

## Security

Artwork protocol must not become a generic file-transfer API.

Never allow Android to request:

- arbitrary paths
- arbitrary filenames
- filesystem locations
- URLs

Only:

```text
artworkId
```

from the current known media artwork cache/provider.

No:

- HTTP
- TCP
- cloud
- remote image fetch
- arbitrary binary file transfer

---

## Performance

macOS:

- resize/compress once per new artwork
- cache processed artwork
- do not re-encode every 2 seconds
- do not block BLE command handling
- do not block main thread with image processing

Android:

- decode only completed validated artwork
- use appropriately sized Bitmap
- bounded cache
- no continuous artwork animation
- no repeated decode for same artworkId

---

## Testing — macOS

Add tests for:

- artwork resize target
- compression output
- deterministic artworkId
- same image → same artworkId
- changed image → changed artworkId
- artwork size ceiling
- chunk sizing from `maximumUpdateValueLength`
- exact chunk count
- final partial chunk
- send queue backpressure
- resume after `isReadyToUpdateSubscribers`
- transfer cancellation
- track change cancellation
- unknown artwork request rejection
- cached artwork response
- no repeated processing for same artwork
- no command/state protocol regression

Tests must use fake image data/provider objects.

Do not require Spotify or Apple Music.

---

## Testing — Android

Add tests for:

- artwork request only when missing
- cached artwork prevents request
- begin validation
- declared oversize rejected
- sequential chunk acceptance
- wrong artworkId rejected
- invalid chunk index rejected
- incomplete transfer rejected
- total-size mismatch rejected
- completion validation
- corrupt image decode fails safely
- disconnect clears active transfer
- track change cancels old transfer
- valid artwork enters cache
- cache lookup by artworkId
- cache eviction/bound
- Media widget state chooses artwork correctly

No physical BLE device required for unit tests.

---

## Build Verification

Android:

```bash
./gradlew clean assembleDebug testDebugUnitTest
```

macOS:

```bash
swift build
swift test
```

and:

```bash
xcodebuild -scheme AliControlMac -destination 'platform=macOS' build
xcodebuild -scheme AliControlMac -destination 'platform=macOS' test
```

Then rebuild:

```bash
Scripts/build_app.sh
```

All relevant builds/tests must pass.

---

## Manual Test Checklist

1. Rebuild and launch `AliControlMac.app`.
2. Install updated Android APK.
3. Connect tablet.
4. Open Spotify or Apple Music.
5. Play a track with artwork.
6. Verify metadata appears first.
7. Verify artwork appears shortly afterward.
8. Verify artwork corresponds to the current track.
9. Skip to next track.
10. Verify old artwork does not remain attached to new metadata.
11. Verify new artwork loads.
12. Go back to previous track.
13. Verify cached artwork appears faster / without unnecessary re-transfer if implemented.
14. Pause/resume.
15. Verify artwork stays visible.
16. Swipe away from Media page and return.
17. Verify artwork remains.
18. Resize Media widget.
19. Verify responsive artwork rendering.
20. Disconnect during an artwork transfer.
21. Verify no partial/corrupt image appears.
22. Reconnect.
23. Verify artwork can recover.
24. Rapidly skip several tracks.
25. Verify no crash/stale artwork.
26. Test normal volume/media/system action buttons during artwork transfer.
27. Verify commands remain responsive.
28. Leave one track playing for several minutes.
29. Verify artwork is not repeatedly re-transferred.
30. Verify AliControlMac idle resource usage remains reasonable.

---

## Documentation

Update README.md with:

- artwork architecture
- supported providers
- artwork processing size
- image format
- compression approach
- artworkId/cache logic
- artwork request protocol
- BLE chunk transport
- backpressure handling
- disconnect/track-change behavior
- Android cache
- Media widget artwork behavior
- known limitations

---

## Out of Scope

Do **not** implement:

- internet artwork lookup
- Spotify Web API
- Apple Music cloud API
- artwork URLs
- arbitrary file transfer
- generic BLE file transfer
- animated album artwork
- video
- lyrics
- visualizer
- weather
- calendar
- telemetry extensions
- fan controls
- themes
- dashboard modes
- Windows agent
- Claude/Codex integrations