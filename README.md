# Ali Control

Ali Control turns an old Android tablet into a dedicated desk control panel for a
desktop computer — media control, PC actions, developer shortcuts, device state,
and settings, all from a fullscreen, always-on second screen.

## Project purpose

The tablet runs a lightweight native app that talks to a desktop agent (macOS
first, Windows later) over Bluetooth Low Energy to control the desktop and
report state back. TASK-001 built the Android UI shell. TASK-002 adds the
first real communication layer: the tablet acts as a BLE central, scanning for
and connecting to an Ali Control desktop peripheral. No desktop agent exists
yet — that's TASK-003 (see [TASKS.md](TASKS.md)).

## Architecture

```
Tablet (this app)
  UI (Activities/Views)                     com.alicontrol.app.ui
      |
  Transport layer                           com.alicontrol.app.transport
    Transport interface + TransportManager
    BluetoothTransport (BLE central)  <-- only implementation so far
      |
  Protocol                                  com.alicontrol.app.ble
    AliControlBleProfile (UUIDs) + ProtocolMessage (message format)
      |
  macOS / Windows desktop agent (BLE peripheral) — not implemented yet
```

- `ui/` — Activities and views only. `MainActivity` reads connection state
  through `TransportManager`/`Transport` and never touches Bluetooth APIs
  directly. `ConnectActivity` is the one BLE-specific screen (scan, permission
  and Bluetooth-enable prompts, device list, connect) — this is where
  transport-specific detail is expected to live, the same way a future
  Wi-Fi/USB device picker would.
- `transport/` — `Transport`, the transport-agnostic contract (`isConnected`,
  `remoteName`, `addListener`/`removeListener`, `disconnect`, `send`), and
  `BluetoothTransport`, the BLE central implementation. Future `UsbTransport`
  and `WifiTransport` implementations plug into the same interface.
- `ble/` — the protocol, independent of transport and of desktop OS:
  `AliControlBleProfile` (the GATT UUIDs) and `ProtocolMessage` (the tiny
  JSON-shaped command/state envelope).
- `AliControlApp` (the `Application` subclass) owns the single
  `TransportManager` instance so the BLE connection survives navigating
  between activities.

## BLE architecture

- **Android tablet = BLE central/client.** It scans, connects, writes
  commands, and subscribes to state notifications.
- **Desktop computer = BLE peripheral/server** (TASK-003+, not implemented
  yet). It must advertise the Ali Control service and implement the two
  characteristics below. This split is deliberate: BLE peripheral support on
  macOS/Windows is what makes a phone/tablet-style central app possible
  without extra hardware.
- The protocol (`ble/` package) is not tied to macOS or Windows — any desktop
  OS that can run a BLE peripheral implementing the same UUIDs and message
  format works, unchanged, on the Android side.

### Service / characteristic UUIDs

Defined once, in [`AliControlBleProfile`](android/app/src/main/java/com/alicontrol/app/ble/AliControlBleProfile.kt):

| Purpose | UUID |
|---|---|
| Ali Control service (advertised by the desktop; scan filter on the tablet) | `7eb4052b-fc5f-436f-ae69-c3c9e7e9809f` |
| Command characteristic (tablet writes) | `6544209f-88b6-432e-9db1-c672b7a31916` |
| State characteristic (desktop notifies) | `71dbc91d-eb99-4a50-b875-c3f045f4ea91` |
| Client Characteristic Config descriptor (standard BLE, enables notifications) | `00002902-0000-1000-8000-00805f9b34fb` |

### Message format

A minimal, hand-rolled JSON-shaped envelope (see
[`ProtocolMessage`](android/app/src/main/java/com/alicontrol/app/ble/ProtocolMessage.kt)),
written over the command characteristic and read from state notifications as
UTF-8 bytes:

```json
{"type":"command","action":"ping"}
{"type":"state","status":"connected"}
```

No actual PC control actions are implemented yet — TASK-002 only proves the
transport and envelope.

### Android Bluetooth permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

`targetSdk` is 27, well below the API 31 cutoff where `BLUETOOTH_SCAN` /
`BLUETOOTH_CONNECT` replace the classic permissions — the legacy model applies
and those two are granted at install time, no runtime prompt needed.
`ACCESS_COARSE_LOCATION` **is** a dangerous permission on API 23+, and BLE
scans silently return zero results without it — `ConnectActivity` checks for
it and requests it at runtime before scanning.

## Android requirements

- Kotlin, XML Views (no Jetpack Compose)
- `minSdk` 27 / `targetSdk` 27 (Android 8.1), `compileSdk` 33
- Gradle 8.4, Android Gradle Plugin 8.1.4, Kotlin 1.9.22
- Target hardware: Alcatel 1T 7 8068 (MediaTek MT6580, `armeabi-v7a`, 7", low-end)
- BLE GATT calls deliberately use the pre-API-33 APIs (`characteristic.value`
  + `writeCharacteristic(characteristic)`, the 2-arg `onCharacteristicChanged`
  callback, etc.) since the newer byte-array overloads don't exist on API 27
  devices — these are the parts of the code carrying `@Suppress("DEPRECATION")`

## Build instructions

Requires JDK 17 and the Android SDK (platform 33, build-tools 33.0.2). From the
`android/` directory:

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

The debug APK is produced at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

## Install on the tablet (adb)

With the tablet connected via USB (and USB debugging enabled):

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```
