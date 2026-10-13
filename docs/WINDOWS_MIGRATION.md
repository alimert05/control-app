# Ali Control — macOS → Windows migration

Ali Control used to be an Android tablet client plus a **macOS** menu-bar agent.
Windows 11 (x64) is now the **only** supported desktop platform. This document is
the record of how that migration was done, what was preserved, what changed on the
wire, and the decisions behind it. The product description is in the
[README](../README.md); the historical task documents under [`docs/tasks/`](tasks/)
describe the *intent* of each feature and are kept as architectural history (their
macOS-specific implementation notes are superseded by this document).

## 1. What stayed the same

| Area | Status |
|---|---|
| Android app architecture (kiosk, boot launch, Device Settings, 6×4 dashboard, pages, widgets, edit mode, persistence) | unchanged |
| BLE roles: tablet = central, desktop = peripheral; no LAN, no cloud, no server | unchanged |
| Service + characteristic UUIDs | **unchanged** (`7eb4052b-…`, `6544209f-…`, `71dbc91d-…`) |
| Message envelope (`command` / `state` JSON), ACK semantics, key-order independence | unchanged |
| Live telemetry / media / track messages and their size budgets | unchanged on the wire |
| Artwork protocol (request → begin → binary chunks → end), `artworkId`, caches, priority + cancellation rules | unchanged on the wire |
| Predefined-action security model (only allowlisted action IDs; the desktop owns all behaviour) | unchanged |
| 23 of 27 action IDs | unchanged |

## 2. What changed

| Area | Before | Now |
|---|---|---|
| Desktop agent | Swift / CoreBluetooth menu-bar app (`macos/`) | C# / .NET 10 tray app (`windows/`) |
| Connected platform | a hardcoded `Platform.MACOS` constant | announced by the agent (`desktop_info`) and stored dynamically |
| Capability knowledge | none (assumed) | announced by the agent (`capabilities`), used to disable widgets |
| 4 action IDs | `spotlight`, `mission_control`, `open_finder`, `open_activity_monitor` | `windows_search`, `task_view`, `open_explorer`, `open_task_manager` |
| Permission metadata | `ACCESSIBILITY` (14 actions) | `INPUT_SIMULATION` (8 actions) |
| Saved-layout schema | 1 | 2 (schema 1 is migrated on load) |
| Advertising | `CBPeripheralManager` advertised the service UUID | WinRT `GattServiceProvider`; falls back to a compact alias UUID when the adapter refuses the 128-bit one (§5) |
| Media source | AppleScript against Music / Spotify | Windows system media sessions (generic) |
| Telemetry sources | Mach `host_statistics` | `GetSystemTimes`, `GlobalMemoryStatusEx`, PDH `GPU Engine` counters |

## 3. Executed plan

1. Read everything: `TASKS.md`, `README.md`, `docs/tasks/TASK-001…009A`, all Android
   code and tests, all macOS Swift code and tests, packaging scripts.
2. Establish what a Windows agent *can* do on this hardware by spiking, before designing
   around it: the BLE peripheral API on this PC's adapter, PDH GPU counters, media
   sessions, thermal counters (§5, §7).
3. Build the Windows solution (`windows/`): protocol, actions, telemetry, media,
   artwork, BLE, tray, startup registration, diagnostics, build script. Port every
   behaviour and every test from the macOS agent, then add the Windows-specific ones.
4. Android: dynamic platform + capability state, widget availability rules, Windows
   action set, schema-2 layout with migration, compact-UUID scan/lookup, Windows
   default pages, tests.
5. Verify every macOS file was ported or intentionally dropped, then delete `macos/`.
6. Docs, then a final audit (§12).

## 4. Windows solution

