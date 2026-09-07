# TASK-001 — Android Control Panel Foundation

## Goal

Create the initial native Android application for Ali Control.

The target device is:

- Alcatel 1T 7 8068
- Android 8.1
- API 27
- MediaTek MT6580
- armeabi-v7a
- 7-inch tablet
- low-performance hardware

## Requirements

Create the Android project inside:

android/

Use:

- Kotlin
- XML Views
- Gradle
- Android 8.1 compatibility
- lightweight dependencies

Do not use Jetpack Compose.

## UI

The application should be landscape-first and fullscreen.

Initial home screen:

- Title: ALI CONTROL
- Connection status: No device connected
- Battery percentage
- Tablet volume
- Screen brightness

Main control sections:

- Media
- PC
- Dev
- Device
- Settings

These may initially be placeholder buttons/cards.

The design should be:

- dark
- minimal
- modern
- readable from a desk
- large touch targets
- optimized for a 7-inch screen
- lightweight for MT6580

## Architecture

Keep UI separate from device/control logic.

Prepare clean interfaces for future:

- BluetoothTransport
- UsbTransport
- WifiTransport

Do not implement these transports yet.

Create an application structure that can later support:

Tablet
→ Transport layer
→ Protocol
→ macOS / Windows desktop agents

## Device features

For TASK-001 implement local tablet information/control where practical:

- battery percentage
- current volume
- brightness value

Do not request unnecessary permissions.

## Documentation

Update README.md with:

- project purpose
- architecture
- Android requirements
- build instructions
- adb installation instructions

## Testing

The application must build successfully.

It must support installation on:

Alcatel 1T 7 8068
Android 8.1
armeabi-v7a

Avoid APIs that require Android versions newer than the target device.

## Out of scope

Do NOT implement:

- Bluetooth
- macOS agent
- Windows agent
- Wi-Fi communication
- USB communication
- kiosk/device-owner mode
- custom ROM
- complex animations