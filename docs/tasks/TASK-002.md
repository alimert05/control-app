# TASK-002 — Bluetooth Transport & Pairing

> **Historical document.** The product is now Android + Windows only (macOS support was retired); platform-neutral requirements here still apply. See [docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md).

## Goal

Implement the first real communication layer for Ali Control using Bluetooth Low Energy (BLE).

The Android tablet will act as the BLE central/client.

The desktop computer will later act as the BLE peripheral/server.

This design must support:
- macOS in TASK-003
- Windows later without changing the Android protocol

Target Android device:

- Alcatel 1T 7 8068
- Android 8.1
- API 27
- MediaTek MT6580
- armeabi-v7a

## Important Architecture Decision

Android tablet:
- BLE Central
- scans for Ali Control desktop agents
- connects to the selected desktop
- sends commands
- receives state/telemetry notifications

Desktop:
- BLE Peripheral
- implemented later
- advertises the Ali Control BLE service

Do not implement macOS or Windows code in this task.

## UI Changes

Remove tablet-local statistics from the main dashboard:

- battery percentage
- tablet volume
- tablet brightness

These should not be shown on the home screen.

The home screen should primarily represent the connected computer.

Keep the existing section buttons:

- Media
- PC
- Dev
- Device
- Settings

The header should show connection state, for example:

ALI CONTROL
No device connected

or:

ALI CONTROL
Ali's MacBook Air
Connected

## Bluetooth UI

Add a simple device connection flow.

When no computer is connected, the user should be able to:

- tap Connect
- scan for Ali Control devices
- see discovered devices
- select a device
- connect
- disconnect

Do not show arbitrary Bluetooth accessories.

Only show devices advertising the Ali Control BLE service UUID.

## BLE Protocol

Define stable UUID constants for:

- Ali Control service
- command characteristic
- state/notification characteristic

Store them in one clearly documented place.

The protocol must not be tied to macOS.

Use a small transport-independent message format.

For now support messages conceptually like:

Command:
{
  "type": "command",
  "action": "ping"
}

Response/state:
{
  "type": "state",
  "status": "connected"
}

Do not implement actual PC control actions yet.

## Transport Architecture

Use the Transport interface created in TASK-001.

Implement:

BluetoothTransport

It should support at least:

- scan
- stop scan
- connect
- disconnect
- connection state
- send message
- receive message callback/listener

Bluetooth-specific implementation details must not leak into the main UI.

Future UsbTransport and WifiTransport implementations should be able to use the same higher-level protocol.

## Android 8.1 Compatibility

This is critical.

Do not use BLE APIs that require newer Android versions unless guarded appropriately.

Handle Android 8.1 Bluetooth permissions correctly.

Avoid unnecessary dependencies.

Keep the implementation lightweight for MT6580 hardware.

## Reliability

Implement sensible:

- scan timeout
- connection timeout
- disconnect handling
- Bluetooth disabled handling
- error states

Do NOT implement complex auto-reconnection yet unless it is trivial and reliable.

## Tests

Add unit tests for logic that can be tested without physical BLE hardware.

The project must continue to build successfully.

Run:

- clean build
- unit tests

## Documentation

Update README.md with:

- BLE architecture
- Android = central
- desktop = peripheral
- service/characteristic UUIDs
- high-level protocol design
- Android Bluetooth permissions

## Out of Scope

Do NOT implement:

- macOS agent
- Windows agent
- media controls
- system controls
- desktop telemetry
- Wi-Fi transport
- USB transport
- kiosk mode
- custom ROM
- automatic installation to the connected tablet