```
windows/
├── AliControl.slnx
├── global.json                       .NET SDK 10 (rollForward latestFeature)
├── scripts/build.ps1                 restore → build → test → publish
├── AliControlWindows/                the agent (WinExe, net10.0-windows10.0.19041.0, x64)
│   ├── App/        Program, AgentHost (composition root), tray UI, StartupRegistration,
│   │               AgentLog, Diagnostics (--diagnose), TrayStatusText, TrayIcons
│   ├── BLE/        AliControlBleProfile, IBleGattServer/IBluetoothRadio, WinRtGattServer,
│   │               WinRtBluetoothRadio, PeripheralManager, PriorityNotificationQueue,
│   │               NotificationPump
│   ├── Protocol/   ProtocolMessage, AliControlActions, DesktopInfoMessage (+ capabilities),
│   │               WireText, WireLimits, JsonWire
│   ├── Actions/    ActionRegistry, CommandDispatcher, Controllers (media/system/apps/window/
│   │               productivity), SystemApis (SendInput, shell, power, window, probe)
│   ├── Telemetry/  Cpu/Memory/Gpu samplers, SystemTelemetryProvider
│   ├── Media/      MediaState, MediaSessionCoordinator, WindowsMediaSessionSource,
│   │               MediaTimeline, MediaSourceNames
│   ├── Artwork/    ArtworkProcessor, WinRtImageCodec, ArtworkStore, ArtworkPipeline,
│   │               ArtworkWire, ArtworkTransferSession
│   ├── Live/       LiveStatePublisher, LiveStateMessages
│   └── State/      AgentStatus, SerialAgentExecutor, IScheduler
└── AliControlWindows.Tests/          xUnit (468 tests)
```

The agent has **no NuGet dependencies**: the WinRT APIs come from the Windows SDK
projection in the target framework, WinForms is in-box, and PDH / `SendInput` /
`GetSystemTimes` are plain P/Invoke. It builds to a self-contained single file
(`windows/dist/AliControl/AliControl.exe`, ≈135 MB, no runtime needed — uncompressed on purpose: single-file compression would cut it to ≈55 MB but roughly double the always-running agent's resident memory, 170 MB vs 79 MB measured).

Every component that touches the real machine sits behind an interface
(`IKeyboardInput`, `IShell`, `ISystemProbe`, `IPowerControl`, `IWindowApi`,
`IBleGattServer`, `IBluetoothRadio`, `IMediaSessionSource`, `IImageCodec`,
`IStartupStore`, …) so the tests never press a key, lock or sleep the PC, launch a
program, change a startup entry, or touch Bluetooth.

## 5. BLE on Windows

### API choice

`Windows.Devices.Bluetooth.GenericAttributeProfile.GattServiceProvider` is the
supported way for a desktop app to be a BLE **peripheral**. It needs no admin
rights, no driver, no package identity, and opens no network port. The agent checks
`BluetoothAdapter.IsPeripheralRoleSupported` and reports "adapter cannot act as a
peripheral" instead of failing mysteriously.

### GATT layout (identical to before)

* Service `7eb4052b-fc5f-436f-ae69-c3c9e7e9809f`
  * **command** `6544209f-…`: `Write | WriteWithoutResponse`, protection `Plain` (no pairing dialog)
  * **state** `71dbc91d-…`: `Notify`, protection `Plain` (Windows adds the CCCD descriptor itself)

Writes are acknowledged at the ATT level immediately (before the payload is looked
at); the application-level acknowledgement is a separate `state` notification.

### Advertising finding (important)

On the development PC (Qualcomm FastConnect 7800, Windows 11 26200) a connectable
`GattServiceProvider` advertisement for the **128-bit** service UUID reproducibly
ends in `GattServiceProviderAdvertisementStatus.Aborted`, while a 16- or 32-bit
service UUID starts normally (and Windows sometimes reports a *transient* `Aborted`
followed by `Started` a few milliseconds later). The likely cause is the advertisement
payload (flags + 128-bit UUID + device name) not fitting the legacy 31-byte packet on
that controller. Because the failure is adapter/driver/computer-name dependent, the
agent handles it instead of assuming:

1. It creates the service and advertises it, and treats the first `Aborted` as
   provisional — it waits up to 1.5 s for `Started`.
2. If the 128-bit UUID is rejected it tears that service down and hosts the **same
   command and state characteristics** under a compact **alias service**
   `a11c0e01-0000-1000-8000-00805f9b34fb` (a 32-bit UUID in the Bluetooth base range),
   which advertises reliably.
3. The tablet scans for **both** UUIDs (OR-ed filters) and, after connecting, uses
   whichever of the two services the device exposes. Everything after discovery is
   identical.
4. The tray shows `Advertising: Yes (compact discovery)` when the alias is in use, and
   `AliControl.exe --diagnose` reports which candidates work on a given PC.

If both fail the tray shows `Advertising: Failed`, and the agent retries with bounded
back-off (3 s, 10 s, 30 s, 60 s) before waiting for **Restart Advertising**, a
Bluetooth power cycle, or a resume from sleep. It never loops forever.

### Lifecycle (all event-driven; nothing polls Bluetooth)

* **Radio**: `Radio.StateChanged` drives everything. Off → the service, any connection,
  the queue and telemetry are torn down; On → the service is re-created and advertised.
* **Subscribe** (CCCD written): the agent sends `{"type":"state","status":"connected"}`,
  then `desktop_info`, then `capabilities`, then starts telemetry. **Unsubscribe**
  (explicit, or the GATT session closing): queue cleared, artwork transfer cancelled,
  telemetry stopped, advertising checked again shortly after (Windows may pause it
  while connected).
* **Sleep/resume**: after `PowerModeChanged.Resume` the service is rebuilt from scratch
  (the controller state across sleep is not trustworthy).
* **Quit / sign-out**: advertising is stopped explicitly so nothing is left behind.
* One tablet at a time; a second subscriber replaces the first.

### Notifications, MTU and back-pressure

The tablet requests an ATT MTU of 185 on connect (unchanged). The agent reads the
client's real `MaxNotificationSize` **at send time** (it changes when the MTU is
negotiated) and never trusts a constant:

