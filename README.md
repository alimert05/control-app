# Control App

Turn a spare Android tablet into a dedicated, always-on control panel for your Windows PC — media
controls, shortcuts, live system stats and now-playing info over Bluetooth Low Energy.

**Local-only:** the tablet and the PC talk directly over Bluetooth LE — no Wi-Fi, internet, cloud
service, account or open network port is needed. MIT licensed. In v0.1.0 the BLE link is **not authenticated or
encrypted** by the project; stronger security is planned (see [Security and privacy](#security-and-privacy)).

> ### 🚧 Active Development
>
> The current MVP is available as **v0.1.0 — Initial MVP Release**. It works, but the project is
> under active development: more customization and integrations are planned, and it is **not
> presented as production-ready software**. See [Known issues](#known-issues--limitations) before
> relying on it.

## Overview

Control App repurposes a tablet as a persistent desktop control surface. Instead of sitting in a
drawer, the tablet runs fullscreen next to your monitor and shows a customizable dashboard of
buttons and live widgets for the PC it is connected to.

It has exactly two parts that talk to each other over BLE:

- **Android tablet client** (`android/`) — the touch UI: a paged dashboard of action buttons and
  live widgets, plus an on-tablet editor. Kotlin, XML Views, no Compose.
- **Windows desktop agent** (`windows/`) — a tray application (C# / .NET 10) with a small idle footprint (see [Performance](#performance)) that
  advertises over Bluetooth, executes a fixed set of allowlisted actions and publishes system
  and media state. No Electron, Node.js, Python, local web server or cloud service is involved.

The long-term goal is a polished, customizable "second screen" for a desk setup — see the
[roadmap](#roadmap).

The apps are named **Ali Control** on the device and in the system tray. The project was developed
and primarily tested on an **Alcatel 1T 7 (8068)** tablet running **Android 8.1**, but nothing in the code is
specific to that device (see [Requirements](#requirements)).

## Features

Everything below is implemented in the code in this repository.

**Tablet**

- **Fullscreen, kiosk-style display** — immersive mode on every screen, optional keep-screen-awake,
  and automatic launch after the tablet boots. This is application-level behavior; it does not
  block the Home or Recents buttons.
- **Customizable dashboard** — multiple swipeable pages on a 6×4 grid. Widgets: single actions,
  action groups (e.g. media transport), CPU / RAM / GPU readouts and gauges, clock, daily
  overview, a now-playing widget and shortcuts. An on-tablet **Edit Mode** lets you drag, resize,
  add and remove widgets and create, rename, reorder and delete pages. Layouts are stored locally.
- **Dashboard profiles** — four built-in profiles (**Default**, **Dev**, **Game**, **Media**) plus
  your own. Each profile has its own independent dashboard; switch from the header, and the last
  profile is restored on launch. You can create, duplicate, rename, reorder and reset profiles.
- **27 predefined PC actions** — media (play/pause, next/previous, volume, mute), system (lock,
  sleep with confirmation, display sleep, screenshot, show desktop, Windows Search, Task View),
  apps (Terminal, VS Code, File Explorer, browser, Spotify, Settings, Task Manager), window
  controls (minimize, maximize/restore, close) and copy / paste / undo / redo.
- **Live PC data** — CPU, RAM and GPU usage, plus now-playing title, artist, album, playback state,
  progress and album artwork. A value the PC cannot measure is shown as `--`, never as a made-up
  number. (See the Spotify note under [Known issues](#known-issues--limitations).)
- **Connection handling** — BLE scan and connect screen, connection status in the header, the PC's
  name once connected, and bounded automatic reconnect after an unexpected drop.
- **Device screen** — brightness, tablet volume, battery, keep-awake, and shortcuts to the
  Android Wi-Fi and Bluetooth settings.

**Windows agent**

- **Tray app** with status icon and menu: connection and advertising status, **Open at Startup**
  (a per-user entry, written only when you tick it), **Restart Advertising** and **Quit**.
- **BLE peripheral** built on the Windows GATT server APIs, with a fallback discovery UUID for
  adapters that refuse to advertise the full one.
- **Capability announcement** — the agent tells the tablet which features this PC supports
  (for example GPU telemetry). Widgets the PC cannot serve are dimmed, never deleted from your layout.
- **Telemetry and media** — CPU, RAM and GPU sampling while a tablet is connected, and now-playing
  data from Windows' own media sessions (any app that publishes to them, not a Spotify-specific API).
- **Diagnostics** — `AliControl.exe --diagnose` writes a Bluetooth/setup report.
- **Least privilege** — runs as a standard user, never asks for elevation, opens no network port.

## Architecture

```
┌────────────────────────────┐        BLE        ┌────────────────────────────────┐
│ Android tablet             │◀─────────────────▶│ Windows PC                     │
│ BLE central + dashboard UI │  JSON messages    │ Tray agent, BLE peripheral     │
│                            │  (commands down,  │                                │
│ • dashboard / profiles     │   state up)       │ • allowlisted action registry  │
│ • one shared live-state    │                   │ • CPU / RAM / GPU telemetry    │
│   store for all widgets    │                   │ • Windows media sessions       │
└────────────────────────────┘                   └───────────────┬────────────────┘
                                                                 ▼
                                                  Windows APIs: SendInput, media keys,
                                                  media sessions, performance counters
```

The tablet is the BLE **central**; the PC is the BLE **peripheral**. The tablet writes small JSON
commands (`{"type":"command","action":"volume_up"}`) containing a predefined action ID, and the PC
answers with an acknowledgement and streams state notifications (metrics, playback, track
metadata, artwork chunks). Both sides implement the protocol independently with shared test
fixtures to catch drift. The tablet never sends shell text, paths or URLs, and the agent only
executes IDs from its own allowlist. Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Repository structure

```
android/                          Android tablet app (package com.alicontrol.app)
  app/src/main/java/…/
    ui/                             screens and dashboard renderers
    dashboard/                      grid / page / widget model, editor, persistence
      profile/                      dashboard profiles
    live/                           shared live PC state, artwork cache
    actions/  ble/  transport/      action library, protocol messages, BLE client
    kiosk/  device/                 fullscreen, boot launch, tablet settings
  app/src/test/                     JVM unit tests
windows/                          Windows desktop agent (C# / .NET 10)
  AliControlWindows/                agent source: App, BLE, Protocol, Actions, Telemetry, Media, Artwork
  AliControlWindows.Tests/          xUnit tests (all hardware sits behind fakes)
  scripts/build.ps1                 restore → build → test → publish
docs/                             architecture, Android client, Windows agent, manual test checklist
```

## Requirements

**Tablet**

- Android **8.1 (API 27)** or newer with Bluetooth Low Energy. The app is landscape-only.
- Real-device testing has primarily been performed using an Alcatel 1T 7 (8068) running Android 8.1;
  other devices should work in principle but have not been tested broadly.
- Permissions: Bluetooth, location (required by Android for BLE scanning), boot-completed and an
  optional brightness permission — explained in [Permissions and privileges](#permissions-and-privileges).
  The app does **not** request Internet access.

**PC**

- **Windows 11 (x64).** Windows 10 version 2004+ may work (the project targets Windows SDK
  10.0.19041) but has not been tested.
- A Bluetooth adapter that supports the **LE peripheral role** (`AliControl.exe --diagnose` checks).
- **macOS is not supported in v0.1.0.** An early macOS agent existed during development and was removed; renewed macOS support is planned for a future release.

**To build from source**

- Android app: **JDK 17**, the Android SDK (platform 33). Gradle 8.4 (wrapper), Android Gradle
  Plugin 8.1.4 and Kotlin 1.9.22 are fetched by the build. Runtime libraries: AndroidX core,
  AppCompat, Material Components, ViewPager2.
- Windows agent: **.NET SDK 10** (`windows/global.json`). The agent has no NuGet dependencies.

There are no prebuilt binaries yet; build both parts from source as below.

## Installation / setup

### Tablet

Enable USB debugging on the tablet, then, with `JAVA_HOME` pointing at JDK 17 and the Android SDK
available (set `ANDROID_HOME`, or create an untracked `android/local.properties` containing
`sdk.dir=<path to the SDK>`):

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On macOS/Linux use `./gradlew` instead of `.\gradlew.bat`. Launch **Ali Control** on the tablet.

### Windows

```powershell
cd windows
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
.\dist\AliControl\AliControl.exe
```

The script restores, builds, runs the tests and publishes a self-contained single-file build to
`windows\dist\AliControl\AliControl.exe` (about 135 MB; no .NET runtime needed on the PC). Pass
`-SkipTests` to skip the tests. The agent starts in the notification area (it may be under the `^`
overflow arrow). Make sure Windows Bluetooth is on.

### macOS

macOS support is not available in v0.1.0. Support for macOS is planned for a future release.

## Usage

1. Start `AliControl.exe` on the PC. The tray menu should show **Advertising: Yes**.
2. Open **Ali Control** on the tablet, tap **Connect**, grant the location prompt on first run, and
   pick the PC. The header shows **Connected** and the PC's name.
3. Tap widgets to send actions; swipe between pages. **Edit** switches to Edit Mode (drag to move,
   corner handle to resize, **Add** / **Pages** in the header). The `[ Default ▾ ]` button switches
   or manages [profiles](docs/ANDROID_CLIENT.md#dashboard-profiles). **All Actions** lists every action the PC
   supports and **Device** opens the tablet settings.
4. Optional: tick **Open at Startup** in the tray menu so the agent launches when you sign in.

Other agent commands: `AliControl.exe --diagnose` (setup report) and `AliControl.exe --quit`
(stop the running agent). A manual verification checklist is in
[docs/MANUAL_TESTING.md](docs/MANUAL_TESTING.md).

## Security and privacy

### Local-only communication

Control App communicates directly between the tablet and PC over Bluetooth Low Energy. It does not
require Wi-Fi, internet access, cloud services, accounts, or an open network port.

This is checked against the code, not just intended:

- The Android app declares no `INTERNET`, network-state or Wi-Fi permission, so Android would not let
  it open network connections. It contains no HTTP, socket or cloud code and no networking or
  analytics libraries.
- The Windows agent contains no HTTP client, socket, listener or URL, and has no NuGet
  dependencies; an automated test fails the build if network code, a listener or a URL is added.
- Only *building from source* needs the internet (Gradle and NuGet download the toolchain and
  test libraries). Running the apps does not.
- Some actions start other programs on the PC (your browser, Spotify). Those programs may use the
  network on their own; Control App sends nothing through them. The Device screen's Wi-Fi and
  Bluetooth entries merely open Android's own settings screens.

### Existing safeguards

- **Fixed action allowlist.** The tablet sends predefined action IDs; the agent maps each to a fixed
  implementation and rejects anything else. Inbound messages are strictly parsed and capped at 512 bytes.
- **No arbitrary execution.** No shell command, path, URL or script ever crosses the link. The agent
  never starts a process with arguments or through `cmd`/PowerShell command strings (the Terminal
  action opens a fixed console, with no arguments), and an automated test enforces this.
- **No network listener** and no outgoing network access in the agent.
- **Standard user.** The agent runs without administrator rights, never requests elevation and
  installs no service, scheduled task or driver.
- **No shutdown, restart, sign-out or file actions.** The most disruptive actions are Lock, Sleep and
  Display Sleep.
- **Elevated windows are protected by Windows.** Windows blocks injected keystrokes toward windows
  running as administrator, so those actions fail with an error.
- **One active client.** The agent only acts on commands from the client that is currently subscribed.

### Current limitations

- **No pairing or authentication.** The agent's BLE characteristics are created with the plain (no
  encryption or pairing required) protection level, and the Android app never bonds with the PC.
- **No application-level encryption.** Messages are plain JSON. Do not assume the link is encrypted.
- **A nearby BLE device may therefore be able to:** connect to the running agent; invoke any allowed
  action (including lock, sleep and keyboard shortcuts); read live data (the PC's name, CPU / RAM /
  GPU usage and now-playing title, artist, album and artwork); and replace the tablet's connection,
  because the newest connection wins.
- **Sleep confirmation is tablet-side only.** The tablet asks before sending Sleep, but the agent does
  not enforce that.
- Only run the agent in an environment where this risk is acceptable, and quit it from the tray when
  you are not using it (Quit stops advertising).
- Additional security layers, including authenticated pairing and stronger protection of the BLE link, are planned for future releases.

### Privacy

Control App does not include analytics, telemetry uploads, cloud logging, crash reporting or
account-based tracking. The CPU, RAM and GPU readings and now-playing details the PC sends are *system
state delivered locally to your own tablet over BLE* — not data collection — and nothing is uploaded.

Data stays on your devices: the tablet keeps your layouts and settings in app-private storage
(Android backup is disabled), and the agent writes `%LOCALAPPDATA%\AliControl\agent.log` (size-capped
event lines, including the connecting device's Bluetooth address but no track metadata or PC name),
`diagnostics.txt` only when you run `--diagnose`, and an optional per-user startup entry.

### Permissions and privileges

| Where | Permission / privilege | Why |
|---|---|---|
| Tablet | `BLUETOOTH`, `BLUETOOTH_ADMIN` | scan for, connect to and disconnect from the PC (the legacy permissions, granted at install on this API level) |
| Tablet | `ACCESS_COARSE_LOCATION` | Android requires it for BLE scan results at this API level; requested at runtime. The app never reads the tablet's location |
| Tablet | `RECEIVE_BOOT_COMPLETED` | only to start the dashboard after the tablet boots; the receiver reacts to nothing else and no background service runs |
| Tablet | `WRITE_SETTINGS` | optional; only for the brightness slider on the Device screen, granted by you on a system screen (the slider is disabled without it) |
| Tablet | *not requested* | Internet / network state / Wi-Fi, storage, camera, microphone, contacts, Accessibility, Device Admin |
| PC | standard user | no UAC prompt, no administrator rights, no service or driver |
| PC | keyboard input | eight actions send fixed key combinations with `SendInput` as the signed-in user; Windows blocks this toward elevated windows |
| PC | registry (current user only) | only the **Open at Startup** entry, written when you tick it and removed when you untick it |

## Performance

These figures describe the **Windows agent** and were measured on **one machine**; other hardware, Bluetooth
adapters and workloads will give different numbers. They are a snapshot of v0.1.0, not a guarantee.

### Tested on

- **OS:** Windows 11 Pro (build 26200)
- **CPU:** AMD Ryzen 7 9800X3D (8 logical processors as reported by Windows)
- **RAM:** 64 GB (2 × 32 GB)
- **Release:** Control App v0.1.0 — Windows agent, Release build, self-contained single file
- **Conditions:** normal desktop use (other applications kept the whole CPU at roughly 10–17% busy), Windows
  "Power saver" power plan, no tablet attached

### Real agent process: startup and idle

Measured on the published v0.1.0 `AliControl.exe`: one discarded warm-up launch, then 5 launches, each
sampled for 60 seconds starting 15 seconds after the agent was ready. Ranges are min–max across the 5 launches.

| Scenario | Memory | CPU |
| --- | --- | --- |
| Startup (advertising, about 2.3–2.4 s after launch) | working set 77 MB · private working set 16 MB | — |
| Idle, about 1 minute after launch | working set 75–76 MB · private working set 14 MB · private bytes 19 MB | below measurement resolution: 0 in 4 of 5 launches, one 16 ms tick (0.03% of one core) in the other |
| Connected to a tablet | **not measured** (no tablet attached) | **not measured** |

Also at idle: 16–18 threads and about 450–465 handles. The process started running its own code about
0.12–0.15 s after launch; on this PC about 1.6 s of the time to "advertising" is Windows reporting that the adapter
rejected the full-length service UUID before the agent falls back to the compact one (see
[ARCHITECTURE.md](docs/ARCHITECTURE.md)). Windows accounts CPU time in roughly 16 ms steps, so an idle process shows as 0.

On this machine the agent was light at idle: no measurable CPU and about 14 MB of private memory (the figure Task
Manager shows as *Memory*). The larger working set includes pages of the 135 MiB self-contained executable that
Windows maps into the process; the **executable size on disk is a separate number** and not what the agent holds in RAM.

### Telemetry loop: isolated pipeline benchmark

While a tablet is subscribed, the agent runs a 2-second loop (CPU / RAM / GPU sampling, a read of the Windows media
sessions, artwork handling, message encoding). To measure it without a tablet, the agent's own classes and real
providers were run in a **separate test process for 12 minutes, without sending anything over Bluetooth**. This is an
isolated benchmark of that loop — **not a measurement of a connected agent**, and its numbers should not be read as
connected CPU or memory. Media playback was not controlled; it started and stopped on its own during the runs.

| Measurement | Result |
| --- | --- |
| CPU, steady state (minutes 4–12) | about 0.09% of one logical core (range 0.03–0.13%); 0.3–0.6% in the first three minutes while .NET's JIT warms up |
| Messages | 30–59 per minute, about 72 bytes each |
| Allocation | about 168 KB/s, of which about 95% was GPU sampling (with GPU sampling disabled: about 9 KB/s) |
| Managed heap between collections | grows about 10 MB per minute, then drops at each garbage collection (which ran at roughly 45–50 MB) |
| Live data after garbage collection | 0.7–0.8 MB after the automatic collections, 0.4 MB after a forced full collection |

The test process's private bytes oscillated between roughly 68 and 86 MB (working set 107–126 MB) from the first
collection onward, against 17 MB (54 MB working set) just after setup. The post-collection low points were flat
(68.6 MB, then 68.0 MB) and the live managed heap stayed under 1 MB, so this looks like garbage awaiting
collection plus memory the .NET runtime keeps committed, with **no sign of a leak in this 12-minute run** (a multi-hour
run was not done, and what makes up the private bytes that remain after a collection was not broken down).

### What this does and does not show

- The agent's **idle** footprint on this machine is small: effectively no CPU and about 14 MB private memory.
- The loop's CPU cost after warm-up is small, but it allocates far more than its ~72-byte output needs, so a
  *connected* agent should be expected to use more memory than the idle figures above. How much was **not measured**.
- Connected-tablet CPU and memory still need a real measurement with a tablet attached; no value is given here.
- Performance work is planned for future releases as the project grows (more live widgets, broader device support):
  starting with the allocations in GPU sampling, and a real connected-tablet measurement.

## Known issues & limitations

- **Windows Spotify now-playing is not working end to end yet.** The agent can read Spotify's title,
  artist, album, state, position and artwork through Windows media sessions on the PC, but in the
  first test with a physical tablet that media state did not reach the tablet correctly. This is an
  open issue; the media widget should not be relied on for Spotify until it is resolved.
- **Device coverage.** Real-device testing has primarily been performed using an Alcatel 1T 7 (8068).
  Other tablets and PCs have not been tested broadly; the Android client and the Windows agent are
  also covered by automated tests on each side.
- **No BLE pairing, authentication or encryption (v0.1.0).** A nearby BLE device can connect to a running
  agent. See [Security and privacy](#security-and-privacy) for exactly what that allows and what is planned.
- **Windows only.** macOS support was removed; Windows 11 (x64) is the only supported desktop.
- **Elevated windows cannot be controlled.** Windows blocks injected keystrokes toward windows
  running as administrator, so those actions fail with an error. Display Sleep is best-effort on
  some PCs, and Screenshot needs an unlocked session.
- **No temperature readings.** Windows has no reliable, vendor-neutral user-mode temperature source,
  so none are shown. GPU usage appears only if the PC exposes the Windows GPU performance counters.
- **Bluetooth adapters vary.** The agent needs LE peripheral support; if an adapter is plugged in
  after the agent started, restart the agent.
- **Kiosk mode is app-level.** It cannot block Home/Recents (that needs device-owner setup), boot
  launch depends on the tablet vendor's autostart settings, and brightness control needs a manual
  `WRITE_SETTINGS` grant.
- **Dashboard placeholders.** The Daily Overview widget shows local time and date only; its weather
  and calendar lines are explicit "unavailable" placeholders.
- **Packaging.** Builds are unsigned and there is no installer.

## Roadmap

All items below are **planned and not implemented**.

- Fix end-to-end Windows media (Spotify) delivery and improve media metadata support
- Efficiency work on the agent's live-state loop (mainly GPU-sampling allocations), plus a connected-tablet performance measurement
- Themes and visual customization
- More system controls
- System performance monitoring beyond CPU / RAM / GPU, and fan / hardware controls where supported
- Developer-workflow widgets (for example Git and build/test status)
- Weather and calendar widgets
- Authenticated pairing and stronger protection of the BLE link
- Installer and code signing
- Broader device support

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — components, BLE profile, protocol, action library, security model
- [docs/ANDROID_CLIENT.md](docs/ANDROID_CLIENT.md) — dashboard, Edit Mode, profiles, persistence, kiosk, permissions
- [docs/WINDOWS_AGENT.md](docs/WINDOWS_AGENT.md) — tray app, telemetry, media, startup, logging
- [docs/MANUAL_TESTING.md](docs/MANUAL_TESTING.md) — end-to-end checklist for a tablet and a PC
- [windows/README.md](windows/README.md) — quick reference for building the agent

## Development

The project is under active development, and the code and the protocol may change between
versions. At v0.1.0 the automated suites are:

```powershell
cd android
.\gradlew.bat clean assembleDebug testDebugUnitTest   # debug APK + 501 JVM unit tests

cd ..\windows
dotnet test AliControl.slnx                           # 468 tests; none touch the real PC
```

`.\gradlew.bat assembleRelease` produces an unsigned release APK (no signing configuration is
committed). The Android tests cover the dashboard model and editor, persistence and migration,
profiles, protocol parsing and live-state handling; the Windows tests use fakes for every real
system call (keyboard input, locking, Bluetooth, media), so running them never changes your PC.

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE).
