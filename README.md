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
functional controls end to end. TASK-004 turns the tablet side into a
dedicated appliance: persistent immersive fullscreen, launch-on-boot, and an
in-app Device/Settings screen for the tablet's own Wi-Fi/Bluetooth/
brightness/volume/battery/keep-awake (see [TASKS.md](TASKS.md)).

## Overall architecture

```
Android tablet (BLE central)                    macOS agent (BLE peripheral)
─────────────────────────────                    ─────────────────────────────
ui/                                               App/            menu bar shell
  MainActivity, ConnectActivity,                    (AppDelegate, main.swift)
  DeviceSettingsActivity                                 |
      |                                             BLE/
kiosk/                                                PeripheralManager
  KioskController (immersive mode,                      (CBPeripheralManager)
    keep-awake, escape hatch)                            |
  BootReceiver (launch on boot)                     Protocol/
      |                                                ProtocolMessage
device/                                                 AliControlCommands
  DeviceController                                       |
  BrightnessController, VolumeController,           Control/
  BatteryProvider (tablet-local only —                 CommandDispatcher
  never touches BLE)                                    MacOSController
      |
transport/
  Transport + TransportManager
  BluetoothTransport (BLE central)  <==== BLE ====>
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
TASK-003 did not regenerate them:

| Purpose | UUID |
|---|---|
| Ali Control service (advertised by macOS; scan filter on Android) | `7eb4052b-fc5f-436f-ae69-c3c9e7e9809f` |
| Command characteristic (Android writes, macOS receives) | `6544209f-88b6-432e-9db1-c672b7a31916` |
| State characteristic (macOS notifies, Android subscribes) | `71dbc91d-eb99-4a50-b875-c3f045f4ea91` |
| Client Characteristic Config descriptor (standard BLE; Android writes it, CoreBluetooth manages it automatically on the macOS side) | `00002902-0000-1000-8000-00805f9b34fb` |

### Message format

A minimal JSON-shaped envelope — hand-rolled on Android (no JSON library, to
stay lightweight), `Codable`-based on macOS (Foundation is already there, so
that's free). Both produce/consume byte-identical wire output:

```json
{"type":"command","action":"volume_up"}
{"type":"state","action":"volume_up","status":"ok"}
```

`action` on a state message echoes which command it's acknowledging (or is
absent for the unsolicited handshake sent right after subscribe:
`{"type":"state","status":"connected"}`). A failed command gets
`{"type":"state","action":"...","status":"error"}` instead of silence.

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
name when connected, or disconnected/scanning/connecting/error otherwise), a
small ⚙ button that opens **Device/Settings**, and five control cards:
**Volume Up, Volume Down, Mute, Play / Pause, Terminal**. Tapping one with
nothing connected shows a lightweight toast instead of sending anything.
`ConnectActivity` is the separate scan/connect flow (unchanged from TASK-002).
Tablet-local stats (battery/brightness/volume) stay off this screen — they
live only in Device/Settings.

### Kiosk / immersive fullscreen

`kiosk/KioskController` (not any Activity) owns this. Every screen
(`MainActivity`, `ConnectActivity`, `DeviceSettingsActivity`) hides the status
and navigation bars via `View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY` +
`SYSTEM_UI_FLAG_FULLSCREEN`/`HIDE_NAVIGATION`, reapplied from both `onResume()`
and `onWindowFocusChanged(true)` — the standard two-hook pattern, since a
status-bar swipe changes window focus without a full pause/resume cycle. On
`MainActivity` (the app's home screen) `onBackPressed()` is a no-op, so the
back button can't exit to the launcher.

This is **application-level** kiosk behaviour only — it hides the system bars
and stops back-press from exiting, but it cannot block the physical/soft
Home or Recents buttons. That requires Device Owner provisioning, which
TASK-004 explicitly does not implement (see Limitations).

**Development escape hatch:** press and hold the screen title / connection
status text for 3 seconds (checked on release, not mid-hold) to reveal the
system bars via `KioskController.exitImmersiveMode()`, giving access to
Home/Recents/Settings. It's self-healing — the next `onResume`/window-focus
event on any screen reapplies immersive mode automatically, so there's no
separate "re-lock" step. Three continuous seconds isn't something a normal
user triggers by touching that text in passing.

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
| Bluetooth | Shows on/off (reusing `BluetoothTransport.isBluetoothEnabled` — the existing TASK-002/003 connection flow is untouched); button opens `Settings.ACTION_BLUETOOTH_SETTINGS`. |
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
  + `writeCharacteristic(characteristic)`, the 2-arg `onCharacteristicChanged`
  callback, etc.) since the newer byte-array overloads don't exist on API 27
  devices — these are the parts of the code carrying `@Suppress("DEPRECATION")`.
- Every TASK-004 API (`Settings.System.canWrite`, `ACTION_MANAGE_WRITE_SETTINGS`,
  `BatteryManager.BATTERY_PROPERTY_CAPACITY`, `AudioManager` stream volume,
  `FLAG_KEEP_SCREEN_ON`, `SharedPreferences`, manifest `BOOT_COMPLETED`) has
  existed since at most API 23 — nothing added here requires anything newer
  than API 27.

### Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

`targetSdk` 27 is below the API 31 cutoff where `BLUETOOTH_SCAN` /
`BLUETOOTH_CONNECT` replace the classic permissions, so those two are granted
at install time — no runtime prompt. `ACCESS_COARSE_LOCATION` **is** dangerous
on API 23+ (BLE scans silently return nothing without it); `ConnectActivity`
requests it at runtime.

**New in TASK-004:**
- `RECEIVE_BOOT_COMPLETED` — normal permission, install-time grant, required
  for `BootReceiver` to hear the boot broadcast at all.
- `WRITE_SETTINGS` — a *special* permission: declaring it does not grant it.
  The user must explicitly approve it on the system
  `ACTION_MANAGE_WRITE_SETTINGS` screen; `BrightnessController` checks
  `Settings.System.canWrite()` first and never assumes it. If not granted,
  the brightness slider is disabled with a "Grant permission" button — the
  rest of the app (BLE, tablet volume, battery, boot-launch) is entirely
  unaffected either way.
- No Accessibility permission, no Device Admin/Owner permission, and no new
  network/server permission were added — none were needed for anything in
  this task.
- The APK also carries an auto-injected, self-referencing
  `com.alicontrol.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — the Android
  Gradle Plugin adds this automatically for any app that calls
  `registerReceiver()` in code (the battery-change listener) when built
  against `compileSdk` 33. It's a build-tooling artifact, not something the
  user grants or something we declared.