* a live-state message larger than `min(client limit, 160)` is dropped, not truncated
  (another follows 2 s later); anything larger than the client's limit is refused at
  the last moment;
* artwork chunk size = client limit (capped at 160) − the 12-byte frame header;
* `NotifyValueAsync` completes when the stack has handed the notification to the radio,
  and the pump awaits each one — that **is** the flow control. Transient protocol errors
  keep the notification at the head of the queue and retry (bounded); a vanished client
  stops the pump.
* a two-tier queue: acknowledgements, the handshake and live state are high priority;
  artwork frames are low priority and the choice is re-made before every send, so a
  button press during an image transfer is acknowledged before the next artwork frame.

### Track message budget

A `track` message carrying an `artworkId` has 81 bytes of fixed structure, so the old
nominal budgets (title 48 + artist 36 + album 26) could reach 191 bytes — over the
160-byte limit. The agent now shares the remaining ~79 bytes between the three text
fields ("water-filling": a short artist leaves room for a long title), cuts on grapheme
boundaries, and a test proves every combination fits. Short metadata is never
truncated.

## 6. Protocol additions (`protocol` version 2)

Nothing that existed changed. Two message types were added; both travel on the state
characteristic and are ignored by an older parser.

```json
{"type":"desktop_info","platform":"windows","protocol":2,"name":"ALI-PC"}
{"type":"capabilities","ids":["actions","telemetry.cpu","telemetry.memory","telemetry.gpu","media","media.artwork"]}
```

* `desktop_info` is sent once per connection and tells the tablet which platform it is
  talking to. `name` is bounded (24 escaped bytes).
* `capabilities` is **additive**: a long list is split over several messages (dotted IDs
  are long; all eight possible IDs do not fit the 160-byte budget with the identity
  fields, and the protocol never truncates). A new `desktop_info` starts the set over.
* Capability IDs: `actions`, `telemetry.cpu`, `telemetry.memory`, `telemetry.gpu`,
  `telemetry.temperature.cpu`, `telemetry.temperature.gpu`, `media`, `media.artwork`.
  The agent announces **only what it probed as working on that machine**: `telemetry.gpu`
  needs the PDH counters, `media`/`media.artwork` need the media session service. The
  two temperature IDs are defined but never announced (§7).

