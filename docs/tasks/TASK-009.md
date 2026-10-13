# TASK-009 — Live System & Media Data

> **Historical document.** Written when macOS was the reference platform; the wire protocol and behaviour described here are unchanged. On Windows, CPU/RAM/GPU come from native counters and media/artwork from the system media session instead of Mach APIs and AppleScript. See [docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md) §7 and §10.

## Goal

Bring Ali Control's existing placeholder dashboard widgets to life using real,
live data from the connected desktop.

This task introduces a desktop-to-tablet live state/data channel for:

- system telemetry
- Now Playing / media state

The existing command direction:

```text
Tablet
↓
BLE command
↓
macOS
```

will now be complemented by:

```text
macOS
↓
BLE state / telemetry updates
↓
Tablet
```

The implementation must remain:

- local
- BLE-only
- event-driven or low-frequency sampled where necessary
- lightweight
- safe
- compatible with the existing Android 8.1 tablet

Do not introduce cloud communication or LAN servers.

---

# Core Requirements

TASK-009 should provide real data for the existing dashboard widgets.

Required baseline:

- CPU usage
- RAM usage
- Now Playing title
- Now Playing artist
- playback state

Implement where reliably available:

- GPU usage
- album name
- media position
- media duration
- CPU/GPU temperature

If a metric cannot be obtained reliably on the current macOS/hardware without
unsafe/private/fragile techniques, leave that metric unavailable.

Never fabricate data.

---

# Architecture

Introduce clean provider abstractions.

Suggested macOS structure:

```text
TelemetryProvider
├── CPU
├── Memory
└── optional GPU / Temperature providers

MediaProvider
├── AppleMusicProvider
├── SpotifyProvider
└── optional future providers
```

These should produce platform-independent state models.

Suggested shared conceptual models:

```text
SystemMetrics
├── cpuUsage
├── memoryUsage
├── gpuUsage?
├── cpuTemperature?
├── gpuTemperature?
└── timestamp
```

```text
MediaState
├── source?
├── title?
├── artist?
├── album?
├── isPlaying
├── positionSeconds?
├── durationSeconds?
└── timestamp
```

Exact naming may differ if justified.

---

# System Telemetry

## CPU Usage

Implement real CPU utilization.

Requirements:

- normalized percentage
- range 0–100
- lightweight sampling
- no external process launched every second
- no shell polling loop

Prefer native macOS APIs.

The first sample may be unavailable if a delta is required.

---

## RAM Usage

Implement real memory usage.

Display as a meaningful percentage.

Prefer a definition such as:

```text
used / total
```

but document exactly how `used` is calculated.

Avoid presenting misleading memory pressure semantics as simple RAM usage.

---

## GPU Usage

Attempt GPU utilization only if it can be obtained reliably and safely on the
current Mac.

Important:

- do not scrape UI
- do not require root
- do not repeatedly invoke heavy CLI tools
- do not use arbitrary shell execution
- do not fabricate a GPU percentage

If unavailable:

```text
GPU
--
```

must remain.

Design the data model so Windows can supply GPU usage later.

---

## Temperatures

Temperature support is best-effort.

Do not use unstable/private system hacks merely to make a number appear.

If CPU/GPU temperature cannot be retrieved through a reasonably maintainable
local mechanism:

- keep the value unavailable
- document the limitation

Future Windows/hardware adapters may provide richer sensor data.

---

# Telemetry Sampling

System metrics naturally require sampling.

Use a bounded, low-frequency sampler.

Recommended default:

```text
1 update every 2 seconds
```

or slower if needed.

Requirements:

- no busy loop
- no high-frequency polling
- stop unnecessary work when no tablet is subscribed/connected if practical
- one sampler only
- avoid spawning repeated processes
- no work at display-frame frequency

Sampling frequency should be centralized/configurable.

---

# BLE Telemetry Transport

Reuse the existing BLE service and state-notification path where practical.

Do not change existing command UUIDs unnecessarily.

Do not break existing command acknowledgements.

Telemetry transport must:

- validate payload sizes
- never silently truncate UTF-8/JSON
- tolerate disconnect/reconnect
- resume cleanly after re-subscription
- not block command acknowledgements

If the existing state characteristic is reused, distinguish message types explicitly.

Example:

```json
{
  "type": "metrics",
  "cpu": 34.2,
  "memory": 47.8
}
```

Media example:

```json
{
  "type": "media",
  "title": "Song",
  "artist": "Artist",
  "playing": true
}
```

Exact JSON field naming may differ if a cleaner wire model already exists.

---

# BLE Payload Size Safety

Media metadata can exceed BLE payload limits.

Do not assume arbitrary strings fit inside one notification.

The implementation must choose a safe strategy.

Acceptable approaches include:

1. compact bounded metadata fields with explicit UTF-8 byte limits
2. multiple semantic state messages
3. a small generic application-level fragmentation/reassembly layer