### Build

Requires JDK 17 and the Android SDK (platform 33, build-tools 33.0.2). From
`android/`:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Install on the tablet (connected via USB, USB debugging enabled):

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Manual kiosk/settings test (not performed automatically)

1. Install the APK above and launch Ali Control.
2. Confirm it enters fullscreen immersive mode — status/navigation bars hidden.
3. Confirm the existing BLE connect flow and remote controls (TASK-003) still work.
4. Open Device/Settings (⚙ button).
5. Move the tablet volume slider — confirm the tablet's own volume changes.
6. Move the brightness slider — confirm it works, or (if `WRITE_SETTINGS` isn't
   granted yet) confirm the "Grant permission" button correctly routes to the
   system permission screen and the slider becomes usable after granting.
7. Confirm the battery percentage shown looks correct.
8. Tap the Wi-Fi "Open Settings" button — confirm it opens Android Wi-Fi settings.
9. Tap the Bluetooth "Open Settings" button — confirm it opens Android Bluetooth settings.
10. Toggle "Keep screen awake" — confirm the screen doesn't time out while on.
11. Close Device/Settings, reboot the tablet, and confirm Ali Control launches
    automatically after boot.
12. Press and hold the header text for 3 seconds on any screen — confirm the
    system bars reappear, and confirm they hide again automatically the next
    time the app is resumed/focused.
13. Leave the app (via the escape hatch) and return — confirm no crash.

### Android 8.1 limitations

- **No true OS-level lockdown.** Without Device Owner provisioning (explicitly
  out of scope for TASK-004), the app cannot block the physical/soft
  Home or Recents buttons — only the in-app back button (on the home screen)
  and the system bars themselves are addressable at the application level.
- **Boot-launch reliability varies by OEM.** Some Android skins (common on
  budget/MediaTek tablets) restrict `BOOT_COMPLETED` delivery or require the
  user to manually allow "autostart" for the app in vendor-specific battery/
  autostart-management settings, outside anything this app can control.
- **Boot-launch has no in-app toggle.** It's unconditional in this task's
  scope — turning it off means uninstalling or disabling the app.
- **`WRITE_SETTINGS` is a manual, per-app grant** on the system settings
  screen — it cannot be pre-approved at install time on any Android version.

## macOS

A menu-bar-only helper (Swift Package, no Xcode `.xcodeproj` needed) under
[`macos/`](macos/):

```
macos/
├── Package.swift
├── Info.plist                    NSBluetoothAlwaysUsageDescription
├── Sources/AliControlMac/
│   ├── App/                      main.swift, AppDelegate.swift — menu bar icon + Quit
│   ├── BLE/                      AliControlBleProfile.swift, PeripheralManager.swift
│   ├── Protocol/                 ProtocolMessage.swift, AliControlCommands.swift
│   └── Control/                  CommandDispatcher.swift, MacOSController.swift
└── Tests/AliControlMacTests/
```