The tablet text reader now decodes every standard JSON escape (`\n`, `\/`, `\uXXXX`),
fixing a latent bug where a title containing `/` could arrive as `\/`; the agent itself
writes only `"` and `\` as escapes and replaces control characters with spaces.

## 7. Telemetry

One sampler, 2 s, running only while a tablet is subscribed; the GPU counter is opened
on subscribe and closed on unsubscribe.

| Metric | Source | Definition |
|---|---|---|
| CPU | `GetSystemTimes` | busy share of CPU time elapsed since the previous sample (`kernel − idle + user`), all logical processors; first sample has no baseline |
| RAM | `GlobalMemoryStatusEx` | `(total physical − available physical) / total`. *Available* includes the standby cache, so this matches Task Manager's "In use" and a healthy PC does not read ~100 % |
| GPU | PDH `\GPU Engine(*)\Utilization Percentage` | per-engine sum over all processes → busiest engine per GPU → busiest GPU. Vendor-neutral (the data behind Task Manager's GPU column); no admin, no driver; cost ≈ 0–8 ms per sample |
| CPU / GPU temperature | **none** | see below |

**Temperatures are deliberately not reported.** Windows has no public, user-mode,
vendor-neutral temperature API. The options that exist: the ACPI "Thermal Zone
Information" counters (on the development PC the counter set has *no instances at all*,
and where present they are not CPU-package temperatures); WMI `MSAcpi_ThermalZoneTemperature`
(admin only, same accuracy problem); kernel-driver libraries such as LibreHardwareMonitor
(need elevation and a driver); vendor SDKs (NVML, ADL, igcl — one vendor each). All are
excluded by the rules (no admin, no drivers, no fragile vendor hacks), so the capabilities
are not advertised and the tablet shows `--`. Vendor adapters belong to TASK-013.

If a machine has no GPU engine counters, `telemetry.gpu` is simply not announced.

## 8. Actions

27 actions + the protocol-level `ping`. Fixed implementations only; the tablet never
sends a path, an argument or a command string, and the handler signature is
`Func<bool>`.

| ID | Windows implementation |
|---|---|
| `play_pause` `next_track` `previous_track` | system media keys (`VK_MEDIA_*`) via `SendInput` — global, focus-independent, routed by Windows to the active media session |
| `volume_up` `volume_down` | `VK_VOLUME_UP/DOWN` × 5 (Windows steps 2 % per press → the 10 % step the buttons always meant); shows the native volume flyout |
| `mute_toggle` | `VK_VOLUME_MUTE` |
| `lock_screen` | `LockWorkStation()` (locks; never signs out) |
| `sleep` (CONFIRM) | `SetSuspendState(false,false,false)` ≈1 s *after* the ACK is sent, so the ACK still reaches the tablet |
| `display_sleep` | `WM_SYSCOMMAND/SC_MONITORPOWER` to the desktop window |
| `screenshot` | Win+PrintScreen (whole screen → `Pictures\Screenshots`, no interaction at the PC) |
| `show_desktop` | Win+D |
| `windows_search` | Win+S |
| `task_view` | Win+Tab |
| `open_terminal` | Windows Terminal via its `wt.exe` alias; if absent, the fixed system Windows PowerShell |
| `open_vscode` | `Code.exe` from the registry App Paths, then the three standard install locations |
| `open_explorer` | `%SystemRoot%\explorer.exe` |
| `open_browser` | the user's real default browser, from the shell's own `AssocQueryString("https")` (never hardcoded) |
| `open_spotify` | the registered `spotify:` URI (desktop and Store editions); probed first so a PC without Spotify gets a clean error, not the shell's "get an app" dialog |
| `open_system_settings` | `ms-settings:` |
| `open_task_manager` | `%SystemRoot%\System32\Taskmgr.exe` |
| `minimize_window` `close_window` | `WM_SYSCOMMAND` (`SC_MINIMIZE` / `SC_CLOSE`) posted to the foreground window — the window's own title-bar commands; close lets the app run its "save changes?" logic and never force-quits anything |
| `fullscreen_toggle` | `SC_MAXIMIZE` / `SC_RESTORE`. Windows has no OS-level fullscreen, so this is maximize/restore. (ID kept; label is "Maximize / Restore") |
| `copy` `paste` `undo` `redo` | Ctrl+C / V / Z / **Y** via `SendInput` (Ctrl+Y is the Windows convention for redo) |

Window actions refuse the desktop, the taskbar (`Progman`, `WorkerW`, `Shell_TrayWnd`, …
— closing the desktop shell window raises the Windows *Shut Down* dialog), invisible
windows and the agent's own windows.

**Privilege model.** The agent runs as a standard user and asks for no elevation
(`asInvoker`). Windows blocks input/messages toward *elevated* windows (UIPI); such an
action fails safely with an `error` acknowledgement — nothing is escalated. Eight actions
are flagged `INPUT_SIMULATION` on the tablet (screenshot, show desktop, Windows Search,
Task View, copy, paste, undo, redo) and shown with a "Keyboard input" badge.

### Retired IDs (saved-layout migration)

| Old ID (schema 1) | New ID | Intent |
|---|---|---|
| `spotlight` | `windows_search` | system-wide search |
| `mission_control` | `task_view` | window / desktop overview |
| `open_finder` | `open_explorer` | file manager |
| `open_activity_monitor` | `open_task_manager` | process / performance monitor |

All other IDs are unchanged. The agent never accepts the old IDs.

`display_sleep` was macOS-only before; it is a real Windows action now and is kept.

## 9. Saved layouts

* **Since TASK-010** the dashboards live inside a profile document (`dashboard_profiles_json`,
  schema 3), each profile embedding a dashboard in the schema-2 format described here. The old
  single-layout key (`dashboard_layout_json`) is read once to become the Default profile and is
  never modified or removed afterwards. Everything below still applies to each embedded dashboard.
* Schema 2 is the dashboard format written from now on. Schema 1 documents are read, the four retired IDs
  are rewritten in every widget kind that references actions (single action, action
  group, media, slider) **before** validation, and the migrated document is written back
  immediately so it runs once. Positions, sizes, widget IDs, pages and order are
  untouched; no widget is lost.
* Strictness is preserved: a schema-2 document containing a retired or unknown ID, a
  schema-1 document containing an unknown ID, an unsupported version, corrupt JSON or an
  invalid structure still falls back to the shipped defaults (a hand-edited file cannot
  smuggle an arbitrary action ID into a layout).
* The saved dashboard is platform-neutral. Availability is a *rendering* decision
  (`WidgetSupport`): a widget the connected desktop cannot serve (an unknown platform, or
  a capability it did not announce) is dimmed and not tappable, and becomes usable again
  as soon as a desktop that supports it connects. Until the agent has introduced itself
  or announced capabilities, nothing is gated.

## 10. Media state and artwork

* **Source:** `GlobalSystemMediaTransportControlsSessionManager` — the same service the
  lock screen and volume flyout use. Generic: Spotify, Windows Media Player, browsers and
  anything else that publishes to the Windows media transport controls. No Spotify Web
  API, no cloud, no provider scripting; read-only (control stays on the media-key
  actions).
* **Fields:** app (normalised to a short id such as `spotify`), title, artist, album,
  playing/paused, position, duration, thumbnail. Position is "position as of
  `LastUpdatedTime`" plus the elapsed time × playback rate while playing, clamped into the
  track.
* **Selection policy** (unchanged from the original design, applied to sessions): a
  playing session wins; with several, the previously chosen one keeps the widget (no
  flip-flopping), the system's current session breaks ties; pausing does not hand the
  widget to another idle player; nothing playable → "Media unavailable".
* **Artwork:** the session's own thumbnail → centre-crop → 160×160 → JPEG at quality
  0.60 / 0.40 / 0.25 until ≤ 20 KiB (else skipped) → `artworkId` = first 8 bytes of
  SHA-256 of the *final* bytes (verified against the tablet's fixture
  `f09bb5200116bd26`). Processed once per track change, cached (8-entry LRU, plus a
  "this track has none" cache), sent only on `artwork_request`, request validated as 16
  lowercase hex characters (and *any* extra field rejects the request), chunked to the
  client's real limit, cancelled on disconnect or track change. No URL is ever fetched.
  Real Spotify thumbnails on the development PC compress to ≈ 4 KB.

## 11. Tray, startup, diagnostics

* **Tray** (WinForms `NotifyIcon`, no window): `Bluetooth`, `Tablet` (address of the
  connected tablet — a central's friendly name is not exposed to a peripheral),
  `Advertising`, **Open at Startup**, **Restart Advertising**, **Quit**. Icons are drawn
  at runtime (grey ring = off, half-blue = waiting, green = connected, amber = problem).
* **Open at Startup**: one value, `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`
  → `AliControl` = `"<path>\AliControl.exe"`. The same per-user mechanism Windows shows
  under *Settings → Apps → Startup* and Task Manager's *Startup apps*. No admin, no
  service, no scheduled task, no hidden file; written only when the user ticks the item,
  removed when they untick it. An entry the user disabled in Task Manager is reported as
  disabled (and re-enabling from the tray clears that flag). It refuses to register
  anything but the real `AliControl.exe` (never `dotnet.exe`).
* **Single instance** (named mutex); `AliControl.exe --quit` asks a running agent to stop
  cleanly; `AliControl.exe --diagnose` writes `%LOCALAPPDATA%\AliControl\diagnostics.txt`
  (adapter capabilities, probed capabilities, a real advertising test per candidate UUID).
* **Log:** `%LOCALAPPDATA%\AliControl\agent.log` (256 KiB cap, one rotated file): events
  only — Bluetooth state, advertising, connect/disconnect, command failures (action id
  and size only, never payloads or track metadata).

## 12. Security review

* **No arbitrary execution.** The registry is a closed dictionary of 28 IDs; handlers are
  `Func<bool>` and cannot receive anything from the tablet; launch targets are fixed
  strings built from `%SystemRoot%`, the registry or a fixed URI; no process is ever
  started with arguments. A source-audit test (`SourceAuditTests`) enforces this
  mechanically: only one wrapper may call `Process.Start`, no `.Arguments`, no `cmd.exe`,
  `PowerShell` only as the fixed terminal fallback, no verbs such as `runas`.
* **No network surface.** No sockets, no HTTP, no listener; the audit test fails if any
  such type or an `http(s)://` literal appears. BLE only.