Choose the simplest robust approach compatible with the current architecture.

Requirements:

- no silent string corruption
- no mid-UTF-8 truncation
- no invalid JSON
- no assumption about JSON key order
- malformed/incomplete payloads fail safely

Do not implement album-art transfer as part of this requirement.

---

# Media State

Implement real Now Playing information where practical.

Priority order:

1. currently active supported media source
2. Apple Music
3. Spotify

Do not pretend macOS exposes a universal public Now Playing API if it does not.

A provider-based implementation is preferred.

---

# Apple Music

If Apple Music is running and playing/paused, attempt to provide:

- title
- artist
- album
- playback state
- position
- duration

Use a maintainable macOS-supported mechanism.

If Automation permission is required:

- do not request it repeatedly
- fail gracefully
- keep system telemetry working
- clearly document the permission

---

# Spotify

If Spotify is installed/running, attempt to provide:

- title
- artist
- album
- playback state
- position
- duration where practical

Fail safely if Spotify is not installed or not running.

Do not require Spotify Web API/cloud authentication if local integration is sufficient.

---

# Media Provider Selection

The user should not need to manually choose Apple Music vs Spotify for the first version.

Implement deterministic selection.

For example:

- prefer a source that is currently playing
- otherwise preserve the most recently active supported source
- otherwise report media unavailable

Do not continuously fight between providers.

Document the selection policy.

---

# Media Playback State

The Android MEDIA widget should reflect:

- playing
- paused
- unavailable

The Play/Pause button remains an action from the existing Action Library.

Do not create a second media-control command system.

---

# Media Progress

If position and duration are available:

```text
1:23 ━━━━━━━●━━━━━━━━ 3:34
```

may be shown.

Do not update BLE dozens of times per second.

A progress update every 1–2 seconds is sufficient.

The Android side may locally interpolate progress between state updates if this
can be implemented cleanly.

Do not create a high-frequency animation.

---

# Album Artwork

Album artwork is **not required** for TASK-009.

Do not add fragile BLE binary transfer just to include artwork.

If metadata/state works cleanly first, artwork may be implemented in a future task.

The MEDIA widget should still look polished without artwork.

---

# Android Data Models

Create Android-side live state models separate from dashboard layout models.

Suggested:

```text
DesktopState
├── metrics
└── media
```

or equivalent.

Dashboard widgets should subscribe/render from this state.

Do not store live telemetry inside persisted dashboard JSON.

Layout state and live data state are separate concepts.

---

# State Store

Introduce an application-scoped live state holder.

Responsibilities:

- hold latest system metrics
- hold latest media state
- expose updates to visible dashboard widgets
- reset stale desktop data on disconnect
- survive page changes without duplicating subscriptions

Avoid:

- static global mutable maps
- one BLE listener per widget
- duplicated telemetry parsers

A single shared state source is preferred.

---

# Disconnection Behaviour

When the desktop disconnects:

System widgets should transition to:

```text
CPU --
RAM --
GPU --
```

Media should transition to:

```text
Media unavailable
```

Do not leave stale values indefinitely and pretend they are live.

The dashboard layout itself must remain fully usable/editable.

---

# Reconnection Behaviour

After BLE reconnect:

- state subscription resumes
- metrics begin updating again
- media state refreshes
- widgets recover without recreating the entire dashboard
- no stale previous-device state should survive incorrectly

---

# Metric Widget Rendering

Update existing METRIC widgets.

Examples:

```text
CPU
34%
```

```text
RAM
48%
```

```text
GPU
--
```

Formatting:

- no excessive decimal precision
- prefer whole percentages for compact widgets
- unavailable = `--`
- never show NaN/Infinity

Larger widget sizes may show extra context where appropriate.

---

# Gauge Widget Rendering

GAUGE widgets may now use real metrics where available.

Keep rendering lightweight.

Avoid:

- continuously spinning animation
- expensive custom drawing
- chart libraries

A simple progress ring/bar is acceptable.

Do not animate at high frequency.

---

# Media Widget Rendering

Replace:

```text
Media unavailable
```

with real metadata when available.

Suggested layout:

```text
NOW PLAYING

Song Title
Artist

1:23 ━━━━━━━●━━━━ 3:34

⏮    ▶/⏸    ⏭
```

When metadata is unavailable:

```text
NOW PLAYING

Media unavailable

⏮    ▶/⏸    ⏭
```

Existing controls must continue using ActionLibrary.

---

# Dashboard Page Behaviour

Update all pages/widgets that currently show placeholder system/media data.

Examples:

- Dashboard CPU
- Dashboard GPU
- Dashboard RAM
- Media page
- Dev system metrics

One shared live state must drive all copies.

Do not independently sample/fetch per page.

---

# Data Freshness

Track when live state was last updated.

If data becomes unexpectedly stale while BLE still appears connected:

