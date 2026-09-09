# Ali Control

Ali Control turns an old Android tablet into a dedicated desk control panel for a
desktop computer — media control, PC actions, developer shortcuts, device state,
and settings, all from a fullscreen, always-on second screen.

## Project purpose

An Android tablet (BLE central) talks to a small native desktop agent (BLE
peripheral) over Bluetooth Low Energy to control the desktop and get simple
state back. TASK-001 built the Android UI shell. TASK-002 added the BLE
transport and protocol on the Android side. TASK-003 added the first real
desktop agent — a macOS menu-bar helper — and wired up the first five
functional controls end to end. TASK-004 turned the tablet side into a
dedicated appliance: persistent immersive fullscreen, launch-on-boot, and an
in-app Device/Settings screen. TASK-005 turns the prototype into a stable,
daily-usable MVP: a real double-clickable macOS `.app` (no more `swift run`),
Open at Login, fully automatic BLE startup, bounded automatic reconnect on
both sides, a connection state that only says "Connected" once the transport
is genuinely usable, and hardened MTU/message-size handling. TASK-006 adds
the **action library**: 27 structured, platform-aware actions (up from 5
hardcoded ones) across five categories, backed on Android by a static
registry (`actions/`) UI can filter/drive, and on macOS by a data-driven
`ActionRegistry` that replaced the old growing switch statement (see
[TASKS.md](TASKS.md)). TASK-007 replaces the temporary five-card home screen
with a three-page, icon-forward dashboard rendered from reusable widget and
logical-grid models, while retaining the complete Actions screen for
manual coverage of all 27 commands. TASK-008 turns that fixed dashboard into
an editable one: a coarser **6x4 grid**, an explicit **Edit Mode** with
drag/resize/add/remove, page create/rename/delete/reorder, a Daily Overview
widget, and JSON layout persistence with Reset to Default. TASK-009 adds the
return direction: the Mac now pushes **live CPU/RAM telemetry and Now Playing
metadata** to the tablet over the same BLE link, so the placeholder widgets
show real values. TASK-009A adds request-driven, resized album artwork with a
bounded binary BLE transfer and caches on both devices.

## Overall architecture

```
Android tablet (BLE central)
  ui/                      MainActivity + secondary screens
    └─ ui/dashboard/       ViewPager2, grid/page/widget renderers, edit gestures
         └─ dashboard/     UI-independent page/widget/6x4 grid data, editor,
                           serializer and layout store
              └─ live/     DesktopStateStore — one shared source of live
                           telemetry/media state (never persisted)
                   └─ actions/  ActionLibrary metadata + shared ActionDispatcher
                        └─ transport/ + ble/
                             BluetoothTransport, protocol messages and allowlist
                                  ⇅ BLE
macOS agent (BLE peripheral)
  App/                     menu-bar lifecycle and login item
    └─ BLE/                PeripheralManager
         └─ Protocol/      ProtocolMessage + AliControlAction IDs
              └─ Control/  ActionRegistry + platform-specific controllers
              └─ Live/     LiveStatePublisher — one 2 s sampler, wire messages
                   └─ Telemetry/  CPU + memory samplers (Mach APIs)
                   └─ Media/      Apple Music / Spotify providers + selection

Android kiosk/ and device/ remain independent supporting layers for immersive
mode, boot launch, keep-awake, and tablet-local settings/status.
```

Both sides implement the *same* protocol (identical UUIDs, identical JSON
message shape, identical action-name allowlist) independently in their native
language — Kotlin on Android, Swift on macOS — so a future Windows agent only
has to implement that same protocol again; nothing about it is macOS-specific.

## BLE architecture

- **Android tablet = BLE central/client.** It scans (filtered to the Ali
  Control service UUID), connects, writes commands, and subscribes to state
  notifications. See [`android/.../transport/BluetoothTransport.kt`](android/app/src/main/java/com/alicontrol/app/transport/BluetoothTransport.kt).
- **macOS = BLE peripheral/server.** It advertises the Ali Control service,
  exposes the command/state characteristics, and executes commands as they
  arrive. See [`macos/.../BLE/PeripheralManager.swift`](macos/Sources/AliControlMac/BLE/PeripheralManager.swift).
- This split (tablet-as-central) is what makes a peripheral-role helper on the
  desktop OS the whole trick — no pairing dialog, no extra hardware.

### Service / characteristic UUIDs

Defined once on the Android side ([`AliControlBleProfile.kt`](android/app/src/main/java/com/alicontrol/app/ble/AliControlBleProfile.kt))
and reused verbatim on macOS ([`AliControlBleProfile.swift`](macos/Sources/AliControlMac/BLE/AliControlBleProfile.swift)) —
unchanged since TASK-002/003, and TASK-005 does not touch them:

| Purpose | UUID |
|---|---|
| Ali Control service (advertised by macOS; scan filter on Android) | `7eb4052b-fc5f-436f-ae69-c3c9e7e9809f` |
| Command characteristic (Android writes, macOS receives) | `6544209f-88b6-432e-9db1-c672b7a31916` |
| State characteristic (macOS notifies, Android subscribes) | `71dbc91d-eb99-4a50-b875-c3f045f4ea91` |
| Client Characteristic Config descriptor (standard BLE; Android writes it, CoreBluetooth manages it automatically on the macOS side) | `00002902-0000-1000-8000-00805f9b34fb` |

### Message format & size safety

A minimal JSON-shaped envelope — hand-rolled on Android (no JSON library, to
stay lightweight), `Codable`-based on macOS (Foundation is already there, so
that's free). Both produce/consume byte-identical wire output, and neither
side depends on JSON key order:

```json
{"type":"command","action":"volume_up"}
{"type":"state","action":"volume_up","status":"ok"}
```

`action` on a state message echoes which command it's acknowledging (or is
absent for the unsolicited handshake sent right after subscribe:
`{"type":"state","status":"connected"}`). A failed command gets
`{"type":"state","action":"...","status":"error"}` instead of silence.

**MTU / never-truncate hardening (TASK-005):** every protocol message is
34-43 bytes, well past the un-negotiated default BLE MTU (23, i.e. 20 usable
bytes) — this is exactly what used to make every command arrive on macOS as
truncated, unparseable garbage before TASK-003's physical testing caught it.
`BluetoothTransport` now:
- negotiates a larger MTU (185) immediately on connect, tracking whatever the
  peripheral actually accepted;
- `send()` explicitly checks the message against the *actual negotiated* MTU
  before writing, and refuses (surfacing a transport error) rather than
  writing anything the stack might cut short — this holds even if MTU
  negotiation itself failed and the connection is still running at the
  original default;
- `ProtocolMessage.fromJson()` on Android additionally rejects any payload
  that isn't a complete, closed `{...}` object up front, so a
  hypothetically-truncated payload can never be misread as a smaller-but-valid
  message instead of failing outright.

### Live state protocol (TASK-009)

The desktop→tablet direction reuses the **same state characteristic** as the
command acknowledgements above — no new UUID, no change to MTU negotiation or
reconnect behaviour. Messages are told apart by their `type`, and each side
ignores the other family entirely (`LiveStateMessage` returns null for a
`state` ack; `ProtocolMessage` sees a `metrics` message as a type it doesn't
act on). Three new types:

```json
{"type":"metrics","cpu":34,"memory":48}
{"type":"media","available":true,"source":"spotify","playing":true,"position":83,"duration":214}
{"type":"track","title":"Song","artist":"Artist","album":"Album"}
```

An **omitted metric means unavailable**, never zero — that distinction is what
lets a widget choose between `0%` and `--`. GPU and temperatures are currently
always omitted by the macOS agent (see below), but the fields exist so a
future Windows agent needs no protocol change.

