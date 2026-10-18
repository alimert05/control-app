# Android client

The tablet app: Kotlin, XML Views, no Jetpack Compose. See the [README](../README.md) for setup and
[ARCHITECTURE.md](ARCHITECTURE.md) for the protocol it speaks.

## Requirements

- Kotlin, XML Views (no Jetpack Compose)
- `minSdk` 27 / `targetSdk` 27 (Android 8.1), `compileSdk` 33
- Gradle 8.4, Android Gradle Plugin 8.1.4, Kotlin 1.9.22, **JDK 17**
- Target hardware: Alcatel 1T 7 (8068) — MediaTek MT6580, `armeabi-v7a`, 7", low-end
- BLE GATT calls deliberately use the pre-API-33 APIs (`characteristic.value` +
  `writeCharacteristic(characteristic)`, the 2-arg `onCharacteristicChanged` /
  `onDescriptorWrite`) since the newer byte-array overloads don't exist on API 27
  devices — these carry `@Suppress("DEPRECATION")`.
- Runtime dependencies are `androidx.core`/`appcompat`, Material Components and
  `androidx.viewpager2` — no drag-and-drop, dashboard, charting or database framework.
  `org.json:json` is a **test-only** dependency (the unit-test `android.jar` stubs
  `org.json` out); it is not in the APK.

## UI

