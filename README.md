# Ali Control

Ali Control turns an old Android tablet into a dedicated desk control panel for a
**Windows 11 PC** — media control, PC actions, developer shortcuts, live system
and now-playing data, and device settings, all from a fullscreen, always-on
second screen.

It has exactly two parts:

1. **Android tablet client** (`android/`) — Kotlin, XML views, API 27 / Android 8.1,
   built for the Alcatel 1T 7 8068 (MT6580, 7").
2. **Native Windows desktop agent** (`windows/`) — a lightweight C# / .NET 10 tray
   application. No Electron, no Node.js, no Python, no local server, no cloud.

macOS was the original reference desktop and is **no longer supported**; its agent was
removed in the Windows migration — see [docs/WINDOWS_MIGRATION.md](docs/WINDOWS_MIGRATION.md)
for what was kept, what changed, and why.

## Contents

- [Architecture](#architecture) · [BLE](#ble-architecture) · [Protocol](#protocol)
- [Action library](#action-library) · [Capabilities](#desktop-identity-and-capabilities)
- [Android client](#android-client) · [Dashboard profiles](#dashboard-profiles) · [Windows agent](#windows-agent)
- [Build & test](#build--test) · [Manual end-to-end checklist](#manual-end-to-end-test)
- [Limitations](#limitations)

## Architecture

```
Android tablet (BLE central)
  ui/                      MainActivity + secondary screens
    └─ ui/dashboard/       ViewPager2, grid/page/widget renderers, edit gestures
         └─ dashboard/     UI-independent page/widget/6x4 grid data, editor, serializer
                           (+ schema migration), layout store, widget availability rules
              └─ live/     DesktopStateStore — one shared source of live telemetry,
                           media state and the connected desktop's identity (never persisted)
                   └─ actions/  ActionLibrary metadata + shared ActionDispatcher
                        └─ transport/ + ble/   BluetoothTransport, protocol messages
                                  ⇅ BLE
Windows agent (BLE peripheral)
  App/                     tray lifecycle, Open at Startup, diagnostics, composition root
    └─ BLE/                GATT server (WinRT), connection state machine, priority queue/pump
         └─ Protocol/      ProtocolMessage, action IDs, desktop_info / capabilities
              └─ Actions/  ActionRegistry + fixed Windows implementations
              └─ Live/     LiveStatePublisher — one 2 s sampler, wire messages
                   └─ Telemetry/  CPU / RAM / GPU samplers
                   └─ Media/      Windows media-session source + selection policy
                   └─ Artwork/    resize/compress/cache + chunked transfer

Android kiosk/ and device/ remain independent supporting layers for immersive
mode, boot launch, keep-awake, and tablet-local settings/status.
```

Both sides implement the *same* protocol independently in their own language — Kotlin
and C# — with identical UUIDs, identical JSON message shapes and an identical
action-ID allowlist. Cross-platform fixtures on both sides (the literal JSON strings in
`ProtocolFixtureTest.kt` and in the agent's `Protocol/` tests, and a shared SHA-256
artwork-id fixture) exist to catch wire drift.

## BLE architecture

- **Android tablet = BLE central/client.** It scans, connects, writes commands and
  subscribes to state notifications
  ([`BluetoothTransport.kt`](android/app/src/main/java/com/alicontrol/app/transport/BluetoothTransport.kt)).
- **Windows PC = BLE peripheral/server.** It advertises the Ali Control service, exposes
  the command and state characteristics and executes commands as they arrive
  ([`WinRtGattServer.cs`](windows/AliControlWindows/BLE/WinRtGattServer.cs),
  [`PeripheralManager.cs`](windows/AliControlWindows/BLE/PeripheralManager.cs)) using the
  WinRT `GattServiceProvider`. No pairing dialog, no extra hardware, no admin rights.
- The adapter must support the **peripheral role** (`BluetoothAdapter.IsPeripheralRoleSupported`);
  the tray says so if it does not. `AliControl.exe --diagnose` reports it.

### Service / characteristic UUIDs

Defined once on the Android side
([`AliControlBleProfile.kt`](android/app/src/main/java/com/alicontrol/app/ble/AliControlBleProfile.kt))
and reused verbatim on Windows
([`AliControlBleProfile.cs`](windows/AliControlWindows/BLE/AliControlBleProfile.cs)) —
**unchanged since the original protocol**:

| Purpose | UUID |
|---|---|
| Ali Control service | `7eb4052b-fc5f-436f-ae69-c3c9e7e9809f` |
| Command characteristic (tablet writes, PC receives) | `6544209f-88b6-432e-9db1-c672b7a31916` |
| State characteristic (PC notifies, tablet subscribes) | `71dbc91d-eb99-4a50-b875-c3f045f4ea91` |
| Client Characteristic Config descriptor (standard; the tablet writes it, Windows manages it) | `00002902-0000-1000-8000-00805f9b34fb` |
| **Compact discovery alias** (fallback only, see below) | `a11c0e01-0000-1000-8000-00805f9b34fb` |

**The compact alias.** Some Bluetooth adapters/drivers refuse to advertise a connectable
GATT service whose 128-bit UUID plus the device name overflow the legacy advertisement
packet (Windows reports the advertisement status `Aborted`; this happens on the
development PC). When that happens the agent hosts the **same two characteristics** under
the 32-bit alias service above, which advertises reliably. The tablet scans for **both**
UUIDs and uses whichever service the device exposes, so nothing after discovery differs.
The tray shows "Advertising: Yes (compact discovery)" when the alias is in use.
Details and the evidence are in [docs/WINDOWS_MIGRATION.md §5](docs/WINDOWS_MIGRATION.md).

### Connection readiness & reconnection

**"Connected" means genuinely usable, not just GATT-linked.** The tablet's
`BluetoothTransport.ConnectionState` is
`DISCONNECTED → SCANNING → CONNECTING → PREPARING → CONNECTED`; `PREPARING` covers MTU
negotiation (185), service discovery, characteristic lookup and the CCCD write — it only
advances once `onDescriptorWrite` *confirms* success. A single 15 s timeout covers
`CONNECTING`+`PREPARING`; stale callbacks from an abandoned attempt are ignored.

**Tablet auto-reconnect:** after an unexpected drop of a previously fully-connected link
the transport retries the same device by address (no scanning) up to 3 times (2 s, 5 s,
10 s), then stops; manual Connect is always available.

**Windows side** (all event-driven; nothing polls Bluetooth):

- the radio being switched **off** tears down the service, any connection, the queue and
  telemetry; **on** re-creates and advertises again;
- a tablet **subscribing** triggers the connection handshake and starts telemetry;
  **unsubscribing/disconnecting** cancels artwork, stops telemetry, and re-checks that
  advertising is still up (Windows may pause it while connected);
- after **sleep/resume** the service is rebuilt from scratch;
- advertising failures are retried with bounded back-off (3 s, 10 s, 30 s, 60 s), then the
  agent waits for **Restart Advertising**, a Bluetooth power cycle or a resume;
- on **Quit** or sign-out advertising is stopped explicitly.

## Protocol

A minimal JSON envelope, hand-rolled on Android (no JSON library) and in the agent
(byte-identical wire output); neither side depends on key order:

```json
{"type":"command","action":"volume_up"}
{"type":"state","action":"volume_up","status":"ok"}
{"type":"state","status":"connected"}
```

A failed command gets `"status":"error"` instead of silence. Inbound payloads are
strictly validated on both sides: complete JSON object, correct field types, bounded size
(the agent caps writes at 512 bytes and rejects invalid UTF-8) — a truncated payload can
never half-parse into a different command.

**MTU / never-truncate rule.** The tablet negotiates MTU 185 on connect and refuses to
write anything larger than the negotiated payload. The agent reads the client's real
`MaxNotificationSize` at send time and refuses to send a notification that does not fit
(it drops one telemetry update rather than corrupt the stream — another follows 2 s
later); every live message is designed to stay under 160 bytes, verified by tests.

### Live state

The desktop→tablet direction reuses the state characteristic; messages are told apart by
`type`, and each side ignores the other family entirely:

```json
{"type":"metrics","cpu":34,"memory":48,"gpu":12}
{"type":"media","available":true,"source":"spotify","playing":true,"position":83,"duration":214}
{"type":"track","title":"Song","artist":"Artist","album":"Album","artworkId":"0123456789abcdef"}
```

An **omitted metric means unavailable**, never zero — that is what lets a widget choose
between `0%` and `--`. Playback state (small, changes every tick) and track identity
(larger, sent only when the track changes) are separate messages. Track text is bounded in
escaped UTF-8 bytes on grapheme boundaries; with an `artworkId` present the three text
fields share the ~79 bytes left under the 160-byte limit (a short artist leaves room for a
long title), so short metadata is never truncated and no combination can overflow.

### Desktop identity and capabilities

Sent once per connection, right after the tablet subscribes (protocol version 2; nothing
that existed before changed):

```json
{"type":"desktop_info","platform":"windows","protocol":2,"name":"ALI-PC"}
{"type":"capabilities","ids":["actions","telemetry.cpu","telemetry.memory","telemetry.gpu","media","media.artwork"]}
```

The tablet stores the connected platform **dynamically** (`DesktopState.desktop`); no
connected platform is hardcoded anywhere. `capabilities` messages are additive (a long
list may be split, and a new `desktop_info` starts the set over). The agent announces
**only capabilities it probed as working on that machine**:

| Capability | Announced when |
|---|---|
| `actions` | always |
| `telemetry.cpu` / `telemetry.memory` | the native counters answer |
| `telemetry.gpu` | PDH `GPU Engine` counters exist on this PC |
| `media` / `media.artwork` | the Windows media-session service is reachable |
| `telemetry.temperature.cpu` / `.gpu` | **never** (no reliable user-mode source; see below) |

**How the tablet uses them** (`WidgetSupport`): a widget the connected desktop cannot
serve — an unknown platform, or a capability it did not announce — is **dimmed and not
tappable, but never removed from the saved layout**, and works again as soon as a desktop
that supports it connects. Until the agent has introduced itself / announced capabilities,
nothing is gated. Disconnected, remote widgets are dimmed as before.

### Artwork over BLE

Artwork stays local and request-driven. The agent reads the thumbnail the Windows media
session itself provides (never a URL), centre-crops to a square, resizes to **160×160** and
encodes JPEG at quality **0.60, then 0.40, then 0.25** until it is at most **20 KiB**
(otherwise it is skipped; real Spotify thumbnails come out ≈ 4 KB). `artworkId` is the
lowercase hex of the first eight bytes of SHA-256 over the *final compressed bytes*.
Track metadata carries only that ID; the tablet checks its own cache and sends, only on a
miss:

```json
{"type":"artwork_request","artworkId":"0123456789abcdef"}
```

The agent only answers IDs in its own processed-artwork cache; the request is a closed
message (any extra field rejects it) and cannot name a path, filename or URL. A transfer
is an `artwork_begin` envelope, ordered binary chunks, and an `artwork_end` envelope; each
binary frame is `0xAC | 0x01 | artworkId (8 raw bytes) | chunk index (uint16 BE) | JPEG bytes`.
The 12-byte header is subtracted from the client's real notification limit. Notifications
go through a two-tier queue — acknowledgements, handshake and live state are high
priority, artwork frames low — and the choice is re-made before **every** send, so a
button press during a transfer is acknowledged before the next artwork frame.

The tablet validates ID, MIME type, declared size (max 20 KiB), chunk count (max 512),
exact sequential indices, byte count and the final content hash before decoding; it
decodes only completed JPEGs after a bounds pass, uses `RGB_565`, and keeps at most 8
bitmaps / 1 MiB in an LRU cache. A disconnect, track change or different artwork ID
discards the partial buffer; completed artwork survives reconnects.

## Action library

The full, explicit allowlist — nothing outside this list is ever sent or executed on
either side (no generic command path, no shell strings, no application paths over BLE).
Every action has structured metadata mirrored on both sides:

- **id** — the stable wire string ([`AliControlCommands.kt`](android/app/src/main/java/com/alicontrol/app/ble/AliControlCommands.kt) /
  [`AliControlActions.cs`](windows/AliControlWindows/Protocol/AliControlActions.cs))
- **label**, **category** (`MEDIA` / `SYSTEM` / `APPS` / `WINDOW` / `PRODUCTIVITY`) —
  tablet-side display metadata
- **supportedPlatforms** — currently `WINDOWS` for all
- **dangerLevel** — `SAFE` (sent immediately) or `CONFIRM` (the tablet asks first)
- **permissionRequirement** — `NONE` or `INPUT_SIMULATION`

The tablet's [`ActionLibrary.kt`](android/app/src/main/java/com/alicontrol/app/actions/ActionLibrary.kt)
only *describes* what may be sent; the agent's
[`ActionRegistry`](windows/AliControlWindows/Actions/ActionRegistry.cs) independently
re-validates every ID against its own allowlist.

| ID | Category | Danger | Input | Windows implementation |
|---|---|---|---|---|
| `ping` | *(protocol-only)* | — | — | acknowledged only |
| `play_pause` `next_track` `previous_track` | MEDIA | SAFE | — | system media keys (`SendInput`) |
| `volume_up` `volume_down` | MEDIA | SAFE | — | system volume keys ×5 (≈10 % step) |
| `mute_toggle` | MEDIA | SAFE | — | system mute key |
| `lock_screen` | SYSTEM | SAFE | — | `LockWorkStation()` |
| `sleep` | SYSTEM | **CONFIRM** | — | `SetSuspendState` (after the ACK is sent) |
| `display_sleep` | SYSTEM | SAFE | — | `SC_MONITORPOWER` |
| `screenshot` | SYSTEM | SAFE | ⌨ | Win+PrintScreen |
| `show_desktop` | SYSTEM | SAFE | ⌨ | Win+D |
| `windows_search` | SYSTEM | SAFE | ⌨ | Win+S |
| `task_view` | SYSTEM | SAFE | ⌨ | Win+Tab |
| `open_terminal` | APPS | SAFE | — | Windows Terminal (`wt.exe`); fixed PowerShell console if absent |
| `open_vscode` | APPS | SAFE | — | `Code.exe` from App Paths / standard locations |
| `open_explorer` | APPS | SAFE | — | `explorer.exe` |
| `open_browser` | APPS | SAFE | — | the user's real default browser (`AssocQueryString`) |
| `open_spotify` | APPS | SAFE | — | the registered `spotify:` URI |
| `open_system_settings` | APPS | SAFE | — | `ms-settings:` |
| `open_task_manager` | APPS | SAFE | — | `Taskmgr.exe` |
| `minimize_window` `close_window` | WINDOW | SAFE | — | `WM_SYSCOMMAND` to the foreground window (close never force-quits) |
| `fullscreen_toggle` | WINDOW | SAFE | — | maximize / restore (Windows has no OS-level fullscreen) |
| `copy` `paste` `undo` `redo` | PRODUCTIVITY | SAFE | ⌨ | Ctrl+C / V / Z / Y |

27 actions (6 media + 7 system + 7 apps + 3 window + 4 productivity). ⌨ = works by
injecting keyboard input into the focused window, which **Windows refuses toward elevated
(administrator) windows** — such an action fails safely with an `error` acknowledgement.
Window actions refuse the desktop, taskbar, invisible windows and the agent's own windows.
There is no shutdown / restart / sign-out / file action; `sleep` is the only confirmed one.

### Action IDs that changed in the migration

macOS-specific IDs were replaced by their Windows equivalents. Saved layouts are
migrated automatically on load (nothing is lost — see
[Persistence](#persistence)); the agent never accepts the old IDs:

| Old ID | New ID |
|---|---|
| `spotlight` | `windows_search` |
| `mission_control` | `task_view` |
| `open_finder` | `open_explorer` |
| `open_activity_monitor` | `open_task_manager` |

## Android client

### Requirements

- Kotlin, XML Views (no Jetpack Compose)
- `minSdk` 27 / `targetSdk` 27 (Android 8.1), `compileSdk` 33
- Gradle 8.4, Android Gradle Plugin 8.1.4, Kotlin 1.9.22, **JDK 17**
- Target hardware: Alcatel 1T 7 8068 (MediaTek MT6580, `armeabi-v7a`, 7", low-end)
- BLE GATT calls deliberately use the pre-API-33 APIs (`characteristic.value` +
  `writeCharacteristic(characteristic)`, the 2-arg `onCharacteristicChanged` /
  `onDescriptorWrite`) since the newer byte-array overloads don't exist on API 27
  devices — these carry `@Suppress("DEPRECATION")`.
- Runtime dependencies are `androidx.core`/`appcompat`, Material Components and
  `androidx.viewpager2` — no drag-and-drop, dashboard, charting or database framework.
  `org.json:json` is a **test-only** dependency (the unit-test `android.jar` stubs
  `org.json` out); it is not in the APK.

### UI

The home screen is a dedicated, multi-page dashboard. Its compact header keeps connection
state, the **profile switcher** (`[ Default ▾ ]`, see [Dashboard profiles](#dashboard-profiles)),
**All Actions**, **Device**, **Edit** and Connect/Disconnect immediately reachable;
`ConnectActivity` is the separate BLE scan/connect flow. Horizontal swipes move between
pages, with the page name and a dot indicator. The header shows the PC's own name once the
agent has announced it.

#### Grid and widget model

`dashboard/` is a UI-independent model. Each `DashboardPage` owns ordered
`DashboardWidget` values with a `WidgetPlacement` (`x`, `y`, `width`, `height`) on one
stable **6-column × 4-row logical grid**. Those **24 cells are placement units, not 24
mandatory buttons**. `x + width ≤ 6`, `y + height ≤ 4`, and widgets never overlap. Each
`WidgetType` declares its own size limits so content stays usable:

| Type | Min | Max |
|---|---|---|
| `ACTION` | 1×1 | 2×2 |
| `ACTION_GROUP` | 2×1 | 6×2 |
| `METRIC` | 1×1 | 2×2 |
| `GAUGE` | 1×1 | 2×2 |
| `CLOCK` | 2×1 | 4×2 |
| `DAILY_OVERVIEW` | 3×2 | 6×4 |
| `MEDIA` | 3×2 | 6×4 |
| `SLIDER` | 2×1 | 6×2 |
| `SHORTCUT` (local navigation) | 1×1 | 2×2 |

Widgets render responsively for the cells they occupy (a 1×1 `ACTION` is an icon over one
word — each action defines a short tile label, so "Task View" and "Task Manager" don't both
become "Task" — a 2×1 puts the full label beside the icon, a 2×2 goes large).

#### Edit Mode

**Edit** switches to an explicit editing mode: subtle 6×4 guides, header **Add / Pages /
Done**, ‹ › page arrows instead of swiping, and **no widget wires up its normal click
handler** — a stray tap cannot fire a remote action. **Select** by tapping (accent border,
remove badge, resize handle); **Move** by dragging (snap + legal/illegal tint); **Resize**
by the corner handle (clamped to type min/max and free space); **Remove** by the badge
(this page only — never the action library, the agent, or another page). Illegal drops and
resizes are **rejected, not corrected**, with a short message. All validation lives in
`DashboardEditor`, a pure Kotlin object.

**Add** opens the widget picker; adding an `ACTION` opens the action picker, generated
entirely from `ActionLibrary` and grouped by category with icons plus *Confirm* / *Keyboard
input* badges. There is no free-text field anywhere in the editor: an arbitrary action id
or shell string has no way in. New widgets take their type's default size at the first free
position scanning row-major; nothing existing is ever moved or dropped, and a full page
reports "Not enough space". **Pages** manages create / rename (non-blank, ≤ 24 characters) /
move / delete (the last page can't be deleted; deleting a page with widgets asks first) /
**Reset to default** (the active profile's layout only — see
[Reset](#create-duplicate-rename-reorder-delete-reset)). Editing needs no desktop connection,
and edits always apply to the **active profile's** dashboard.

#### Persistence

Every successful edit is serialized to JSON in its own small `SharedPreferences` file
(`dashboard_prefs`) — no Room, no SQL, no cloud. Two keys live there since TASK-010:

| Key | Holds | Schema |
|---|---|---|
| `dashboard_profiles_json` | the **profile document**: every profile, its dashboard, the last selected profile and the startup default (see [Dashboard profiles](#dashboard-profiles)) | **3** |
| `dashboard_layout_json` | the pre-profile single dashboard — read only, once, to migrate it into the Default profile; never rewritten or removed | 1 or 2 |

A *dashboard* is still the same JSON object (`DashboardSerializer`, **`schemaVersion` 2**) — each
profile embeds one — and its deserialization is strict and total: bad JSON, an unsupported
version, an unknown widget type, a widget outside the grid or its type's limits, overlapping
widgets, duplicate ids, or an unknown action id all return null, so a corrupted layout
degrades to "you get the defaults back", never to a crash. A stored layout always wins over
the shipped default.

**Saved-layout migration (action ids).** A dashboard written by a macOS-era build (schema 1)
is read and its four retired action IDs (table above) are rewritten to their Windows
equivalents in every widget kind that references actions *before* validation; positions,
sizes, widget ids, pages and order are untouched. This still applies to every dashboard that
reaches a profile — through the legacy-layout migration or embedded in a profile document.
Strictness is preserved: a schema-2 document naming a retired or unknown ID, or a schema-1
document naming an unknown ID, is still rejected.

#### Default pages

`DefaultDashboardFactory` supplies three Windows-native pages as data — they are the
**Default profile's** layout:

- **Dashboard** — Daily Overview; CPU/GPU/RAM; Lock, Screenshot, Device shortcut; a row of
  six 1×1 actions (Terminal, VS Code, Windows Search, Task View, File Explorer, Browser);
  compact ⏮ ▶ ⏭ and Vol−/Mute/Vol+ strips.
- **Media** — the full 6×3 `MEDIA` widget (live title/artist/album, progress, artwork),
  volume down/up, mute, Spotify and a clock.
- **Dev** — VS Code, Terminal, Browser, Windows Search, File Explorer, Task Manager,
  Screenshot, Settings, an All Actions shortcut, CPU/RAM metrics and a GPU gauge.

The shipped defaults are shared by every desktop (data, not per-platform classes), and are
unchanged by TASK-010: they are exactly what the Default profile contains and what *Reset
to Default Layout* restores.

**Daily Overview** shows real local tablet time and date (updated on minute boundaries)
beside explicit `Weather unavailable`, `No calendar data`, `Next event: --` placeholders —
weather/calendar are TASK-015, and plausible-looking fake values would be worse than none.

#### Actions, connection, and platform

Every remote action resolves through `ActionLibrary` and the shared `ActionDispatcher` and
`TransportManager`, so danger metadata still controls confirmation dialogs (a `sleep`
widget added to a custom page still asks). The connected platform is whatever the agent
announced (see [capabilities](#desktop-identity-and-capabilities)). Remote widgets dim and
disable while disconnected; metrics, clocks, Daily Overview and local Device/Settings
access remain usable. Action icons come from one `ActionIconRegistry` of local vector
drawables. A value the desktop cannot measure renders as `--`.

#### Live desktop state

`live/DesktopStateStore` is the single, application-scoped source of live data: **one**
BLE listener for the whole app. Widgets never subscribe to the transport, parse a payload
or own a timer — they register a small update closure (`LiveWidgetBindings`) while being
built and the store's state is pushed through them, so a CPU tile on the Dashboard page and
another on the Dev page are two renderings of one value. `DesktopState` is deliberately
**separate from the dashboard layout model**: layout is persisted user configuration, live
telemetry is volatile and never written anywhere. The store merges the two media messages
(playback updates carry no text and must not wipe the track metadata; an `available:false`
update clears both), holds the desktop's identity and capabilities, and **clears everything
on disconnect and on connect** so nothing from a previous session is inherited. Data that
stops arriving while the link still looks up expires after 10 s (five missed samples).

| Widget | Live behaviour |
|---|---|
| METRIC | `34%` / `48%`, or `--` when the desktop can't measure it (dimmed if the desktop did not announce that capability) |
| GAUGE | same value plus a flat bar — one cheap layout pass every 2 s, no animation |
| MEDIA | real title, `Artist — Album`, a progress row, artwork, and a play/pause glyph that follows the real playback state |

Media transport buttons still send `previous_track`, `play_pause`, `next_track` through
`ActionLibrary` — live metadata is a display channel, not a second control path.

**Actions screen** (`ActionsActivity`): lists every action the connected platform supports,
grouped by category, built at runtime from the registry; `sleep` asks first. It remains
available through **All Actions**.

### Dashboard profiles

A **profile** is a complete, independently customizable dashboard — one "mode" of the
tablet:

```
DashboardProfile  (id · name · order · builtIn)
└── Dashboard     ← the existing TASK-007/008 model, not duplicated
    └── Pages → Widgets
```

Profiles sit *above* the dashboard and add no second layout model or editor: Edit Mode,
widget move/resize/add/remove and page create/rename/reorder/delete are still the TASK-008
`DashboardEditor`, now operating on `activeProfile.dashboard`. The code is in
`dashboard/profile/`: `DashboardProfile`, `ProfileCollection` (profiles + last selected +
startup default), `BuiltInProfiles` (templates), `ProfileEditor` (pure operations),
`ProfileSerializer`/`ProfileStore` (persistence and migration) and `ProfileSession` (the
running app's state; it stores every successful operation).

#### Built-in profiles

Four ship with fixed ids that never change — the name is separate data and can be renamed:

| id | Name | Pages | What is on them |
|---|---|---|---|
| `default` | Default | Dashboard · Media · Dev | exactly the layout the app shipped with before profiles (see *Default pages* above) |
| `dev` | Dev | Dev · Tools · System | VS Code, Terminal, Browser (large), Explorer, Windows Search, Task Manager, live CPU/RAM/GPU · Screenshot, Show Desktop, Task View, clipboard and window strips · large readings, Settings, Lock, Device, All Actions |
| `game` | Game | Game · Media · System | large CPU/GPU/RAM, ⏮ ▶ ⏭, volume and a dedicated mute, Spotify, Task Manager, Show Desktop, Lock, Screenshot, Device · a full Media page · large one-tap system buttons |
| `media` | Media | Now Playing · Controls · Apps | the full 6×3 `MEDIA` widget (title, artist, album, progress, artwork) with a volume strip and clock · large transport and volume/mute · Spotify, Browser, Explorer and everyday launchers |

Every template uses the 6×4 grid, only existing widget types and `ActionLibrary` ids (all
Windows actions); tests check bounds, overlaps, size limits, action ids, page order and id
uniqueness for each, and pin the exact action set of Dev and Game. Nothing here pretends a
later feature exists: no Claude Code / Codex / Git / build controls (TASK-014), no Steam,
Discord, fan, temperature or game-detection items, and there is no automatic switching.
Profiles describe *contexts*, not operating systems — there is no per-platform profile.

#### Profile switcher and manager

The header's `[ Default ▾ ]` button opens a compact popup: the profiles in their saved order
(the active one checked), a divider, then **Manage Profiles**. It stays in the header while
editing.

**Manage Profiles** lists every profile with badges (*Built-in* / *Custom*, *Active*,
*Startup default*, page count). Tapping one offers **Switch to this profile**, **Rename**,
**Duplicate**, **Move up / down**, **Set as startup default**, **Reset to Default Layout**
(built-in) or **Clear layout** (custom), and **Delete**; **Add profile** is at the bottom. It
is plain `PopupMenu` / `AlertDialog` — no Compose, no drag-and-drop.

**Switching** leaves Edit Mode first (every edit is already saved the moment it is made, so
nothing is discarded), makes the profile active and remembers it, renders *that profile only*
(inactive profiles stay serialized data), shows its first page and updates the page
indicators. Opening Manage Profiles also leaves Edit Mode, so the manager can never act on
live editing state. While editing, remote actions are disabled exactly as before.

#### Create, duplicate, rename, reorder, delete, reset

- **Create** adds an empty profile (one blank page). Ids are generated (`custom_` + 12 random
  hex characters), never derived from the name.
- **Duplicate** deep-copies the dashboard — pages, widgets, placements and configs, including
  the lists inside them — so the copy shares no state with the original; it is named
  "*Name* Copy" (numbered if taken). A copy of a built-in profile is a custom profile.
- **Rename** trims, requires text and caps at 24 characters; the id never changes. Built-in
  profiles can be renamed too.
- **Reorder** moves a profile up or down; the switcher uses the saved order.
- **Delete** is for custom profiles only, always asks, and never touches another profile.
  Built-in profiles can't be deleted (the manager explains this and offers the reset), and
  the last profile can never be deleted. Deleting the active profile selects a fallback by
  the startup policy below.
- **Reset** affects one profile: a built-in goes back to its shipped template, a custom one
  to an empty dashboard (no "original source" is tracked for duplicates). Its name and
  position, every other profile, BLE, device settings, live media state and the artwork cache
  are untouched. It always asks first. The page menu's **Reset to default** is this same
  reset for the active profile.

#### Persistence and migration

The profile document (`dashboard_profiles_json`, **schema 3**) holds `lastSelectedProfileId`,
`defaultProfileId` and the ordered `profiles`, each with `id`, `name`, `builtIn`, `order` and
its `dashboard` (the schema-2 object). No Room, SQLite or cloud.

**Migration of the existing single dashboard** happens when there is no readable profile
document: a valid pre-profile layout becomes the **Default profile's dashboard** (retired
macOS action ids migrated as before), the shipped Dev, Game and Media templates are added, and
the result is saved at once — so it runs **once**, survives a restart, and later loads read the
profile document. The old `dashboard_layout_json` value is never modified or deleted (an older
build still finds its data, and a damaged profile document can be rebuilt from it). A corrupt or
unrecognised legacy value simply yields the shipped profiles and is left alone.

**Recovery is per profile**, so one damaged entry never costs the others: a custom profile with
an invalid dashboard (bad placement, overlap, unknown widget or action id) or a duplicate id is
dropped; a built-in with a missing or invalid dashboard is restored from its template; a missing
built-in is added; the stored `builtIn` flag is recomputed from the id; a blank name falls back.
Loading never writes — a repaired state is stored by the next ordinary save. A document from a
newer build is not read.

#### Startup policy

The profile restored at launch is resolved in this order:

```
last selected profile → configured startup default → built-in "default" → first valid profile
```

The *startup default* (**Set as startup default**) is only a fallback for when the last selected
profile can't be restored (e.g. it was deleted); normally the app reopens where you left it. It
is the persisted `defaultProfileId`, which is not the same thing as the built-in profile whose
id happens to be `default`.

#### Live Windows state, artwork and capabilities

Profiles are presentation only. `DesktopStateStore` stays application-scoped — one store, one
BLE listener — and the profile layer holds no reference to it, to the transport, or to the
artwork cache (a test enforces this boundary on both the classes' signatures and the source
imports). Switching therefore never reconnects, restarts advertising or telemetry, or clears the
desktop name, capabilities, CPU/RAM/GPU, media metadata, playback progress, artwork or the
artwork cache; the newly rendered pages are filled from the state the adapter already holds, so a
cached track's artwork appears immediately in the Media profile and is not requested again.
Capabilities remain runtime information: a widget the connected desktop can't serve (e.g. GPU
without `telemetry.gpu`) stays in its profile and renders disabled, and works again when the
capability returns — no layout is ever rewritten from capability state.

#### Profile limitations

- A profile opens on its **first page**; the last-viewed page is not remembered per profile.
- The Default profile keeps its existing **Dev** page (the pre-profile layout is preserved
  rather than redesigned), so it overlaps a little with the Dev profile.
- No themes (TASK-011), no automatic switching, no per-profile BLE or device settings.
- Reordering is move up/down only; delete, reset and clear have no undo beyond their confirmation.
- A duplicate keeps the original's page and widget ids (they only need to be unique within one
  dashboard); only the profile id is new.
- The profile UI is unit-tested at the logic level only; see the manual checklist.

### Kiosk / immersive fullscreen

`kiosk/KioskController` owns this. Every screen hides the status and navigation bars via
`SYSTEM_UI_FLAG_IMMERSIVE_STICKY` + `FULLSCREEN`/`HIDE_NAVIGATION`, reapplied from both
`onResume()` and `onWindowFocusChanged(true)`. On `MainActivity`, `onBackPressed()` is a
no-op (it stops editing in edit mode). This is **application-level** kiosk behaviour — it
cannot block the Home or Recents buttons (that needs Device Owner provisioning, out of
scope).

**Development escape hatch:** press and hold the screen title / connection status text for
3 seconds to reveal the system bars via `KioskController.exitImmersiveMode()`. It is
self-healing: the next `onResume` / window-focus event reapplies immersive mode.

### Launch on boot

`kiosk/BootReceiver` listens for `BOOT_COMPLETED` and starts `MainActivity` directly — no
service, no polling. It is exempt from Android 8's implicit-broadcast restrictions so a
manifest-declared receiver works on API 27. Boot-launch is unconditional (no in-app toggle).

### Device / Settings screen

`DeviceSettingsActivity` (the ⚙ button); all logic lives in `device/`:

| Setting | Behaviour |
|---|---|
| Wi-Fi | opens `Settings.ACTION_WIFI_SETTINGS`; no credential storage |
| Bluetooth | shows on/off; opens `Settings.ACTION_BLUETOOTH_SETTINGS` |
| Brightness | in-app slider writing `Settings.System.SCREEN_BRIGHTNESS`; disabled with a "Grant permission" button without `WRITE_SETTINGS` |
| Tablet volume | slider over `AudioManager.STREAM_MUSIC` — separate from the remote PC volume commands |
| Keep screen awake | switch persisted in `SharedPreferences`; applied per Activity |
| Battery | read-only percentage, event-driven (`ACTION_BATTERY_CHANGED`) |

### Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

Unchanged by the migration. `targetSdk` 27 is below the API 31 cutoff, so the classic
Bluetooth permissions are granted at install; `ACCESS_COARSE_LOCATION` is requested at
runtime by `ConnectActivity` (BLE scans return nothing without it); `WRITE_SETTINGS` is a
special permission granted on its own system screen (the brightness slider degrades to
disabled without it). No Accessibility, no Device Admin/Owner, no network/server
permission. The APK also carries the AGP-injected
`…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (a build-tooling artifact).

### Android limitations

- No true OS-level lockdown without Device Owner provisioning.
- Boot-launch reliability varies by OEM (some budget MediaTek skins need "autostart"
  allowed manually) and has no in-app toggle.
- `WRITE_SETTINGS` is a manual per-app grant.

## Windows agent

### Requirements

- **Windows 11, x64** (Windows 10 2004+ should work: the project targets 10.0.19041).
- A Bluetooth adapter with **LE peripheral role** support (most Bluetooth 4.0+ adapters;
  `AliControl.exe --diagnose` checks).
- To build: **.NET SDK 10** (`global.json` pins it). To run the published build: nothing —
  it is self-contained.
- Runs as a **standard user**. It never asks for elevation.

### Run it

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

### Open at Startup

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

### Live telemetry

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
announced and the tablet shows `--`. Vendor adapters belong to TASK-013.

### Now Playing

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

**Spotify:** works through the same generic path — title, artist, album, state, position,
duration and artwork were verified against the real Windows Spotify app on the development
PC. `open_spotify` launches it through its registered `spotify:` URI (desktop and Microsoft
Store editions); on a PC without Spotify it fails cleanly. Apps that don't publish a
thumbnail simply show the placeholder.

### Permissions & privilege

| Need | Why | How |
|---|---|---|
| Bluetooth | advertise, accept the tablet | no prompt for an unpackaged desktop app; Windows' "Bluetooth" must be on |
| Keyboard input | 8 actions press real Windows shortcuts | `SendInput` as the signed-in user; blocked by Windows toward elevated windows |
| Registry write | only **Open at Startup**, only HKCU | on user action |

No administrator rights, no UAC prompt, no service, no kernel driver, no network access, no
screen recording, no input hooks. `app.manifest` declares `asInvoker`.

### Logging

`%LOCALAPPDATA%\AliControl\agent.log` (256 KiB cap, one rotated file): event-driven lines
only — Bluetooth state, advertising mode, connect/disconnect, unparseable payload sizes,
command failures (action id only). Nothing on a timer; never payload content, track
metadata or the machine name.

## Build & test

### Windows agent

```powershell
cd windows
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

Runs `dotnet restore`, `dotnet build`, `dotnet test` (468 tests) and publishes a
self-contained single-file x64 build to:

```
windows\dist\AliControl\AliControl.exe        (≈ 135 MB, no .NET runtime needed; ≈ 80 MB resident, ~0 % CPU idle)
```

Options: `-SkipTests`, `-Configuration Debug`, `-FrameworkDependent` (≈ 1 MB, needs the .NET 10
Desktop Runtime). Or by hand:

```powershell
cd windows
dotnet restore
dotnet build AliControl.slnx
dotnet test  AliControl.slnx
```

The tests never press a key, lock or sleep the PC, launch a program, close a window, change a
startup entry, or touch Bluetooth or media playback: everything real sits behind an
interface and is replaced by a recording fake. They cover the protocol (fixtures, ACKs,
malformed and oversized input, `desktop_info`/capabilities), every action's routing and
exact key chord / launch target, the failure-isolation rules, telemetry maths (CPU, RAM,
GPU aggregation, unavailable metrics, sampler lifecycle), media selection and timeline,
artwork (compression ladder, IDs, chunking, request validation, cache, a real in-memory
WinRT codec round trip), the priority queue and pump, the whole connection lifecycle
(advertising fallback and retries, subscribe/unsubscribe, reconnect, Bluetooth off/on,
sleep/resume, cancellation), Open at Startup, the tray status text, and a source audit
that mechanically enforces the "no arbitrary execution / no network / no elevation" rules.

### Android

Requires JDK 17 and the Android SDK (platform 33, build-tools 33.0.2). From `android/`:

```bash
./gradlew clean assembleDebug testDebugUnitTest   # debug APK + unit tests
./gradlew assembleRelease                         # unsigned release APK
```

- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Unsigned release APK: `android/app/build/outputs/apk/release/app-release-unsigned.apk`

The release build has no signing config (no keystore or credentials exist in this repo).
`lint`'s `ExpiredTargetSdkVersion` check is disabled in `app/build.gradle` — targeting API 27
is this project's deliberate requirement for the tablet, which is sideloaded and never
distributed via Play.

Install/update on the tablet (USB debugging enabled):

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Manual end-to-end test

Not performed automatically — it needs the physical tablet and the PC in the same room.
The agent itself was run on real Windows hardware (advertising, capability probes, media
sessions, artwork, GPU counters); a tablet was not connected to it.

1. Install the APK and launch Ali Control on the tablet.
2. Build and run `AliControl.exe`. Confirm the tray icon appears and, within a couple of
   seconds, shows `Advertising: Yes` (possibly `(compact discovery)` — both are fine).
   Optional first: `AliControl.exe --diagnose`.
3. On the tablet tap **Connect** (grant the location prompt on first run) and pick the PC.
   Confirm the tablet reaches a true **Connected** state and the tray icon turns green with
   the tablet's address. The header should show the PC's name.
4. Verify Dashboard, Media and Dev render on the 6×4 grid and swiping is smooth. Verify
   widgets are enabled, and that CPU/RAM (and GPU, if announced) change from `--` to real
   values within a few seconds and CPU rises under load.
5. Run the actions in roughly ascending risk order, **Sleep last**: Windows Search, Task
   View, File Explorer, Terminal, VS Code, Browser (confirm it is your real default
   browser), Spotify, Settings, Task Manager, Screenshot (check `Pictures\Screenshots`),
   Show Desktop, Lock Screen, Display Sleep, Minimize / Maximize-Restore / Close Window
   (close a Notepad window, not something unsaved), Copy / Paste / Undo / Redo, volume ±
   and mute, play/pause/next/previous — then Sleep (confirm the dialog appears first).
6. Play a track in Spotify (or another media app): confirm title, artist, album, state and
   position/duration appear and advance, artwork appears on the Media widget shortly after
   the metadata, and the tablet's Play/Pause/Next/Previous change what the PC plays.
   Skip tracks quickly: no stale artwork, no crash. Confirm commands stay responsive during
   an artwork transfer.
7. Verify an unavailable-app action fails cleanly (e.g. Spotify on a PC without it): an
   error toast, no crash, no dialog on the PC. Focus an *elevated* window (e.g. Task Manager
   run as administrator) and confirm Copy/Paste fails with an error while Volume still works.
8. Edit mode: move/resize/add/remove widgets, create/rename/reorder/delete pages, confirm
   persistence across an app restart and a tablet reboot, then **Reset to Default**.
9. **Saved-layout migration:** if the tablet still has a layout saved by the macOS-era build,
   confirm after updating that its Spotlight / Mission Control / Finder / Activity Monitor
   widgets now show Windows Search / Task View / File Explorer / Task Manager in the same
   places.
10. Disconnect and confirm remote widgets disable, CPU/RAM/GPU return to `--` and media to
    "Media unavailable", while editing, Device/Settings and All Actions keep working;
    reconnect and confirm everything recovers without restarting either app.
11. Quit the agent from the tray (or `AliControl.exe --quit`): the tablet must notice
    immediately. Relaunch it: the tablet reconnects (auto-reconnect or manual Connect).
12. Toggle PC Bluetooth off and on: advertising must recover by itself. Toggle tablet
    Bluetooth off and on: the tablet must recover. Put the PC to sleep and wake it: the
    agent must advertise again within a few seconds.
13. Tick **Open at Startup**, sign out and back in: the agent must launch by itself and appear
    in Settings → Apps → Startup; untick it and confirm the entry disappears.
14. Leave both idle for several minutes: the agent must stay near 0 % CPU in Task Manager and
    `agent.log` must stay quiet. Reboot the tablet and confirm Ali Control launches on boot.
15. On the tablet exercise Device/Settings (brightness, tablet volume, battery, Wi-Fi /
    Bluetooth entries, keep-awake), the development escape hatch, and returning from Android
    Settings.
16. **Profiles — switching** (with the PC connected and a track playing): open the header
    switcher and confirm *Default / Dev / Game / Media* and *Manage Profiles*. Switch
    Default → Dev → Media → Default. Confirm the header stays **Connected** with the PC's
    name, CPU/RAM/GPU keep updating, the current track and its artwork are still shown on the
    Media profile immediately (cached artwork is not re-fetched), and the page dots follow
    each profile's own page count.
17. **Profiles — independence and Edit Mode:** in Default enter Edit Mode and move a widget,
    then switch to Dev *while still editing* (Edit Mode ends, nothing is lost). Confirm Dev is
    unchanged, switch back and confirm the move persisted. Customise Dev, then restart the app
    and confirm the **last selected** profile reopens.
18. **Profiles — management:** from *Manage Profiles* create a profile, rename it, add and
    remove widgets, duplicate it and change the copy (original unchanged), reorder, **Set as
    startup default**, delete the custom profile, try to delete a built-in (the manager
    explains and offers the reset), **Reset** Dev and confirm Default is unchanged. Restart and
    confirm order, names and the startup default persisted; delete the profile you were on,
    restart, and confirm the startup default opens.
19. **Profiles — migration:** after updating from a build that has a customised single
    dashboard, confirm that layout is now the **Default** profile (same pages, widgets and
    places), and that Dev, Game and Media are present with their shipped layouts.
20. **Profiles — offline:** disconnect the PC, switch and edit profiles (remote widgets stay
    dimmed), reconnect, and confirm the active profile's widgets recover live state. Reboot the
    tablet and confirm the profiles, the selection and kiosk mode persist.

## Limitations

- **Not verified with a tablet.** The two halves are verified separately (the agent was run
  on real hardware; the tablet's parsing, state, migration and rendering rules are unit-tested)
  but the connected loop needs the manual checklist above. General BLE reliability in the
  field (range, multiple nearby devices, the bounded auto-reconnect recovering a real
  dropped link) is exercised by unit tests at the decision-logic level only.
- **Advertising depends on the adapter/driver/computer name.** The compact-alias fallback
  exists because of that (see above); a Bluetooth adapter plugged in after the agent started
  needs an agent restart.
- **Elevated windows can't be controlled** (Windows UIPI): keystroke/window actions return an
  error. **Display Sleep** is best-effort on modern-standby PCs. **Screenshot** needs an
  unlocked session.
- **Temperatures are unavailable** by design (see above). **Playback position** updates every
  ~2 s and isn't interpolated on the tablet (a smoother bar would need a per-widget timer,
  which is ruled out).
- **Media metadata is bounded** (it is shortened on the PC before sending — see the track
  budget above). **Thumbnails** depend on the app publishing one.
- **If MTU negotiation ever fails** and the link stays at the 23-byte default, live-state
  notifications are dropped by the size guard rather than sent truncated (widgets stay `--`);
  commands are already unusable in that state because the tablet refuses to send them.
- **Profiles** (TASK-010) are verified by unit tests and code review, not on a tablet; see
  [Profile limitations](#profile-limitations) and manual steps 16–20. They do not change or fix
  the separate, still-open Windows Spotify media-delivery issue.
- **Security model, unchanged:** BLE has no pairing, so any central in range can connect and
  send the allowlisted actions — including lock, sleep and keystrokes — to a PC running the
  agent. There is no shutdown/restart/sign-out/file action and nothing outside the allowlist
  is executable.
- Local, unsigned build; no installer/MSIX yet.
