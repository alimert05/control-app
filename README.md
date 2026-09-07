# Ali Control

Ali Control turns an old Android tablet into a dedicated desk control panel for a
desktop computer — media control, PC actions, developer shortcuts, device state,
and settings, all from a fullscreen, always-on second screen.

## Project purpose

An Android tablet (BLE central) talks to a small native desktop agent (BLE
peripheral) over Bluetooth Low Energy to control the desktop and get simple
state back. TASK-001 built the Android UI shell. TASK-002 added the BLE
transport and protocol on the Android side. TASK-003 adds the first real
desktop agent — a macOS menu-bar helper — and wires up the first five
functional controls end to end (see [TASKS.md](TASKS.md)).

## Overall architecture

```
Android tablet (BLE central)                    macOS agent (BLE peripheral)
─────────────────────────────                    ─────────────────────────────
ui/                                               App/            menu bar shell
  MainActivity, ConnectActivity                     (AppDelegate, main.swift)
      |                                                  |
transport/                                        BLE/
  Transport + TransportManager                      PeripheralManager
  BluetoothTransport (BLE central)  <==== BLE ====>      (CBPeripheralManager)
      |                                                  |
ble/                                              Protocol/
  AliControlBleProfile (UUIDs)                       ProtocolMessage
  ProtocolMessage (message format)                    AliControlCommands
  AliControlCommands (allowlist)                     |
                                                   Control/
                                                     CommandDispatcher
                                                     MacOSController
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
name when connected, or disconnected/scanning/connecting/error otherwise) and
five control cards: **Volume Up, Volume Down, Mute, Play / Pause, Terminal**.
Tapping one with nothing connected shows a lightweight toast instead of
sending anything. `ConnectActivity` is the separate scan/connect flow (unchanged
from TASK-002).

### Requirements

- Kotlin, XML Views (no Jetpack Compose)
- `minSdk` 27 / `targetSdk` 27 (Android 8.1), `compileSdk` 33
- Gradle 8.4, Android Gradle Plugin 8.1.4, Kotlin 1.9.22
- Target hardware: Alcatel 1T 7 8068 (MediaTek MT6580, `armeabi-v7a`, 7", low-end)
- BLE GATT calls deliberately use the pre-API-33 APIs (`characteristic.value`
  + `writeCharacteristic(characteristic)`, the 2-arg `onCharacteristicChanged`
  callback, etc.) since the newer byte-array overloads don't exist on API 27
  devices — these are the parts of the code carrying `@Suppress("DEPRECATION")`.
  No API introduced in TASK-003 requires anything newer than API 27.

### Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

`targetSdk` 27 is below the API 31 cutoff where `BLUETOOTH_SCAN` /
`BLUETOOTH_CONNECT` replace the classic permissions, so those two are granted
at install time — no runtime prompt. `ACCESS_COARSE_LOCATION` **is** dangerous
on API 23+ (BLE scans silently return nothing without it); `ConnectActivity`
requests it at runtime.

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