* **No privilege.** `asInvoker` manifest, no service, no scheduled task, no driver; the
  only registry write is the user-toggled HKCU `Run` value.
* **Untrusted input.** Inbound writes are size-capped (512 B), strict UTF-8, strict JSON
  (complete object, string-typed fields, depth-limited); malformed input yields an error
  ACK. A write from a client that has not subscribed is ignored. Artwork requests are
  closed messages.
* **Existing model, unchanged:** BLE has no pairing, so any central in range can connect
  and send the allowlisted actions — as before. `lock_screen`, `sleep`, window and
  keystroke actions are therefore reachable by anyone within Bluetooth range of a PC
  running the agent. `sleep` is the only action that asks for confirmation (on the
  tablet); there is no shutdown, restart, sign-out or file action.
* **Keyboard injection scope:** only 16 virtual keys are in the injectable enum, never a
  caller-supplied key; there are no input hooks.

## 13. TASK-010 (Dashboard Modes & Profiles)

The migration made the default pages Windows-native — *Dashboard* (Windows Search, Task View,
File Explorer, browser, terminal, VS Code, transport/volume), *Media* (full media widget,
Spotify) and *Dev* (VS Code, Terminal, Browser, Windows Search, Explorer, Task Manager,
Settings, Screenshot, CPU/RAM/GPU) — as data in `DefaultDashboardFactory`, shared by every
desktop (no per-platform classes).