**Payload-size strategy.** Track metadata is the only unbounded content in the
protocol, and an oversized notification would be truncated by the stack —
possibly mid-UTF-8, producing invalid JSON. Two mechanisms together make that
impossible:

1. **Two semantic messages.** Playback state (small, changes every tick) is
   separate from track identity (larger, changes rarely, sent only when the
   track actually changes). The frequent message therefore carries no
   unbounded text at all.
2. **Bounded UTF-8 fields.** Title/artist/album are truncated to fixed
   *escaped-byte* budgets (48/36/26) on whole grapheme boundaries, so a
   multi-byte character or an emoji cluster is never cut in half, and a title
   full of quotes can't expand past the budget once escaped.

Every live message is designed to stay under **160 bytes** (verified by tests)
against the ~182 usable at the negotiated MTU of 185, and `PeripheralManager`
independently refuses to write any notification larger than the connected
central's own `maximumUpdateValueLength` — dropping one telemetry update is
always better than corrupting the stream, since another follows two seconds
later. Application-level fragmentation was deliberately **not** built: a
reassembly buffer with sequence numbers and timeouts is real BLE-reliability
risk for text that wouldn't fit a 7-inch dashboard tile anyway.

### Connection readiness & reconnection

**"Connected" means genuinely usable, not just GATT-linked.** Android's
`BluetoothTransport.ConnectionState` is:

`DISCONNECTED → SCANNING → CONNECTING → PREPARING → CONNECTED`

`PREPARING` covers MTU negotiation, service discovery, characteristic lookup,
and the state-characteristic notification descriptor (CCCD) write — the state
only advances to `CONNECTED` once `onDescriptorWrite` *confirms* that last
write actually succeeded, not merely once it was issued. A single 15s timeout
covers the whole `CONNECTING`+`PREPARING` window. Stale/late GATT callbacks
from an already-abandoned connection attempt (e.g. one that already timed
out) are guarded against explicitly (`isCurrentGatt`), so they can never
resurrect a `CONNECTED` state for a link nothing is using anymore.

**Android auto-reconnect (TASK-005):** if a *previously fully-connected* link
drops unexpectedly (not via the user's own Disconnect button), the transport
retries the same device directly by address — no scanning — up to 3 times
with increasing delays (2s, 5s, 10s). It gives up cleanly after that; manual
Connect (via the normal scan flow) is always available regardless. A manual
disconnect immediately cancels any pending reconnect and never triggers one.
If Bluetooth itself is off when a scheduled attempt would fire, that attempt
is skipped rather than wasted.

**macOS reconnect/power-cycle:** the peripheral side needed no structural
changes for this — it was already correct. Advertising is never stopped
except by an explicit Bluetooth power-off, so a fresh Android connection
attempt (after any drop, or a full reconnect) is picked up by the same
running `CBPeripheralManagerDelegate` callbacks
(`didSubscribeTo`/`didUnsubscribeFrom`) with no special-casing needed.
Toggling Mac Bluetooth off/on is handled by the existing
`peripheralManagerDidUpdateState` transitions (clears stale characteristic
references on `.poweredOff`, re-adds the service and re-advertises on
`.poweredOn`). System sleep/wake is not given its own explicit handler —
macOS's own Bluetooth stack reset around sleep/wake surfaces through these
same `CBManagerState` transitions, so the existing logic already covers it;
see Limitations for what still needs a physical Mac to actually confirm.
TASK-005 adds one explicit recovery action: **Restart Advertising** in the
menu bar, for the rare case advertising looks stuck without needing a full
relaunch.

### Action library (TASK-006)

The full, explicit allowlist — nothing outside this list is ever sent or
executed on either side (there is no generic/arbitrary command path, no
shell strings, no application paths supplied over BLE). Every action has
structured metadata, mirrored exactly on both platforms:

- **id** — the stable wire-protocol string (`AliControlCommands.kt` /
  `AliControlAction.swift`)
- **label** — Android-only display text
- **category** — `MEDIA` / `SYSTEM` / `APPS` / `WINDOW` / `PRODUCTIVITY`
- **supportedPlatforms** — `MACOS` and/or `WINDOWS` (Windows metadata exists
  now even though no Windows agent is implemented — see below)
- **dangerLevel** — `SAFE` (sent immediately) or `CONFIRM` (Android must ask
  first)
- **permissionRequirement** — `NONE` or `ACCESSIBILITY`

On Android this lives entirely in [`actions/ActionLibrary.kt`](android/app/src/main/java/com/alicontrol/app/actions/ActionLibrary.kt)
(static, in-memory, zero I/O) — Android never executes anything itself; the
library only describes what MAY be sent and lets UI filter by platform/
category. On macOS, [`Control/ActionRegistry.swift`](macos/Sources/AliControlMac/Control/ActionRegistry.swift)
independently re-validates every ID against its own allowlist before
executing anything — the Android-side metadata existing does not itself
grant the tablet the ability to run anything.

| ID | Category | Danger | Permission | macOS implementation |
|---|---|---|---|---|
| `ping` | *(protocol-only, not in the library)* | — | — | Acknowledged only |
| `play_pause` | MEDIA | SAFE | ACCESSIBILITY | Media-key `CGEvent` |
| `next_track` | MEDIA | SAFE | ACCESSIBILITY | Media-key `CGEvent` |
| `previous_track` | MEDIA | SAFE | ACCESSIBILITY | Media-key `CGEvent` |
| `volume_up` | MEDIA | SAFE | NONE | AppleScript `set volume` |
| `volume_down` | MEDIA | SAFE | NONE | AppleScript `set volume` |
| `mute_toggle` | MEDIA | SAFE | NONE | AppleScript `set volume` |
| `lock_screen` | SYSTEM | SAFE | NONE | `CGSession -suspend` |
| `sleep` | SYSTEM | **CONFIRM** | NONE | `pmset sleepnow` |
| `display_sleep` | SYSTEM | SAFE | NONE | `pmset displaysleepnow` (macOS only) |
| `screenshot` | SYSTEM | SAFE | ACCESSIBILITY | Simulated ⌘⇧3 |
| `show_desktop` | SYSTEM | SAFE | ACCESSIBILITY | Simulated F11 |
| `spotlight` | SYSTEM | SAFE | ACCESSIBILITY | Simulated ⌘Space (macOS only) |
| `mission_control` | SYSTEM | SAFE | ACCESSIBILITY | Simulated ⌃↑ (macOS only) |
| `open_terminal` | APPS | SAFE | NONE | `NSWorkspace`, `com.apple.Terminal` |
| `open_vscode` | APPS | SAFE | NONE | `NSWorkspace`, `com.microsoft.VSCode` |
| `open_finder` | APPS | SAFE | NONE | `NSWorkspace`, `com.apple.finder` (macOS only) |
| `open_browser` | APPS | SAFE | NONE | `NSWorkspace` default-browser lookup |
| `open_spotify` | APPS | SAFE | NONE | `NSWorkspace`, `com.spotify.client` |
| `open_system_settings` | APPS | SAFE | NONE | `NSWorkspace`, `com.apple.systempreferences` |
| `open_activity_monitor` | APPS | SAFE | NONE | `NSWorkspace`, `com.apple.ActivityMonitor` (macOS only) |
| `minimize_window` | WINDOW | SAFE | ACCESSIBILITY | Simulated ⌘M |
| `close_window` | WINDOW | SAFE | ACCESSIBILITY | Simulated ⌘W (closes window only, never force-quits) |
| `fullscreen_toggle` | WINDOW | SAFE | ACCESSIBILITY | Simulated ⌃⌘F |
| `copy` | PRODUCTIVITY | SAFE | ACCESSIBILITY | Simulated ⌘C |
| `paste` | PRODUCTIVITY | SAFE | ACCESSIBILITY | Simulated ⌘V |
| `undo` | PRODUCTIVITY | SAFE | ACCESSIBILITY | Simulated ⌘Z |
| `redo` | PRODUCTIVITY | SAFE | ACCESSIBILITY | Simulated ⌘⇧Z |

27 actions total (6 media + 7 system + 7 apps + 3 window + 4 productivity).
`play_pause`/`volume_up`/`volume_down`/`mute_toggle`/`open_terminal` are the
five that existed before TASK-006 — same IDs, same wire format, fully
backward compatible (`play_pause`'s permission classification changed from
"undetermined" to `ACCESSIBILITY`, an honest correction — see below — but the
protocol action ID itself is unchanged).

**Why 14 of the 27 need Accessibility:** every action beyond volume/mute
(built-in OS commands) and app launches (`NSWorkspace`, always unprivileged)
works by simulating a real keyboard shortcut via `CGEventPost` on whatever
app is currently frontmost — screenshots, Spotlight, Mission Control, window
management, and copy/paste/undo/redo all work this way, since macOS has no
lighter-weight public API for most of them. Posting synthetic keyboard events
system-wide is squarely what macOS's Accessibility gating covers, so all 14
are classified accordingly — this matches the task spec's own worked example
(`mission_control` → `ACCESSIBILITY`).

**Play/Pause reviewed (TASK-006 requirement):** the media-key `CGEvent`
mechanism predates this task, but it previously always reported success
regardless of whether Accessibility was actually granted. It's now correctly
classified as `ACCESSIBILITY`, and `ActionRegistry` checks real trust
(`AXIsProcessTrusted()`) *before* attempting it — if not granted, it now
honestly returns an error ack instead of a false "ok".

**Accessibility-gated actions fail safely, always** (verified by unit tests,
not just claimed): if Accessibility isn't trusted, `ActionRegistry.canExecute`
returns false *before* the controller method is ever called — no crash, a
clean error ack, the BLE connection stays healthy, and every unrelated (or
subsequent) command keeps working normally. Nothing in this codebase calls
`AXIsProcessTrustedWithOptions` with the prompt option — permission is never
requested automatically, and repeated attempts never escalate to a prompt.

**Unavailable-app behavior:** `open_vscode`/`open_spotify`/any app-launch
action looks up the app by bundle identifier via `NSWorkspace`; if it isn't
installed, the lookup returns `nil`, the handler returns `false`, and the ack
is a clean `error` — never a crash, never a fallback path.

**`open_browser` uses the real default browser** — `NSWorkspace`'s
`urlForApplication(toOpen:)` asks LaunchServices which app is registered for
the `https` scheme, so this works correctly whether the user's default is
Safari, Chrome, Firefox, Arc, or anything else, without ever hardcoding one.

**Known reliability caveat:** `show_desktop` (F11) and `mission_control`
(⌃↑) simulate macOS's *shipped default* keyboard shortcuts for those
features. If a user has remapped their own Mission Control shortcuts in
System Settings, these two actions won't do anything (no crash — the
keystroke is still delivered, just to a shortcut that no longer means
anything) — documented here as a known limitation rather than solved with a
heavier API.