The home screen is a dedicated, multi-page dashboard. Its compact header keeps connection
state, the **profile switcher** (`[ Default ▾ ]`, see [Dashboard profiles](#dashboard-profiles)),
**All Actions**, **Device**, **Edit** and Connect/Disconnect immediately reachable;
`ConnectActivity` is the separate BLE scan/connect flow. Horizontal swipes move between
pages, with the page name and a dot indicator. The header shows the PC's own name once the
agent has announced it.

### Grid and widget model

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

### Edit Mode

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

### Persistence

Every successful edit is serialized to JSON in its own small `SharedPreferences` file
(`dashboard_prefs`) — no Room, no SQL, no cloud. Two keys live in that file:

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

**Saved-layout migration (action ids).** Early builds were developed against a macOS desktop
agent (since removed) and saved dashboards (schema 1) that may reference four action IDs which
no longer exist. Those are rewritten to their Windows equivalents in every widget kind that
references actions *before* validation, so no widget is lost:

| Old ID (schema 1) | Becomes |
|---|---|
| `spotlight` | `windows_search` |
| `mission_control` | `task_view` |
| `open_finder` | `open_explorer` |
| `open_activity_monitor` | `open_task_manager` |

Positions, sizes, widget ids, pages and order are untouched. This applies to every dashboard
that reaches a profile — through the legacy-layout migration or embedded in a profile
document. Strictness is preserved: a schema-2 document naming a retired or unknown ID, or a
schema-1 document naming an unknown ID, is still rejected, and the desktop agent never accepts
the old IDs.

### Default pages

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
the Default profile contains exactly these pages, and *Reset to Default Layout* restores them.

**Daily Overview** shows real local tablet time and date (updated on minute boundaries)
beside explicit `Weather unavailable`, `No calendar data`, `Next event: --` placeholders —
weather and calendar integrations are planned but not implemented, and plausible-looking fake
values would be worse than none.

### Actions, connection, and platform

Every remote action resolves through `ActionLibrary` and the shared `ActionDispatcher` and
`TransportManager`, so danger metadata still controls confirmation dialogs (a `sleep`
widget added to a custom page still asks). The connected platform is whatever the agent
announced (see [capabilities](ARCHITECTURE.md#desktop-identity-and-capabilities)). Remote widgets dim and
disable while disconnected; metrics, clocks, Daily Overview and local Device/Settings
access remain usable. Action icons come from one `ActionIconRegistry` of local vector
drawables. A value the desktop cannot measure renders as `--`.

### Live desktop state

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

## Dashboard profiles

A **profile** is a complete, independently customizable dashboard — one "mode" of the
tablet:

```
DashboardProfile  (id · name · order · builtIn)
└── Dashboard     ← the dashboard model, reused as is
    └── Pages → Widgets
```

Profiles sit *above* the dashboard and add no second layout model or editor: Edit Mode,
widget move/resize/add/remove and page create/rename/reorder/delete are still the
`DashboardEditor`, now operating on `activeProfile.dashboard`. The code is in
`dashboard/profile/`: `DashboardProfile`, `ProfileCollection` (profiles + last selected +
startup default), `BuiltInProfiles` (templates), `ProfileEditor` (pure operations),
`ProfileSerializer`/`ProfileStore` (persistence and migration) and `ProfileSession` (the
running app's state; it stores every successful operation).

### Built-in profiles

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
later feature exists: no AI-assistant, Git or build/test controls, no Steam,
Discord, fan, temperature or game-detection items, and there is no automatic switching.
Profiles describe *contexts*, not operating systems — there is no per-platform profile.

### Profile switcher and manager

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
live editing state. While editing, remote actions are disabled.

### Create, duplicate, rename, reorder, delete, reset

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

### Persistence and migration

The profile document (`dashboard_profiles_json`, **schema 3**) holds `lastSelectedProfileId`,
`defaultProfileId` and the ordered `profiles`, each with `id`, `name`, `builtIn`, `order` and
its `dashboard` (the schema-2 object). No Room, SQLite or cloud.

**Migration of the existing single dashboard** happens when there is no readable profile
document: a valid pre-profile layout becomes the **Default profile's dashboard** (retired
action ids migrated as described above), the shipped Dev, Game and Media templates are added, and
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

### Startup policy

The profile restored at launch is resolved in this order:

```
last selected profile → configured startup default → built-in "default" → first valid profile
```

The *startup default* (**Set as startup default**) is only a fallback for when the last selected
profile can't be restored (e.g. it was deleted); normally the app reopens where you left it. It
is the persisted `defaultProfileId`, which is not the same thing as the built-in profile whose
id happens to be `default`.

### Live Windows state, artwork and capabilities

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

### Profile limitations

- A profile opens on its **first page**; the last-viewed page is not remembered per profile.
- The Default profile keeps its existing **Dev** page (the pre-profile layout is preserved
  rather than redesigned), so it overlaps a little with the Dev profile.
- No themes, no automatic switching, no per-profile BLE or device settings.
- Reordering is move up/down only; delete, reset and clear have no undo beyond their confirmation.
- A duplicate keeps the original's page and widget ids (they only need to be unique within one
  dashboard); only the profile id is new.
- The profile UI is unit-tested at the logic level only; see the manual checklist.

## Kiosk / immersive fullscreen

`kiosk/KioskController` owns this. Every screen hides the status and navigation bars via
`SYSTEM_UI_FLAG_IMMERSIVE_STICKY` + `FULLSCREEN`/`HIDE_NAVIGATION`, reapplied from both
`onResume()` and `onWindowFocusChanged(true)`. On `MainActivity`, `onBackPressed()` is a
no-op (it stops editing in edit mode). This is **application-level** kiosk behaviour — it
cannot block the Home or Recents buttons (that needs Device Owner provisioning, out of
scope).

**Development escape hatch:** press and hold the screen title / connection status text for
3 seconds to reveal the system bars via `KioskController.exitImmersiveMode()`. It is
self-healing: the next `onResume` / window-focus event reapplies immersive mode.

## Launch on boot

`kiosk/BootReceiver` listens for `BOOT_COMPLETED` and starts `MainActivity` directly — no
service, no polling. It is exempt from Android 8's implicit-broadcast restrictions so a
manifest-declared receiver works on API 27. Boot-launch is unconditional (no in-app toggle).

## Device / Settings screen

`DeviceSettingsActivity` (the ⚙ button); all logic lives in `device/`:

| Setting | Behaviour |
|---|---|
| Wi-Fi | opens `Settings.ACTION_WIFI_SETTINGS`; no credential storage |
| Bluetooth | shows on/off; opens `Settings.ACTION_BLUETOOTH_SETTINGS` |
| Brightness | in-app slider writing `Settings.System.SCREEN_BRIGHTNESS`; disabled with a "Grant permission" button without `WRITE_SETTINGS` |
| Tablet volume | slider over `AudioManager.STREAM_MUSIC` — separate from the remote PC volume commands |
| Keep screen awake | switch persisted in `SharedPreferences`; applied per Activity |
| Battery | read-only percentage, event-driven (`ACTION_BATTERY_CHANGED`) |

## Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

Why each one exists:

- `BLUETOOTH` / `BLUETOOTH_ADMIN` — scan for, connect to and disconnect from the PC. `targetSdk` 27
  is below the API 31 cutoff, so these classic permissions are granted at install.
- `ACCESS_COARSE_LOCATION` — Android returns no BLE scan results without it at this API level, so
  `ConnectActivity` requests it at runtime. The app uses no location API and never reads or stores
  the tablet's location.
- `RECEIVE_BOOT_COMPLETED` — only so `BootReceiver` can start the dashboard after boot. It acts on
  `BOOT_COMPLETED` and nothing else, and no service keeps running.
- `WRITE_SETTINGS` — optional, only for the brightness slider (`Settings.System.SCREEN_BRIGHTNESS`).
  It is a special permission you grant on its own system screen; the slider is disabled without it.

Not requested: `INTERNET` (or any network-state / Wi-Fi permission), storage, camera, microphone,
contacts, Accessibility, Device Admin/Owner. The Device screen's Wi-Fi and Bluetooth entries only
open Android's own settings screens. Android backup is disabled (`allowBackup="false"`). The merged
APK manifest also carries the build-tooling `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` entry that
the Android Gradle Plugin adds.

## Android limitations

- No true OS-level lockdown without Device Owner provisioning.
- Boot-launch reliability varies by OEM (some budget MediaTek skins need "autostart"
  allowed manually) and has no in-app toggle.
- `WRITE_SETTINGS` is a manual per-app grant.
