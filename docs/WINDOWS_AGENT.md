# Windows agent

The native Windows 11 tray application that acts as the BLE peripheral the tablet connects to.
See the [README](../README.md) for setup and [ARCHITECTURE.md](ARCHITECTURE.md) for the protocol.

## Requirements

- **Windows 11, x64** (Windows 10 2004+ should work: the project targets 10.0.19041).
- A Bluetooth adapter with **LE peripheral role** support (most Bluetooth 4.0+ adapters;
  `AliControl.exe --diagnose` checks).
- To build: **.NET SDK 10** (`global.json` pins it). To run the published build: nothing —
  it is self-contained.
- Runs as a **standard user**. It never asks for elevation.

## Run it

Build (below), then double-click **`windows/dist/AliControl/AliControl.exe`**. No console
window, no installer, no IDE. A tray icon appears in the notification area (it may be
under the `^` overflow arrow the first time):

```
Ali Control
Bluetooth: On
Tablet: Not connected | Connected (AA:BB:CC:DD:EE:FF)
Advertising: Yes | Yes (compact discovery) | Starting… | No | Failed — try Restart Advertising
────────────────
✓ Open at Startup
  Restart Advertising
────────────────
  Quit
```

Icon: grey ring = Bluetooth off · half-blue = advertising, waiting for the tablet · solid
green = a tablet is connected · amber = needs attention. The status lines are rewritten
when something actually changes; **Open at Startup** is re-read each time the menu opens.
(A peripheral cannot see a connecting central's friendly name, so the tablet is shown by
its Bluetooth address.) **Restart Advertising** is a manual recovery for the rare stuck
case; it is ignored while a tablet is connected (rebuilding the service would drop it).

Command line (all optional):

| Command | Effect |
|---|---|
| `AliControl.exe --quit` | asks the running agent to stop cleanly (same as Quit) |
| `AliControl.exe --diagnose` | writes `%LOCALAPPDATA%\AliControl\diagnostics.txt` and shows it: adapter/peripheral support, probed capabilities, a real advertising test per UUID candidate. Refuses to run while the agent is running. `--no-dialog` skips the dialog. |

## Open at Startup

Exactly one value is written, only when you tick the menu item, and removed when you untick
it:

```
HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
    AliControl = "C:\path\to\AliControl.exe"
```

That is the standard per-user mechanism Windows itself shows under **Settings → Apps →
Startup** and in Task Manager's **Startup apps** tab. No admin rights, no service, no
scheduled task, no hidden file. If you switched the entry off in Task Manager the tray shows
it unticked, and ticking it again clears that flag. It only registers the real
`AliControl.exe` (never `dotnet.exe` or a test host), and the tests never touch the real
registry.

## Live telemetry

One sampler runs **every 2 seconds, only while a tablet is subscribed** (an unattended PC
does no telemetry work at all: no timer, no counters, no media reads). The interval is one
constant (`LiveStatePublisher.DefaultInterval`); there is never a per-widget timer.

| Metric | Source | Definition |
|---|---|---|
| **CPU** | `GetSystemTimes` (one native call) | busy share of the CPU time elapsed since the previous sample (`kernel − idle + user`), normalised across all logical processors. The first sample after a (re)start has no baseline and reports nothing |
| **RAM** | `GlobalMemoryStatusEx` | `(total physical − available physical) / total`. *Available* includes the standby cache, so this equals Task Manager's "In use" and file cache does not pin a healthy machine near 100 %. A usage figure, not commit charge or memory pressure |
| **GPU** | PDH `\GPU Engine(*)\Utilization Percentage` | per-engine sum across processes → busiest engine per GPU → busiest GPU (a laptop's idle iGPU never hides a loaded dGPU). Vendor-neutral, no admin, no driver; ≈ 0–8 ms per sample; the counter is opened on subscribe and closed on unsubscribe |
| **Temperatures** | — | **not reported** |

**Why temperatures are not reported.** Windows has no public, user-mode, vendor-neutral
temperature API. ACPI "Thermal Zone Information" counters are absent on many PCs (the
development PC exposes none) and are not CPU-package temperatures where present; WMI
`MSAcpi_ThermalZoneTemperature` needs administrator rights and has the same problem;
LibreHardwareMonitor-style libraries need elevation and a kernel driver; vendor SDKs (NVML,
ADL, igcl) cover one vendor each. All are excluded by the project rules (no admin, no
drivers, no fragile vendor hacks), so the `telemetry.temperature.*` capabilities are never
announced and the tablet shows `--`. Vendor-specific adapters (NVML and similar) would be a possible future addition.

## Now Playing

The agent reads **Windows' own system media sessions**
(`GlobalSystemMediaTransportControlsSessionManager`) — the service the lock screen and volume
flyout use — so it is generic: Spotify, Windows Media Player, browsers and any app that
publishes to the Windows media transport controls. **No Spotify Web API, no cloud
authentication, no provider scripting; read-only.** It exposes source app, title, artist,
album, playing/paused, position, duration and artwork where the app provides them.

**Selection policy** (evaluated each sample, no user choice): a playing session wins; with
several playing, the previously chosen one keeps the widget (no flip-flopping) and Windows'
own current session breaks ties; pausing never hands the widget to another idle player;
nothing playable → the tablet shows "Media unavailable".

**Spotify:** handled through the same generic path. On the development PC the agent could read
title, artist, album, state, position, duration and artwork from the real Windows Spotify app,
**but in the first test with a physical tablet that media state did not reach the tablet
correctly. This is an open issue** — the desktop-to-tablet delivery of Spotify now-playing data
is not yet confirmed to work end to end (see *Known issues* in the [README](../README.md)).
`open_spotify` launches Spotify through its registered `spotify:` URI (desktop and Microsoft
Store editions); on a PC without Spotify it fails cleanly. Apps that don't publish a
thumbnail simply show the placeholder.

## Permissions & privilege

| Need | Why | How |
|---|---|---|
| Bluetooth | advertise, accept the tablet | no prompt for an unpackaged desktop app; Windows' "Bluetooth" must be on |
| Keyboard input | 8 actions press real Windows shortcuts | `SendInput` as the signed-in user; blocked by Windows toward elevated windows |
| Registry write | only **Open at Startup**: the per-user `Run` value, and clearing a "disabled" flag under `StartupApproved` if you had switched the entry off | HKCU only, on user action |

No administrator rights, no UAC prompt, no service, no kernel driver, no network access, no
screen recording, no input hooks. `app.manifest` declares `asInvoker`.

## Logging

`%LOCALAPPDATA%\AliControl\agent.log` (256 KiB cap, one rotated file): event-driven lines
only — Bluetooth state, advertising mode, connect/disconnect, unparseable payload sizes,
command failures (action id only). Nothing on a timer; never payload content, track
metadata or the PC name. The connect line does include the connecting device's Bluetooth
address (the same label the tray shows). The log never leaves the PC.