- do not keep presenting very old metrics as current forever
- optionally mark data unavailable after a sensible timeout

Keep this logic lightweight.

---

# Protocol Compatibility

Preserve all existing command messages:

```json
{"type":"command","action":"volume_up"}
```

and acknowledgements:

```json
{"type":"state","action":"volume_up","status":"ok"}
```

TASK-009 may add new state message types but must not break old ones.

Existing Android/macOS protocol fixture tests must continue passing.

---

# macOS Permissions

Keep permissions minimal.

Possible permissions may include:

- Bluetooth
- Accessibility for existing keyboard-action features
- Automation only if a supported local media provider genuinely requires it

Do not add:

- Screen Recording
- Full Disk Access
- root privileges

unless there is an explicit technically unavoidable reason, which should not be
introduced in this task without review.

---

# Performance

macOS helper must remain lightweight while idle and connected.

Review:

- CPU usage
- memory usage
- sampling frequency
- process creation
- AppleScript frequency
- BLE notification rate

Do not execute AppleScript every frame or many times per second.

If AppleScript is used for media metadata, use a sensible low-frequency refresh.

Target behavior:

```text
event-driven where possible
low-frequency sampling where necessary
```

Android:

- no high-frequency UI redraw
- no one-timer-per-widget
- no polling from each widget
- update only affected widgets/state

---

# Security

Do not change the existing security model.

No:

- HTTP
- TCP server
- cloud communication
- arbitrary shell command
- arbitrary script received from tablet
- remote executable paths

All desktop behavior remains locally defined.

---

# Testing

## macOS

Add tests for:

- CPU sample normalization
- memory usage calculation
- unavailable GPU state
- telemetry snapshot serialization
- media-state serialization
- provider selection logic
- Spotify unavailable behavior
- Apple Music unavailable behavior
- long UTF-8 metadata payload handling
- oversized BLE state protection
- no command protocol regressions
- reconnect/subscription telemetry lifecycle

Use fakes/mocks.

Automated tests must not require Spotify/Music to be running.

Do not manipulate real media playback during tests.

---

## Android

Add tests for:

- telemetry message parsing
- media message parsing
- malformed message rejection
- payload reconstruction if fragmentation is introduced
- DesktopState updates
- disconnect clears stale state
- reconnect accepts fresh state
- metric formatting
- unavailable-state formatting
- media progress formatting
- dashboard widgets bind to shared state
- persisted dashboard layout remains independent of live telemetry state

---

# Build Verification

Android:

```bash
./gradlew clean assembleDebug testDebugUnitTest
```

macOS:

```bash
swift build
swift test
```

Also run where applicable:

```bash
xcodebuild -scheme AliControlMac -destination 'platform=macOS' build
xcodebuild -scheme AliControlMac -destination 'platform=macOS' test
```

Rebuild:

```bash
Scripts/build_app.sh
```

All relevant tests must pass.

---

# Manual Test Checklist

After implementation:

1. Rebuild `AliControlMac.app`.
2. Install updated Android APK.
3. Launch Mac helper normally.
4. Connect tablet.
5. Verify CPU widget changes from `--` to a real value.
6. Verify RAM widget shows a real value.
7. Create CPU load on Mac and verify value increases.
8. Verify GPU remains `--` if reliable GPU data is unavailable.
9. Verify no fake temperature appears.
10. Open Spotify or Apple Music.
11. Start a track.
12. Verify title appears.
13. Verify artist appears.
14. Verify playback state is correct.
15. Pause/resume from tablet.
16. Verify state updates.
17. Test Previous/Next.
18. Verify metadata changes to the new track.
19. Verify position/duration if implemented.
20. Verify Media page and Dashboard use the same state.
21. Disconnect BLE.
22. Verify metrics become unavailable.
23. Verify Media becomes unavailable.
24. Reconnect.
25. Verify live state recovers.
26. Swipe between dashboard pages repeatedly.
27. Verify no duplicate sampling/subscriptions appear.
28. Leave Mac/tablet idle for several minutes.
29. Verify CPU usage of AliControlMac remains low.
30. Verify no excessive logging.
31. Verify dashboard editing/custom layouts from TASK-008 still work.
32. Verify all previous remote actions still work.

---

# Documentation

Update README.md with:

- telemetry architecture
- telemetry refresh rate
- metric definitions
- supported/unavailable metrics
- media provider architecture
- supported media applications
- permissions
- BLE live-state protocol
- payload-size strategy
- disconnect/reconnect behavior
- known Apple Silicon/macOS limitations
- album-art deferral

---

# Out of Scope

Do **not** implement:

- album-art BLE transfer unless trivially safe and already supported by the chosen design
- fan RPM
- fan control
- hardware profiles
- weather
- calendar
- Microsoft Graph
- themes
- modes/profiles
- Claude Code
- Codex
- Git status
- Windows agent
- HTTP/TCP server
- cloud services
- arbitrary remote execution