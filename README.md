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
12x8 logical-grid models, while retaining the complete Actions screen for
manual coverage of all 27 commands.

## Overall architecture

```
Android tablet (BLE central)
  ui/                      MainActivity + secondary screens
    └─ ui/dashboard/       ViewPager2, grid/page/widget renderers
         └─ dashboard/     UI-independent page, widget and 12x8 grid data
              └─ actions/  ActionLibrary metadata + shared ActionDispatcher
                   └─ transport/ + ble/
                        BluetoothTransport, protocol messages and allowlist
                                  ⇅ BLE
macOS agent (BLE peripheral)
  App/                     menu-bar lifecycle and login item
    └─ BLE/                PeripheralManager
         └─ Protocol/      ProtocolMessage + AliControlAction IDs
              └─ Control/  ActionRegistry + platform-specific controllers

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

The home screen is now a dedicated, three-page dashboard. Its compact header
keeps connection state, Connect/Disconnect, **Device/Settings**, and **All
Actions** immediately reachable; `ConnectActivity` remains the separate BLE
scan/connect flow. Horizontal swipes move between **Dashboard**, **Media**,
and **Dev**, with the current page name and a lightweight dot indicator making
position clear. Page count comes from the `Dashboard.pages` data rather than
being hardcoded in `MainActivity`, so the same pager can render an arbitrary
number of pages when TASK-008 adds page editing.

`dashboard/` is a UI-independent model: each `DashboardPage` owns ordered
`DashboardWidget` values with type, configuration, and bounds in one stable
**12-column × 8-row logical grid**. `DefaultDashboardFactory` supplies the
three shipped layouts as static in-memory data. `ui/dashboard/` turns that
data into native XML/View-based pages using a small custom `ViewGroup`, a
`ViewPager2`, and reusable card renderers—there is no Compose, database,
continuous animation, or telemetry polling.

The renderer supports `ACTION`, `ACTION_GROUP`, `METRIC`, `GAUGE`, `CLOCK`,
`MEDIA`, and `SLIDER`, plus a local-only `SHORTCUT` used for Device/Settings.
The Dashboard page mixes CPU/GPU/RAM placeholders, a local minute-updated
clock, media transport controls, volume/mute, Lock, Screenshot, Terminal, VS
Code, Spotlight, and Mission Control. Media provides the full transport row,
volume, mute, and Spotify. Dev provides VS Code, Terminal, Browser, Finder,
Spotlight, Activity Monitor, Screenshot, System Settings, and compact system
placeholders.

Every remote action still resolves its ID through `ActionLibrary` and is sent
through the shared `ActionDispatcher` and existing `TransportManager`; danger
metadata therefore still controls confirmation dialogs. Remote widgets dim
and disable while disconnected, while metrics, the clock, and local
Device/Settings access remain usable. The current agent is macOS, so widgets
whose actions do not support macOS render disabled rather than being removed;
this preserves grid geometry and can consume a future connected-platform
signal without changing the dashboard model.

Action icons come from one Android `ActionIconRegistry` backed entirely by
small local vector drawables. CPU/GPU/RAM and gauge shells deliberately show
`--`, media deliberately says `Media unavailable`, and the slider shell has
no thumb or value: TASK-007 does not fabricate telemetry, volume state, media
metadata, or artwork. TASK-008 can persist and edit the same page/widget/grid
data later; TASK-007 intentionally has no editor, drag/drop, resize handles,
picker, or layout persistence.

**Actions screen (TASK-006, temporary):** `ActionsActivity` lists every
action from `ActionLibrary` that supports macOS, grouped by category, built
at runtime by iterating the registry rather than hand-authored per action —
that's the whole point of having structured metadata. Rows reuse the
existing plain-row style already used for the BLE device list; there's no
new design system here on purpose. Tapping a `SAFE` action sends it
immediately; tapping `sleep` (the only `CONFIRM` action) shows an
`AlertDialog` first. It remains available through **All Actions** so all 27
actions can be tested until TASK-008 supplies an action picker.

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
  this codebase requires anything newer than API 27.

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

### Permissions

| Permission | Why | Requested how |
|---|---|---|
| **Bluetooth** (`NSBluetoothAlwaysUsageDescription`) | `CBPeripheralManager` needs it to advertise/accept connections at all | macOS prompts automatically the first time the app runs; declared in the bundle's real `Contents/Info.plist` (see packaging above — no longer a linker-embedded workaround) |

Bluetooth-only at the *system-permission* level, as preferred — there is
exactly one `NSUsageDescription` key in `Info.plist`. **Accessibility is
never requested anywhere in this code** (no `AXIsProcessTrustedWithOptions`
call, no `AXUIElement` usage) — `AccessibilityChecker` only ever *reads*
`AXIsProcessTrusted()`. `Open at Login`'s `SMAppService` needs no extra
entitlement or usage-description key for the `mainApp` variant used here.

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
6. Verify Dashboard, Media, and Dev render correctly; swipe repeatedly through
   them and confirm the page name/dots follow without lag or crashes.
7. Test the dashboard actions, including media transport, volume/mute, Lock,
   Screenshot, Terminal, VS Code, Spotlight, Mission Control, Spotify, Browser,
   Finder, Activity Monitor, and System Settings. Confirm CPU/GPU/RAM remain
   honest `--` placeholders and media remains `Media unavailable`.
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
11. Disconnect BLE and confirm remote dashboard widgets disable while the
    dashboard, Device/Settings, All Actions, metrics, and clock remain visible;
    reconnect and confirm actions recover without restarting the app.
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