**No shutdown/restart/logout** exist anywhere in the library, per the task's
explicit scope — `sleep` is the only `CONFIRM`-level action, and the
temporary Android Actions screen (below) actually gates it behind a
confirmation dialog, not just metadata that goes unused.

**Windows plug-in path:** a future Windows agent implements the exact same
action ID strings against its own OS APIs — nothing on the Android side or
in the protocol needs to change. `Platform.WINDOWS` already exists in the
metadata today specifically so the tablet app doesn't need another change
when that agent arrives; it just starts showing the Windows-relevant subset
once a Windows connection exists.

Defined identically in [`AliControlCommands.kt`](android/app/src/main/java/com/alicontrol/app/ble/AliControlCommands.kt)
and [`AliControlAction.swift`](macos/Sources/AliControlMac/Protocol/AliControlActions.swift)
(renamed from `AliControlCommand` as part of this task's refactor — an
internal Swift type name, not part of the wire protocol, so the rename has
no compatibility impact).

## Android

### UI

The home screen is a dedicated, multi-page dashboard. Its compact header keeps
connection state, **All Actions**, **Device**, **Edit**, and
Connect/Disconnect immediately reachable; `ConnectActivity` remains the
separate BLE scan/connect flow. Horizontal swipes move between pages, with the
current page name and a dot indicator making position clear. All five header
controls render through one shared `Widget.AliControl.HeaderButton` style, so
their height, padding, corner radius, icon size, text size, and visual weight
match by construction rather than by hand-matched attributes.

#### Grid and widget model

`dashboard/` is a UI-independent model. Each `DashboardPage` owns ordered
`DashboardWidget` values, and every widget carries a `WidgetPlacement`
(`x`, `y`, `width`, `height`) on one stable **6-column × 4-row logical grid**.
Those **24 cells are placement units, not 24 mandatory buttons** — pages may
leave cells empty, and the shipped layouts simply happen to fill theirs.
`x + width ≤ 6` and `y + height ≤ 4` always hold, and widgets never overlap.

Each `WidgetType` declares its own size limits, so a widget can never be
resized into a state where its content stops being usable:

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

Widgets render responsively for the cells they occupy: a 1×1 `ACTION` is an
icon over a one-word label, a 2×1 puts the full label beside the icon, and a
2×2 goes large. Text steps in size with the widget rather than shrinking until
it is unreadable.

#### Edit Mode

**Edit** switches the dashboard into an explicit editing mode: subtle 6×4 grid
guides appear, the header swaps to **Add / Pages / Done**, page swiping is
replaced by ‹ › arrows (so horizontal drags belong to the widget being moved),
and **no widget wires up its normal click handler at all** — a stray tap
cannot fire a remote action while rearranging.

- **Select** — tap a widget; it gains an accent border, a remove badge
  (top-right) and a resize handle (bottom-right).
- **Move** — drag it. The widget follows the finger via `translationX/Y` (no
  layout pass per touch event) and a preview rectangle shows the target cells,
  tinted by whether the drop is legal.
- **Resize** — drag the corner handle. Sizes snap to cells and are clamped to
  the type's min/max and the remaining space.
- **Remove** — tap the badge. This only removes the widget from that page; it
  never touches `ActionLibrary`, the macOS registry, or another page.

Illegal drops and resizes are **rejected, not corrected**: the widget keeps
the valid position it already had and a short message says why. All of that
validation lives in `DashboardEditor`, a pure Kotlin object with no Android
dependency — the view layer proposes a candidate, the model decides.

**Add** opens the widget picker (Action, Action Group, Metric, Gauge, Clock,
Daily Overview, Media, Slider, Shortcut), which labels any type that cannot
currently fit. New widgets take their type's default size at the first free
position scanning row-major from the top-left; nothing existing is ever moved
or dropped to make room, and a full page reports "Not enough space".

Adding an `ACTION` opens the **action picker**, generated entirely from
`ActionLibrary` and grouped by its existing categories, showing each action's
`ActionIconRegistry` icon plus Confirm/Accessibility badges. There is no
free-text field anywhere in the editor: an arbitrary action id or shell string
simply has no way in, and `DashboardEditor` rejects any id the library does
not know.

