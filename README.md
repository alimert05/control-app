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
is genuinely usable, and hardened MTU/message-size handling (see
[TASKS.md](TASKS.md)).

## Overall architecture

```
Android tablet (BLE central)                    macOS agent (BLE peripheral)
─────────────────────────────                    ─────────────────────────────
ui/                                               App/            menu bar shell
  MainActivity, ConnectActivity,                    main.swift, AppDelegate.swift,
  DeviceSettingsActivity                              LoginItemController.swift
      |                                                  |
kiosk/                                             BLE/
  KioskController (immersive mode,                   PeripheralManager
    keep-awake, escape hatch)                          (CBPeripheralManager)
  BootReceiver (launch on boot)                          |
      |                                             Protocol/
device/                                                ProtocolMessage
  DeviceController                                       AliControlCommands
  BrightnessController, VolumeController,                |
  BatteryProvider (tablet-local only —              Control/
  never touches BLE)                                   CommandDispatcher
      |                                                 MacOSController
transport/
  Transport + TransportManager
  BluetoothTransport (BLE central,
    bounded auto-reconnect)  <==== BLE ====>
      |
ble/
  AliControlBleProfile (UUIDs)
  ProtocolMessage (message format)
  AliControlCommands (allowlist)
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

### Supported commands

The full, explicit allowlist — nothing outside this list is ever sent or
executed on either side (there is no generic/arbitrary command path):

| Action | Effect on macOS |
|---|---|
| `ping` | Acknowledged only; no system effect |
| `volume_up` | Output volume +10 (AppleScript `set volume`) |
| `volume_down` | Output volume −10 |
| `mute_toggle` | Toggles output mute |
| `play_pause` | Simulates the hardware media-key press (whatever app owns "now playing") |
| `open_terminal` | Launches Terminal.app via `NSWorkspace` |

Defined identically in [`AliControlCommands.kt`](android/app/src/main/java/com/alicontrol/app/ble/AliControlCommands.kt)
and [`AliControlCommands.swift`](macos/Sources/AliControlMac/Protocol/AliControlCommands.swift).

## Android

### UI

The home screen has no app branding/title — just connection state (device
name when connected, or disconnected/scanning/**preparing**/connecting/error
otherwise), a small ⚙ button that opens **Device/Settings**, and five control
cards: **Volume Up, Volume Down, Mute, Play / Pause, Terminal**. The cards are
visibly dimmed and disabled whenever nothing is connected (TASK-005); tapping
one with nothing connected still shows a lightweight toast as a second line
of defense. `ConnectActivity` is the separate scan/connect flow (unchanged
from TASK-002). Tablet-local stats (battery/brightness/volume) stay off this
screen — they live only in Device/Settings.

### Kiosk / immersive fullscreen

`kiosk/KioskController` (not any Activity) owns this. Every screen
(`MainActivity`, `ConnectActivity`, `DeviceSettingsActivity`) hides the status
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
- Every API used across all five tasks (`Settings.System.canWrite`,
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

Bluetooth-only, as preferred. **Accessibility is not requested anywhere in
this code** (no `AXIsProcessTrustedWithOptions` call, no `AXUIElement`
usage). Volume/mute use the built-in `set volume`/`get volume settings`
AppleScript commands, which the OS handles directly rather than routing
through another app — no Automation permission either. `open_terminal` uses
`NSWorkspace`, which is unprivileged. `Open at Login`'s `SMAppService` needs
no extra entitlement or usage-description key for the `mainApp` variant used
here.

`play_pause` posts a synthetic system-defined (media-key) `CGEvent` — a
public, documented mechanism, not the private Accessibility API — but on some
macOS versions the OS still requires Accessibility trust before it will
actually deliver a posted `CGEvent` system-wide. This agent does not
proactively request that permission and never opens System Settings on the
user's behalf; if Play/Pause has no effect, granting Accessibility to the
agent manually is the first thing to check. **Every other control (volume,
mute, Terminal, and the BLE connection itself) is completely unaffected**
either way — this is the only feature with any Accessibility dependency, and
it degrades gracefully rather than blocking anything else.

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
6. Test **Volume Up**, **Volume Down**, **Mute**, **Play/Pause** (see the
   Accessibility caveat above if it has no effect), and **Terminal**.
7. Force-quit `AliControlMac.app` (or use its Quit menu item) — confirm
   Android detects the disconnect immediately (no stale "Connected").
8. Relaunch the Mac app — confirm Android can reconnect (either the bounded
   auto-reconnect kicks in, or a manual Connect works) without restarting the
   tablet app.
9. Toggle Mac Bluetooth off and back on — confirm advertising recovers on its
   own.
10. Toggle tablet Bluetooth off and on — confirm Android recovers cleanly.
11. Reboot the tablet — confirm Ali Control still launches on boot.
12. If "Open at Login" is enabled, restart the Mac and confirm
    `AliControlMac.app` launches automatically.
13. Confirm no Terminal or Xcode process is needed anywhere in the above.
14. Open Activity Monitor while idle (nothing being pressed) — confirm CPU
    usage is effectively idle and memory usage is reasonable for a menu-bar
    helper.
15. On Android: open Device/Settings and exercise every control (brightness,
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
- Whether `play_pause`'s synthetic media-key event needs Accessibility
  permission on this specific Mac/macOS version — degrades gracefully either
  way, but the actual system effect can only be confirmed by looking at the
  Mac.
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
