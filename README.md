# Ali Control

Ali Control turns an old Android tablet into a dedicated desk control panel for a
desktop computer — media control, PC actions, developer shortcuts, device state,
and settings, all from a fullscreen, always-on second screen.

## Project purpose

The tablet runs a lightweight native app that eventually talks to a desktop agent
(macOS first, Windows later) over a local transport (Bluetooth, USB, or Wi-Fi) to
control the desktop and report tablet state back. TASK-001 builds only the
Android foundation: the UI shell and local device info. No transport, protocol,
or desktop agent exists yet — those are later tasks (see [TASKS.md](TASKS.md)).

## Architecture

```
Tablet (this app)
  UI (Activities/Views)
      |
  Device layer      — local battery/volume/brightness (com.alicontrol.app.device)
      |
  Transport layer    — Transport interface, no implementation yet (com.alicontrol.app.transport)
      |
  Protocol           — not implemented yet
      |
  macOS / Windows desktop agent — not implemented yet
```

- `ui/` — Activities and views only. No device or transport logic lives here.
- `device/` — reads local tablet state (battery, volume, brightness) via plain,
  unit-testable functions.
- `transport/` — the `Transport` interface and `TransportManager` that future
  `BluetoothTransport`, `UsbTransport`, and `WifiTransport` implementations will
  plug into. Currently always reports "No device connected".

## Android requirements

- Kotlin, XML Views (no Jetpack Compose)
- `minSdk` 27 / `targetSdk` 27 (Android 8.1), `compileSdk` 33
- Gradle 8.4, Android Gradle Plugin 8.1.4, Kotlin 1.9.22
- Target hardware: Alcatel 1T 7 8068 (MediaTek MT6580, `armeabi-v7a`, 7", low-end)
- No non-essential runtime permissions requested

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