**Pages** manages pages: create (starts empty, named "New Page"), rename
(validated non-blank, capped at 24 characters, page id preserved), move
left/right, delete, and **Reset to Default**. The final remaining page can
never be deleted, and deleting a page that holds widgets asks first.

#### Persistence

Every successful edit is serialized to JSON and stored under a single key in
its own small `SharedPreferences` file (`dashboard_prefs`) — no Room, no SQL,
no cloud. The document carries a `schemaVersion`, and deserialization is
strict and total: bad JSON, an unknown schema version, an unknown widget type,
a widget outside the grid or breaking its type's size limits, overlapping
widgets, duplicate ids, or an action id this build does not know all return
null, and `DashboardStore` falls back to the shipped default. A corrupted
layout therefore degrades to "you get the defaults back", never to a crash.

A stored layout always wins over the shipped default, so a future release's
new default layout will not silently overwrite a customized dashboard.
**Reset to Default** clears only that one key — keep-screen-awake and every
other preference are untouched.

#### Default pages

`DefaultDashboardFactory` supplies three shipped pages as data (the model
itself has no notion of "three"):

- **Dashboard** — a 3×2 **Daily Overview** panel; CPU/GPU/RAM placeholders;
  Lock, Screenshot and a Device shortcut; a row of six compact 1×1 actions
  (Terminal, VS Code, Spotlight, Mission Control, Finder, Browser); and two
  compact 3×1 control strips (⏮ ▶ ⏭ and Vol− Mute Vol+). The full-size media
  player that TASK-007 put here is gone — it belongs on the Media page.
- **Media** — the full 6×3 `MEDIA` widget, plus volume down/up, mute, Spotify
  and a clock.
- **Dev** — VS Code, Terminal, Browser, Spotlight, Finder, Activity Monitor,
  Screenshot, System Settings, an All Actions shortcut, and CPU/RAM/GPU
  placeholders.

**Daily Overview** shows real local tablet time and date (updated on minute
boundaries, never on a high-frequency timer) alongside explicit
`Weather unavailable`, `No calendar data`, and `Next event: --` placeholders.
No weather or calendar integration exists yet (TASK-015); showing
plausible-looking values in the meantime would be worse than showing none.

#### Actions, connection, and platform

Every remote action still resolves its ID through `ActionLibrary` and is sent
through the shared `ActionDispatcher` and existing `TransportManager`, so
danger metadata still controls confirmation dialogs — a `sleep` widget added
to a custom page still asks before sending. Remote widgets dim and disable
while disconnected, while metrics, clocks, Daily Overview and local
Device/Settings access remain usable. **Editing works entirely offline**: it
involves no transport at all, so the dashboard is fully customizable with the
Mac switched off.

The current agent is macOS, so widgets whose actions do not support the
connected platform render **disabled and stay in the saved layout** — never
silently removed. A future Windows agent only needs a real
connected-platform signal in place of today's constant.

Action icons come from one `ActionIconRegistry` backed entirely by small local
vector drawables. The slider shell still has no thumb or value, and nothing
anywhere fabricates a reading: a value the desktop cannot measure renders as
`--`, not as a plausible-looking number.

#### Live desktop state (TASK-009)

`live/DesktopStateStore` is the single, application-scoped source of live
data. It is created once by `AliControlApp` and owns **one** BLE listener for
the whole app. Widgets never subscribe to the transport, never parse a
payload, and never own a timer — they register a small update closure
(`LiveWidgetBindings`) while being built, and the store's state is pushed
through those closures. That is what makes a CPU tile on the Dashboard page
and another on the Dev page show the same number: they are two renderings of
one value, not two subscriptions.

`DesktopState` is deliberately **separate from the dashboard layout model**.
Layout is user configuration that is persisted to `dashboard_prefs`; live
telemetry is volatile and is never written anywhere. Nothing about TASK-008's
schema changed for TASK-009 — a METRIC widget still stores only its label
("CPU"/"GPU"/"RAM"), and `MetricType.forLabel` resolves that to a reading at
render time, so there was no migration and no telemetry leaking into a saved
layout.

The store also merges the two media messages: playback updates carry no text
and must not wipe the track metadata a previous `track` message delivered,
while an `available:false` update clears both, so a title can never sit next
to "unavailable".

Rendering:

| Widget | Live behaviour |
|---|---|
| METRIC | `34%` / `48%`, or `--` when the desktop can't measure it |
| GAUGE | Same value plus a flat bar whose fill is a layout weight — one cheap layout pass every 2 s, no animation, no chart library |
| MEDIA | Real title, `Artist — Album`, a `1:23 ▁▁▁▃▁▁ 3:34` progress row, and a play/pause glyph that follows the real playback state |

Media transport buttons are **unchanged**: they still send `previous_track`,
`play_pause` and `next_track` through `ActionLibrary` and the shared
`ActionDispatcher`. Live metadata is a display channel only; TASK-009 added no
second media-control path.

**Disconnect** clears everything — metrics return to `--`, media returns to
`Media unavailable` — and connecting also clears, so state from a previous
session is never inherited. **Reconnect** simply resumes: the Mac restarts its
sampler on re-subscribe and the widgets recover without the dashboard being
rebuilt or the app restarted. Editing, page management and Device/Settings all
keep working while disconnected.

Data that stops arriving while the link still looks up is expired after 10 s
(five missed samples) by a single app-wide check, so a stalled desktop can't
leave a stale CPU figure on screen pretending to be current.

**Actions screen (TASK-006, temporary):** `ActionsActivity` lists every
action from `ActionLibrary` that supports macOS, grouped by category, built
at runtime by iterating the registry rather than hand-authored per action —
that's the whole point of having structured metadata. Rows reuse the
existing plain-row style already used for the BLE device list; there's no
new design system here on purpose. Tapping a `SAFE` action sends it
immediately; tapping `sleep` (the only `CONFIRM` action) shows an
`AlertDialog` first. It remains available through **All Actions** — TASK-008's
action picker covers adding actions to the dashboard, but this screen is still
the fastest way to exercise all 27 commands in one place.

### Kiosk / immersive fullscreen