(The task's suggested tree groups the same folders directly under
`AliControlMac/`; here they sit under `Sources/AliControlMac/` because that's
where Swift Package Manager requires a target's sources to live.)

`PeripheralManager` is the only file that imports `CoreBluetooth`. It decodes
bytes into a `ProtocolMessage` and hands it to `CommandDispatcher`, which maps
the action to one `MacOSController` method — an exhaustive switch over the
`AliControlCommand` enum, so there is no code path that runs an arbitrary
string as a command. `open_terminal` is its own hard-coded method, not a
generic command runner.

### Permissions

| Permission | Why | Requested how |
|---|---|---|
| **Bluetooth** (`NSBluetoothAlwaysUsageDescription`) | `CBPeripheralManager` needs it to advertise/accept connections at all | macOS prompts automatically the first time the agent runs; declared in `Info.plist` (embedded into the executable at link time — see `Package.swift` — since a plain Swift Package executable has no app bundle of its own) |

**Accessibility is not requested anywhere in this code** (no
`AXIsProcessTrustedWithOptions` call, no `AXUIElement` usage). Volume/mute use
the built-in `set volume`/`get volume settings` AppleScript commands, which the
OS handles directly rather than routing through another app — no Automation
permission either. `open_terminal` uses `NSWorkspace`, which is unprivileged.

`play_pause` posts a synthetic system-defined (media-key) `CGEvent` — a
public, documented mechanism, not the private Accessibility API — but on some
macOS versions the OS still requires Accessibility trust before it will
actually deliver a posted `CGEvent` system-wide. This agent does not
proactively request that permission; if Play/Pause has no effect, granting
Accessibility to the agent in System Settings → Privacy & Security is the
first thing to check (see Limitations below).

### Build & test

```bash
cd macos
swift build          # or: xcodebuild -scheme AliControlMac -destination 'platform=macOS' build
swift test            # or: xcodebuild -scheme AliControlMac -destination 'platform=macOS' test
```

### Run

```bash
cd macos
swift run
```

A menu bar icon appears (○ Bluetooth off · ◐ advertising, waiting for the
tablet · ● a tablet is connected · ⚠︎ permission/support problem). Click it for
a status line and Quit. It does not add a Dock icon or launch at login.

## Manual end-to-end test

Not performed automatically — requires the physical tablet and a Mac in the
same room:

1. `cd macos && swift run` — grant the Bluetooth permission prompt if shown.
2. Confirm the menu bar icon reaches ◐ (advertising).
3. Launch Ali Control on the Alcatel tablet, tap **Connect**, grant the
   location permission prompt if this is the first run.
4. The Mac should appear in the scan list (named after the Mac's hostname);
   select it.
5. Android should show the Mac's name and "Connected"; the macOS menu bar
   icon should reach ●.
6. Tap **Volume Up** / **Volume Down** a few times — confirm the Mac's output
   volume actually changes.
7. Tap **Mute** — confirm the Mac's mute state toggles.
8. Tap **Play / Pause** while something is playing (e.g. Music.app) — confirm
   playback toggles. If nothing happens, see the Accessibility caveat above.
9. Tap **Terminal** — confirm Terminal.app opens.
10. Tap **Disconnect** on Android — confirm the Mac's menu bar icon drops back
    to ◐ and Android returns to "No device connected" cleanly (no crash on
    either side).

## Limitations / assumptions requiring physical testing

Everything above the low-level `CBPeripheralManager`/`BluetoothGatt` API calls
has been verified by unit tests and a clean build on both sides, but none of
it has been exercised against real hardware together:

- Whether Android actually discovers the Mac's advertisement (real BLE range,
  timing, and the local name surviving into the scan result) is unverified.
- Whether `play_pause`'s synthetic media-key event needs Accessibility
  permission on this specific Mac/macOS version is unverified — the code
  degrades gracefully either way (reports `"ok"` since `CGEventPost` doesn't
  report permission failures synchronously; the actual system effect can only
  be confirmed by looking at the Mac).
- Whether the `NSBluetoothAlwaysUsageDescription` embedded via the
  `-sectcreate __TEXT __info_plist` linker trick is actually picked up by TCC
  for a bundle-less `swift run` executable (vs. needing a real `.app` bundle)
  is unverified — if the Bluetooth permission prompt never appears and
  `CBPeripheralManager` reports `.unauthorized`, that's the first thing to
  investigate.
- General BLE reliability under real conditions — reconnect behavior,
  multiple nearby BLE devices, disconnect/timeout edge cases — is inherited
  from TASK-002 and still only exercised there in isolation.