TASK-010 (now implemented — see the README's *Dashboard profiles*) builds on exactly that: the
**Default** profile *is* `DefaultDashboardFactory`'s layout, unchanged, and the Dev / Game /
Media templates (`dashboard/profile/BuiltInProfiles`) are defined the same way, as data over
Windows actions only. The Windows-migration compatibility carries through:

* An existing single-dashboard layout migrates into the Default profile once, and a macOS-era
  (schema 1) layout still has its four retired action ids rewritten (§8) before
  it becomes a profile — the same `DashboardSerializer` reader, now also used for each
  dashboard embedded in a profile document (schema 3).
* Profiles are contexts, not operating systems: there is no Mac or Windows profile and no
  per-platform profile model. Platform and capability awareness remains runtime-only
  (`WidgetSupport`): an unsupported widget is disabled, never removed from a profile.
* No Windows agent change was needed or made for TASK-010.

## 14. Known limitations

* **Not verified end to end with a tablet.** The agent, the protocol and the tablet's
  parsing are verified separately and the agent was run on real Windows hardware
  (advertising, capability probes, media sessions, artwork, GPU), but no Android device
  was connected to it. See the manual checklist in the README.
* **BLE advertising variance.** Whether the full 128-bit UUID advertises depends on the
  adapter, driver and computer name; the compact fallback exists because of it. Plugging
  in a Bluetooth adapter after the agent started needs an agent restart.
* **Elevated windows** cannot be controlled (Windows UIPI); those actions return `error`.
* **Display sleep** is a documented best effort: some modern-standby PCs wake the display
  on the next input event.
* **Screenshot** is Win+PrintScreen (full screen to `Pictures\Screenshots`); it needs the
  session to be unlocked.
* **Temperatures** are unavailable by design (§7). **Playback position** is sampled every
  2 s and not interpolated on the tablet.
* **Media thumbnails** depend on the app publishing one; apps that do not simply show the
  placeholder.
* **Peer name:** a connecting tablet's friendly name is not exposed to a Windows
  peripheral; the tray shows its Bluetooth address.
* Unsigned, un-notarised local build; no installer / MSIX yet (§15).

## 15. Deliberately deferred

* An installer / MSIX package and code signing.
* Vendor temperature adapters (NVML etc.) — TASK-013.
* Everything else already listed as open in `TASKS.md` (themes, advanced system controls,
  hardware integrations, AI developer integrations, weather/calendar) and the Windows Spotify
  end-to-end media issue.
* Automatic fallback transports (LAN, USB) — explicitly out of scope.
* Detecting a Bluetooth adapter plugged in after launch (`DeviceWatcher`).