`kiosk/KioskController` (not any Activity) owns this. Every screen
(`MainActivity`, `ConnectActivity`, `DeviceSettingsActivity`, `ActionsActivity`) hides the status
and navigation bars via `View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY` +
`SYSTEM_UI_FLAG_FULLSCREEN`/`HIDE_NAVIGATION`, reapplied from both `onResume()`
and `onWindowFocusChanged(true)` — the standard two-hook pattern, since a
status-bar swipe changes window focus without a full pause/resume cycle. On
`MainActivity` (the app's home screen) `onBackPressed()` is a no-op, so the
back button can't exit to the launcher. Unchanged from TASK-004; preserved and
reverified for TASK-005.

This is **application-level** kiosk behaviour only — it hides the system bars
and stops back-press from exiting, but it cannot block the physical/soft
Home or Recents buttons. That requires Device Owner provisioning, which is
explicitly out of scope (see Limitations).

**Development escape hatch:** press and hold the screen title / connection
status text for 3 seconds (checked on release, not mid-hold) to reveal the
system bars via `KioskController.exitImmersiveMode()`, giving access to
Home/Recents/Settings. It's self-healing — the next `onResume`/window-focus
event on any screen reapplies immersive mode automatically, so there's no
separate "re-lock" step.

### Launch on boot

`kiosk/BootReceiver` listens for `BOOT_COMPLETED` and starts `MainActivity`
directly — no service, no polling, nothing left running afterwards.
`BOOT_COMPLETED` is one of the broadcasts explicitly exempted from Android 8's
implicit-broadcast manifest restrictions, so a plain manifest-declared
receiver still works on API 27+ without a foreground-service workaround.
Boot-launch is currently unconditional (no in-app toggle to disable it) — see
Limitations for real-world caveats.

### Device / Settings screen

`DeviceSettingsActivity`, reached from the ⚙ button. All device-control logic
lives in `device/` (`BrightnessController`, `VolumeController`,
`BatteryProvider`, aggregated by `DeviceController`), not in the Activity:

| Setting | Behaviour |
|---|---|
| Wi-Fi | Button opens `Settings.ACTION_WIFI_SETTINGS`. No custom credential storage. |
| Bluetooth | Shows on/off (reusing `BluetoothTransport.isBluetoothEnabled` — the existing connection flow is untouched); button opens `Settings.ACTION_BLUETOOTH_SETTINGS`. |
| Brightness | In-app slider (0-100%), writing `Settings.System.SCREEN_BRIGHTNESS`. Disabled with a "Grant permission" button when `WRITE_SETTINGS` isn't held — see Permissions. |
| Tablet volume | In-app slider over `AudioManager.STREAM_MUSIC` — entirely separate from the BLE volume_up/volume_down commands, which control the *remote* Mac. |
| Keep screen awake | Switch, persisted in `SharedPreferences`; each Activity applies/clears `FLAG_KEEP_SCREEN_ON` on its own window in `onResume()`, since the flag doesn't carry across windows automatically. |
| Battery | Read-only percentage. Refreshed once when the screen opens and then live via a registered `ACTION_BATTERY_CHANGED` receiver (unregistered in `onPause`) — event-driven, not a poll loop. |

### Requirements

- Kotlin, XML Views (no Jetpack Compose)
- `minSdk` 27 / `targetSdk` 27 (Android 8.1), `compileSdk` 33
- Gradle 8.4, Android Gradle Plugin 8.1.4, Kotlin 1.9.22
- Target hardware: Alcatel 1T 7 8068 (MediaTek MT6580, `armeabi-v7a`, 7", low-end)
- BLE GATT calls deliberately use the pre-API-33 APIs (`characteristic.value`
  + `writeCharacteristic(characteristic)`, the 2-arg `onCharacteristicChanged`/
  `onDescriptorWrite` callbacks, etc.) since the newer byte-array overloads
  don't exist on API 27 devices — these carry `@Suppress("DEPRECATION")`.
- Every Android API used across the implemented tasks (`Settings.System.canWrite`,
  `ACTION_MANAGE_WRITE_SETTINGS`, `BatteryManager.BATTERY_PROPERTY_CAPACITY`,
  `AudioManager` stream volume, `FLAG_KEEP_SCREEN_ON`, `SharedPreferences`,
  manifest `BOOT_COMPLETED`, `BluetoothGatt.requestMtu`/`onMtuChanged`/
  `onDescriptorWrite`, `Log`) has existed since at most API 23 — nothing in
  this codebase requires anything newer than API 27. The dashboard editor adds
  only long-standing APIs: `MotionEvent`, `ViewConfiguration.scaledTouchSlop`,
  `View.translationX/Y`, `Canvas.drawRoundRect`, `GradientDrawable`,
  `ImageView.imageTintList` (API 21), and `org.json`, which ships inside
  Android itself.
- Runtime dependencies are `androidx.core`/`appcompat`, Material Components,
  and `androidx.viewpager2` — no drag-and-drop, dashboard, charting, or
  database framework. `org.json:json` is a **test-only** dependency, present
  solely because the unit-test `android.jar` stubs `org.json` out; it is not
  in the APK.

### Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

Unchanged since TASK-004 — TASK-005 added no new Android permissions (the
reconnect and MTU-safety logic use only APIs already covered above).
`targetSdk` 27 is below the API 31 cutoff where `BLUETOOTH_SCAN` /
`BLUETOOTH_CONNECT` replace the classic permissions, so those two are granted
at install time — no runtime prompt. `ACCESS_COARSE_LOCATION` **is** dangerous
on API 23+ (BLE scans silently return nothing without it); `ConnectActivity`
requests it at runtime. `WRITE_SETTINGS` is a *special* permission the user
must explicitly grant on its own system screen; the brightness slider
degrades to disabled (not a crash) if it isn't held. No Accessibility, no
Device Admin/Owner, no network/server permission anywhere in this app.

The APK also carries an auto-injected, self-referencing
`com.alicontrol.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — the Android
Gradle Plugin adds this automatically for any app that calls
`registerReceiver()` in code (the battery-change listener) when built against
`compileSdk` 33. It's a build-tooling artifact, not something the user grants
or something declared directly.

### Build

Requires JDK 17 and the Android SDK (platform 33, build-tools 33.0.2). From
`android/`:

```bash
./gradlew clean assembleDebug testDebugUnitTest   # debug APK + unit tests
./gradlew assembleRelease                         # unsigned release APK
```

- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Unsigned release APK: `android/app/build/outputs/apk/release/app-release-unsigned.apk`

The release build has no signing config and produces an unsigned APK — no
keystore or private credentials exist in this repo or are needed to build it.
An unsigned APK can be installed via `adb install` (which accepts debug-signed
or unsigned local builds) but would need to be signed before any other
distribution path. `lint`'s `ExpiredTargetSdkVersion` check (a Google Play
submission gate requiring targetSdk 31+) is explicitly disabled in
`app/build.gradle` — targeting API 27 is this project's deliberate
requirement for the Alcatel tablet, not an oversight, and this app is never
distributed via Play.

Install/update on the tablet (connected via USB, USB debugging enabled):

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Android 8.1 limitations

- **No true OS-level lockdown.** Without Device Owner provisioning (out of
  scope), the app cannot block the physical/soft Home or Recents buttons —
  only the in-app back button (on the home screen) and the system bars
  themselves are addressable at the application level.
- **Boot-launch reliability varies by OEM.** Some Android skins (common on
  budget/MediaTek tablets) restrict `BOOT_COMPLETED` delivery or require the
  user to manually allow "autostart" for the app in vendor-specific battery/
  autostart-management settings, outside anything this app can control.
- **Boot-launch has no in-app toggle.** It's unconditional in this project's
  scope — turning it off means uninstalling or disabling the app.
- **`WRITE_SETTINGS` is a manual, per-app grant** on the system settings
  screen — it cannot be pre-approved at install time on any Android version.

## macOS

### Real application bundle (TASK-005)

`AliControlMac.app` — a genuine, double-clickable macOS application bundle,
replacing the earlier `swift run` + linker-embedded-`Info.plist` workaround.
The Swift Package structure is unchanged (still the source of truth, still
buildable/testable with `swift build`/`swift test` for fast dev iteration);
[`Scripts/build_app.sh`](macos/Scripts/build_app.sh) assembles the built
executable into a real bundle with its own `Contents/Info.plist`:

```bash
cd macos
./Scripts/build_app.sh          # release build (default) -> dist/AliControlMac.app
./Scripts/build_app.sh debug    # debug build instead
```

This runs `swift build -c release`, copies the resulting binary and
`Info.plist` into `dist/AliControlMac.app/Contents/{MacOS,Info.plist}`, and
ad-hoc code-signs it (`codesign --sign -`) — no certificate, no Apple
Developer account, nothing to create or commit. Verified output: a real
`APPL`-type bundle, arm64 (Apple Silicon) Mach-O executable, valid ad-hoc
signature, `LSUIElement` set (menu-bar only, no Dock icon), and
`NSBluetoothAlwaysUsageDescription` present.

**Local build vs. a future distributable build:** this is a *local development
build* — ad-hoc signed, arm64-only, meant for running and testing on the Mac
that built it. It is not notarized and has no Developer ID, so Gatekeeper
would warn (or block, depending on how it's transferred to another Mac) if
copied elsewhere and run there. Producing a distributable build (Developer ID
signing + notarization, and optionally a universal x86_64+arm64 binary) is
future work, deliberately out of scope here — this task only required "a
normal `.app` build" suitable for local testing, which is what this is.

### Menu bar behavior

Runs as a background-only menu-bar app (`LSUIElement` in `Info.plist`, plus
`NSApplication.setActivationPolicy(.accessory)` as a belt-and-suspenders
runtime fallback for the bare `swift run` dev path) — no Dock icon, no
persistent window. The menu shows, refreshed on every real status change (via
`CBPeripheralManagerDelegate` callbacks, not polling):

```
Ali Control
Bluetooth: On / Off / Permission needed / Not supported
Tablet: Not connected / Connected (12AB34CD)
Advertising: Yes / No
──────────
Open at Login          [checkbox, refreshed each time the menu opens]
Restart Advertising
──────────
Quit
```

The tablet's connected identifier is CoreBluetooth's `CBCentral.identifier`
(shortened) — `CBCentral` has no public `.name` property, so this is the
closest thing to a "device name" the peripheral role can show; a real Android
device name is simply not available here (a genuine, documented CoreBluetooth
API limitation, not a bug). "Disconnect" was considered (the task listed it as
optional) but was left out: CoreBluetooth's peripheral role has no clean
public API for a peripheral to force-disconnect a specific connected central,
so a "fake" version wasn't worth adding.

### Open at Login

Implemented via `LoginItemController` using `ServiceManagement`'s
`SMAppService.mainApp` (macOS 13 Ventura+ — already this package's minimum
deployment target, so no separate version guard exists). Toggled from the
menu item, checked fresh each time the menu opens. This mechanism has no
shell script, no LaunchAgent/LaunchDaemon plist, and no elevated privileges —
it registers the app bundle itself to relaunch at login, the same thing
System Settings → General → Login Items shows for any app using this API.
It only works because the app is now a real `.app` bundle (see above) —
`SMAppService.mainApp` does not apply to a bare executable.

This code was never invoked during implementation/testing — toggling it
changes a real macOS login item, which is host system state this task
explicitly should not modify automatically. Whether `register()` actually
succeeds for a locally ad-hoc-signed (not Developer-ID-signed) build, and
whether the app truly relaunches after a real reboot, needs physical
verification — see Limitations.

### BLE automatic startup

Unchanged in substance from TASK-003/004 — it was already fully automatic and
event-driven, which is exactly what TASK-005 asked to preserve/confirm:
`PeripheralManager.init()` creates the `CBPeripheralManager` the moment
`AppDelegate.applicationDidFinishLaunching` runs; `peripheralManagerDidUpdateState`
registers the service and starts advertising itself as soon as Bluetooth
reports `.poweredOn`. There is no "Start Server" button and never was one —
nothing to click, nothing to poll for readiness.

### Live telemetry (TASK-009)

One `LiveStatePublisher` samples every **2 seconds** and runs **only while a
tablet is subscribed** — `PeripheralManager` starts it on `didSubscribeTo` and
stops it on `didUnsubscribeFrom` and on Bluetooth power-off. An unattended Mac
does no telemetry work at all: no timer, no Mach calls, no Apple events. The
interval is one constant (`LiveStatePublisher.defaultInterval`).

Sampling runs on a dedicated serial queue, deliberately not the main queue, so
an unresponsive media application can never stall CoreBluetooth's delegate
callbacks and delay a command acknowledgement. (`NSAppleScript` is not
thread-safe, so every scripting call is made from that one queue and never
concurrently.)

| Metric | Source | Status |
|---|---|---|
| **CPU** | `host_statistics(HOST_CPU_LOAD_INFO)` — one Mach call, no subprocess | Available. Usage is the busy share (user + system + **nice**) of the ticks elapsed since the previous sample, so the first sample after start has no baseline and reports nothing. |
| **RAM** | `host_statistics64(HOST_VM_INFO64)` | Available. See the definition below. |
| **GPU** | — | **Unavailable by design** |
| **CPU/GPU temperature** | — | **Unavailable by design** |

**How RAM "used" is calculated.** This mirrors Activity Monitor's *Memory
Used*, so the tablet's number matches what the user can verify on their own
screen:

```text
used     = (internal_page_count − purgeable_count)   // app memory
         + wire_count                                // wired
         + compressor_page_count                     // compressed
percent  = used × vm_page_size / ProcessInfo.physicalMemory × 100
```

Purgeable pages are subtracted because the kernel can reclaim them on demand.
File-backed cache (`external_page_count`) is excluded — macOS deliberately
fills spare RAM with cache, so including it would pin the reading near 100% on
every healthy machine. This is a *usage* figure, not memory *pressure*;
presenting one as the other would be misleading.

**Why GPU and temperatures stay `--`.** macOS exposes no public API for
either. `powermetrics` needs root and a process per sample; GPU "Device
Utilization %" lives in undocumented `IOAccelerator`/`AGXAccelerator`
IORegistry keys Apple has already renamed across Apple Silicon generations;
Apple Silicon thermal sensors moved behind the private
`IOHIDEventSystemClient`, with per-chip key layouts. Every one of those is
either root-only, private, or fragile — exactly what TASK-009 rules out — so
these stay unavailable rather than becoming a number that looks real. The
model and wire format still carry the fields, so a Windows agent (where GPU
counters *are* public) can populate them with no change on either side.

### Now Playing (TASK-009)

A provider architecture over the two supported local players:

| Application | Bundle id | Fields |
|---|---|---|
| **Apple Music** (`Music.app`) | `com.apple.Music` | title, artist, album, playback state, position, duration |
| **Spotify** | `com.spotify.client` | title, artist, album, playback state, position, duration |

Both use one read-only AppleScript query per tick that reads properties and
returns a separator-delimited string — it never starts, pauses or changes
playback. Numbers are rounded to integers *inside* AppleScript, because a real
number would serialize with the system decimal separator and break parsing
under a comma-decimal locale. Spotify reports duration in milliseconds and
Apple Music in seconds; both are normalized to whole seconds.

Two behaviours matter:

- **A non-running app is never scripted.** `tell application "Spotify"` would
  *launch* Spotify. `NSWorkspace`'s running-application list (no permission,
  cannot launch anything) is checked first, every time.
- **Automation denial is latched.** If the user declines the prompt, macOS
  returns `errAEEventNotPermitted` forever after; the provider records that
  and stops issuing Apple events for the rest of the process's life, so a
  2-second sampler can't become a permission-prompt loop.

**Selection policy** (evaluated fresh each sample, no user choice required):

1. only running applications are consulted at all;
2. a player that is actually *playing* wins — and if more than one is, the
   source chosen last time keeps priority, which is what stops two open
   players from fighting over the widget;
3. otherwise the previously chosen source keeps the widget as long as it still
   has a track loaded (pausing shouldn't hand the display to an idle app);
4. otherwise the first source in priority order with a track — Apple Music,
   then Spotify;
5. otherwise nothing: the tablet shows "Media unavailable".

The sticky preference lives only in memory and is reset when telemetry stops,
so a reconnect re-evaluates from scratch.

### Media artwork over BLE (TASK-009A)

Artwork remains local and request-driven. Apple Music exposes the bytes of its
current artwork through its local AppleScript dictionary, so the provider reads
those bytes only when the track identity changes. Spotify's local dictionary
exposes only a remote artwork URL; because this project does not use HTTP,
cloud APIs, private cache paths, or the Spotify Web API, Spotify metadata works
normally and its artwork honestly remains unavailable.

The Mac center-crops artwork to a square, resizes it to **160×160**, and encodes
JPEG at quality **0.60**, then **0.40**, then **0.25** until it is at most
**20 KiB**. If it still cannot meet that ceiling it is skipped. `artworkId` is
the lowercase hexadecimal form of the first eight bytes of SHA-256 over the
*final compressed bytes*, making it stable across tracks that share the same
image and independently verifiable on Android. Processed results and confirmed
no-art outcomes are held in an 8-entry LRU cache, so neither AppleScript nor
JPEG work repeats for the same track.

Track metadata carries only that ID. Android checks its own memory cache and
sends the following closed request only on a miss:

```json
{"type":"artwork_request","artworkId":"0123456789abcdef"}
```

The Mac only answers IDs already present in its processed-artwork cache. The
request cannot name a path, filename or URL. A transfer consists of an
`artwork_begin` JSON envelope, ordered binary chunks, and an `artwork_end`
envelope. Each binary frame is:

```text
0xAC | 0x01 | artworkId (8 raw bytes) | chunk index (uint16 big-endian) | JPEG bytes
```

The 12-byte header is subtracted from the subscribed central's real
`maximumUpdateValueLength` (with the existing 160-byte notification ceiling),
and every control and binary frame is checked against that negotiated limit.
CoreBluetooth `false` backpressure pauses immediately with the rejected frame
still at the head; sending resumes only from
`peripheralManagerIsReady(toUpdateSubscribers:)`. Command acknowledgements and
live state use the high-priority queue and always drain before artwork.

Android validates ID, MIME type, declared size (maximum **20 KiB**), chunk
count (maximum **512**), exact sequential indices, accumulated byte count and
the final content hash before decoding. It allocates one declared-size receive
buffer, decodes only completed JPEGs after a bounds pass, uses `RGB_565`, and
keeps at most 8 bitmaps / 1 MiB decoded memory in an access-ordered LRU cache.
A disconnect or different artwork ID discards the partial buffer; cached
completed artwork survives reconnect. The widget only renders a bitmap whose
ID still matches current metadata, shows artwork in medium/large MEDIA widgets,
keeps the polished music-note placeholder when missing, and leaves the compact
Dashboard action group unchanged.

Artwork/state sampling runs on the existing serial live-state queue. The media
provider re-reads track identity after artwork retrieval; if the track changed
mid-fetch, the old bytes and old metadata are both discarded and retried on the
next normal tick. A confirmed new artwork identity also cancels a queued older
transfer before the new track notification is sent.

### Permissions

| Permission | Why | Requested how |
|---|---|---|
| **Bluetooth** (`NSBluetoothAlwaysUsageDescription`) | `CBPeripheralManager` needs it to advertise/accept connections at all | macOS prompts automatically the first time the app runs; declared in the bundle's real `Contents/Info.plist` (see packaging above — no longer a linker-embedded workaround) |
| **Automation** (`NSAppleEventsUsageDescription`) | Reading Now Playing metadata from Music/Spotify (TASK-009) | macOS prompts **once**, the first time the agent asks a *running* media app what's playing. Declining is handled gracefully: that provider is never asked again, the media widget stays unavailable, and telemetry plus all 27 actions keep working normally. |

No Screen Recording, no Full Disk Access, no root, and nothing that requires
an entitlement. **Accessibility is never requested anywhere in this code** (no
`AXIsProcessTrustedWithOptions` call, no `AXUIElement` usage) —
`AccessibilityChecker` only ever *reads* `AXIsProcessTrusted()`.
`Open at Login`'s `SMAppService` needs no extra entitlement or
usage-description key for the `mainApp` variant used here.

14 of the 27 actions (see the action library table above) *behave*
differently depending on whether the user has separately granted
Accessibility to this app in System Settings — volume/mute/lock/sleep/app
launches are entirely unaffected either way. If Accessibility isn't granted,
those 14 fail with a clean error ack instead of doing nothing silently or
crashing; granting Accessibility to the agent manually (this app never asks)
is what to check if e.g. Screenshot or Copy has no effect.

### Logging

Concise, event-driven `stderr` lines only — Bluetooth state transitions,
advertising start, tablet connect/disconnect, unparseable payloads, and
command execution failures. Nothing is logged on a timer or loop, and nothing
sensitive (no protocol payload content beyond the bare, already-non-sensitive
JSON action strings) is logged. This is deliberately lightweight: a menu-bar
helper, not a service with a real structured-logging story.

### Build & test

```bash
cd macos
swift build          # fast dev build (or: xcodebuild -scheme AliControlMac -destination 'platform=macOS' build)
swift test            # unit tests (or: xcodebuild -scheme AliControlMac -destination 'platform=macOS' test)
./Scripts/build_app.sh   # the real, distributable-for-local-testing .app — see above
```

### Run

Normal use — **no Terminal, no `swift run`, no Xcode required**:

```bash
open dist/AliControlMac.app
```

(Or double-click it in Finder.) The menu bar icon appears (○ Bluetooth off ·
◐ advertising, waiting for the tablet · ● a tablet is connected · ⚠︎
permission/support problem). Click it for status and actions.

## Manual end-to-end test

Not performed automatically — requires the physical tablet and a Mac in the
same room:

1. Install the Android APK (see above) and launch Ali Control on the tablet.
2. Build and launch `AliControlMac.app` normally (`open dist/AliControlMac.app`,
   not `swift run`) — grant the Bluetooth permission prompt if shown.
3. Confirm the menu bar item appears and reaches ◐ (advertising) on its own.
4. On Android, tap **Connect** — grant the location permission prompt if this
   is the first run — and select the Mac from the scan list.
5. Confirm Android reaches a true **Connected** state (not stuck on
   "Preparing…") and the macOS menu bar icon reaches ●, showing the tablet's
   identifier.
6. Verify Dashboard, Media, and Dev render correctly on the 6×4 grid; swipe
   repeatedly through them and confirm the page name/dots follow without lag or
   crashes. Confirm the Dashboard page has no full-size media player, keeps the
   compact ⏮ ▶ ⏭ and Vol−/Mute/Vol+ strips, and shows Daily Overview with real
   time/date and `Weather unavailable` / `No calendar data` / `Next event: --`.
   Confirm All Actions, Device, Edit and Connect are the same height and style.
7. Test the dashboard actions, including media transport, volume/mute, Lock,
   Screenshot, Terminal, VS Code, Spotlight, Mission Control, Spotify, Browser,
   Finder, Activity Monitor, and System Settings. Confirm CPU/GPU/RAM remain
   honest `--` placeholders and media remains `Media unavailable`.
7b. Tap **Edit** and exercise the editor: confirm the 6×4 guides appear and
   normal taps no longer fire actions; move a 1×1 action and confirm it snaps;
   drag one onto an occupied cell and confirm the drop is rejected with the
   widget unmoved; resize a widget via the corner handle and confirm min/max
   limits hold; remove a widget; add an Action (choosing from the action
   picker), a Daily Overview, and a Media widget, confirming placement in free
   space and a "Not enough space" message on a full page; create, rename,
   reorder and delete a page; confirm the last page cannot be deleted; tap
   **Done**. Relaunch the app (and reboot the tablet) and confirm the layout
   persists. Finally use **Pages → Reset to default**, confirm the shipped
   layout returns and that keep-screen-awake is unaffected.
8. On Android, tap **All Actions** to open the Actions screen and test the rest —
   in roughly ascending risk order, **saving Sleep for last** since it
   interrupts the session:
   Next Track, Previous Track, Lock Screen, Display Sleep, Screenshot, Show
   Desktop, Spotlight, Mission Control, VS Code, Finder, Browser (confirm it
   opens the *actual* default browser, not a hardcoded one), Spotify, System
   Settings, Activity Monitor, Minimize Window, Close Window (confirm the
   app itself stays running), Fullscreen Toggle, Copy, Paste, Undo, Redo,
   and finally Sleep (confirm the confirmation dialog appears first).
9. Verify an unavailable-app action (e.g. VS Code or Spotify if not
   installed on the test Mac) fails safely — a clean error toast, no crash.
10. Temporarily revoke Accessibility for `AliControlMac.app` (System Settings
   → Privacy & Security → Accessibility) and confirm an Accessibility-gated
   action (e.g. Copy) fails cleanly with an error ack, while Volume Up right
   after it still works — then re-grant and confirm Copy works again.
10b. **Live data (TASK-009).** Confirm CPU and RAM change from `--` to real
    values within a few seconds of connecting, and that CPU rises under load
    (e.g. `yes > /dev/null` in a Terminal, then Ctrl-C). Confirm GPU stays
    `--` and that no temperature value appears anywhere. Open Spotify or
    Apple Music, start a track, and grant the one-time Automation prompt:
    confirm title, artist and playback state appear, and position/duration
    advance roughly every 2 s. Use the tablet's Play/Pause, Next and Previous
    and confirm the widget follows (including the play/pause glyph). Confirm
    the Dashboard's compact controls and the Media page show the *same*
    state. Decline (or revoke in System Settings → Privacy & Security →
    Automation) and confirm media goes unavailable while CPU/RAM keep
    updating and every action still works.
11. Disconnect BLE and confirm remote dashboard widgets disable, CPU/RAM/GPU
    return to `--` and media returns to "Media unavailable", while the
    dashboard, Device/Settings, All Actions, and clock remain visible;
    confirm **Edit mode still works fully while disconnected** (move, resize,
    add, remove, page management, Reset); reconnect and confirm actions and
    live values recover without restarting the app.
11b. Swipe repeatedly between pages for a minute and confirm values stay
    consistent with no duplicate sampling; then leave both machines idle for
    several minutes and confirm `AliControlMac` stays near-idle in Activity
    Monitor and produces no repeating log output.
12. Force-quit `AliControlMac.app` (or use its Quit menu item) — confirm
    Android detects the disconnect immediately (no stale "Connected").
13. Relaunch the Mac app — confirm Android can reconnect (either the bounded
    auto-reconnect kicks in, or a manual Connect works) without restarting
    the tablet app.
14. Toggle Mac Bluetooth off and back on — confirm advertising recovers on
    its own.
15. Toggle tablet Bluetooth off and on — confirm Android recovers cleanly.
16. Reboot the tablet — confirm Ali Control still launches on boot.
17. If "Open at Login" is enabled, restart the Mac and confirm
    `AliControlMac.app` launches automatically.
18. Confirm no Terminal or Xcode process is needed anywhere in the above.
19. Open Activity Monitor while idle (nothing being pressed) — confirm CPU
    usage is effectively idle and memory usage is reasonable for a menu-bar
    helper.
20. On Android: open Device/Settings and exercise every control (brightness,
    tablet volume, battery, Wi-Fi/Bluetooth settings entry, keep-awake), the
    development escape hatch, and returning from Android Settings — confirm
    no crash anywhere.

## Limitations / assumptions requiring physical testing

Everything above the low-level `CBPeripheralManager`/`BluetoothGatt` API calls
has been verified by unit tests and clean builds on both sides (including the
real `.app` bundle's structure, signature, and architecture), but the
following still need the real tablet and a real Mac together to confirm:

- General BLE reliability under real conditions — actual discovery range/
  timing, the bounded auto-reconnect sequence actually recovering a real
  dropped link, multiple nearby BLE devices, and true disconnect/timeout
  behavior — is exercised by unit tests at the decision-logic level only.
- Whether `SMAppService.mainApp.register()` succeeds for this ad-hoc-signed
  (not Developer-ID-signed) local build, and whether the app truly relaunches
  after an actual reboot — never invoked during implementation, since that
  would modify real login-item system state.
- Whether any of the 14 Accessibility-gated actions actually need it on this
  specific Mac/macOS version — all degrade gracefully either way (unit-tested
  as the *logic* being correct), but the real system effect of e.g.
  Screenshot or Fullscreen Toggle can only be confirmed by looking at the Mac.
- Whether `show_desktop` (F11) and `mission_control` (⌃↑) actually trigger
  those features on the test Mac, given they depend on the user not having
  remapped the shipped-default Mission Control shortcuts.
- Whether `open_vscode`/`open_spotify` correctly fail safely (vs. correctly
  succeed) depends on what's actually installed on the specific test Mac —
  both paths are unit-tested, but only one is exercised on any given machine.
- Whether `open_browser` actually launches the currently-configured default
  browser (not just *a* browser) on the test Mac.
- Whether the real `.app` bundle's `NSBluetoothAlwaysUsageDescription` is
  picked up correctly by TCC on first launch (expected, now that it's a
  proper bundle rather than the old linker-embedded workaround, but
  unverified against the actual permission prompt).
- System sleep/wake behavior — expected to be covered by the same
  `CBManagerState` transitions already handled, but not exercised against a
  real sleep/wake cycle.
- Idle CPU/memory footprint in Activity Monitor — the architecture is
  event-driven with no polling/timers/loops on either side by code review,
  but has not been measured on a running instance.
- **Dashboard editing gestures.** All the layout *rules* (grid bounds,
  collisions, size limits, page CRUD, persistence, corruption fallback) are
  covered by pure-Kotlin unit tests, but the gestures that feed them — drag
  tracking, the drop preview, the corner resize handle, and the touch-target
  sizes they produce on a 7-inch 1024×600 screen — need a finger on the real
  tablet. The project has no instrumentation-test setup (no Robolectric, no
  `androidTest`), so nothing about the on-screen rendering of the dashboard is
  automatically verified.
- **Layout persistence across a real app restart/reboot.** The serializer and
  store are unit-tested against an in-memory implementation; the actual
  `SharedPreferences` read/write path on the tablet is not.
- **Live telemetry end to end.** The CPU/memory arithmetic, the wire format,
  the payload budget, provider selection and every disconnect/reconnect rule
  are unit-tested on both sides with fakes, and the memory sampler is
  exercised against the real kernel. What has *not* been observed is the full
  loop on real hardware: whether a real Mach CPU reading tracks Activity
  Monitor closely, and how the 2-second notification cadence behaves over a
  real BLE link alongside command traffic.
- **Automation prompt behaviour.** The denial path is unit-tested through a
  fake runner, but the real one-time macOS prompt (and what a real
  `errAEEventNotPermitted` looks like from Music vs. Spotify) has not been
  triggered — doing so would modify real TCC state on this machine.
- **Media metadata is bounded**, so a very long title/artist/album is
  truncated on the desktop before sending (48/36/26 escaped bytes). This is
  deliberate, not a bug: see the payload-size strategy above.
- **Media position updates every ~2 s** and is not interpolated on the tablet
  between updates. A smoother bar would need a per-widget animation timer,
  which TASK-009 explicitly rules out.
- **If MTU negotiation ever fails** and the link stays at the 23-byte default,
  live-state notifications are dropped by the size guard rather than sent
  truncated — widgets would simply stay at `--`. Commands are already
  unusable in that state (TASK-005 refuses to send them), so this is
  consistent, not a new failure mode.
