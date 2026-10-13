# TASK-003 — macOS BLE Agent & Core Controls

> **Historical document.** This task was written when macOS was the reference desktop platform. Windows 11 is now the only supported desktop and the macOS agent was removed. Read it for the *product intent and constraints*; the macOS-specific implementation notes (Swift, CoreBluetooth, `.app` bundle, menu bar, Accessibility, Login Items) are superseded by [docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md) and the Windows agent in `windows/`.

## Goal

Implement the first real desktop agent for Ali Control on macOS.

The Android tablet already acts as the BLE central/client.

For this task, create a native macOS helper that acts as the BLE peripheral/server, advertises the Ali Control BLE service, accepts a connection from the Android tablet, receives control commands, executes them on macOS, and sends simple state messages back to the tablet.

Target development machine:

- Apple Silicon MacBook
- macOS
- Xcode available
- Swift preferred
- CoreBluetooth for BLE

The implementation should remain conceptually compatible with a future Windows agent.

---

## Android UI Cleanup

Remove the visible `ALI CONTROL` title/branding from the Android application UI.

Do not replace it with another large application title.

The application should look like a clean control surface rather than a branded app.

Keep project/package naming unchanged unless there is a technical reason to modify it.

The home screen should focus on:

- connection state
- computer name
- control sections/actions

---

## BLE Architecture

The existing BLE architecture from TASK-002 must remain:

Android tablet:
- BLE Central
- scans
- connects
- writes commands
- subscribes to state notifications

macOS helper:
- BLE Peripheral
- advertises Ali Control service
- exposes command characteristic
- exposes state/notification characteristic
- receives commands from Android
- sends state updates to Android

Use the exact UUIDs already defined in the Android project.

Do not generate new UUIDs.

Current profile:

Service:
`7eb4052b-fc5f-436f-ae69-c3c9e7e9809f`

Command characteristic:
`6544209f-88b6-432e-9db1-c672b7a31916`

State characteristic:
`71dbc91d-eb99-4a50-b875-c3f045f4ea91`

CCCD:
`00002902-0000-1000-8000-00805f9b34fb`

---

## macOS Agent

Create the macOS agent under:

`macos/`

Prefer a small native Swift application/helper using CoreBluetooth.

It may be a simple menu-bar app or minimal background-capable app.

Avoid unnecessary UI.

The macOS agent must:

- initialize `CBPeripheralManager`
- advertise the Ali Control BLE service
- expose the command characteristic
- expose the state characteristic with notifications
- detect subscription from the Android tablet
- receive command payloads
- parse protocol messages
- execute supported macOS actions
- send state/ack messages back to the tablet
- expose basic connection status locally for debugging

Do not depend on third-party cloud services.

Do not open network ports.

Do not use HTTP.

Communication for this task must be BLE only.

---

## Protocol

Reuse the protocol design introduced in TASK-002.

Do not create a macOS-only message format.

For this task, support the following commands:

### Ping

Tablet sends conceptually:

```json
{
  "type": "command",
  "action": "ping"
}
Mac should respond with a state or acknowledgement message.
Volume Up
{
  "type": "command",
  "action": "volume_up"
}
Volume Down
{
  "type": "command",
  "action": "volume_down"
}
Mute Toggle
{
  "type": "command",
  "action": "mute_toggle"
}
Play / Pause
{
  "type": "command",
  "action": "play_pause"
}
Open Terminal
{
  "type": "command",
  "action": "open_terminal"
}
If a command fails, return an error/state message instead of silently failing.
macOS Control Implementation
Use native macOS APIs where practical.
For initial MVP actions, lightweight approaches are acceptable.
Examples:
- system volume through AppleScript or native APIs
- mute through AppleScript/native APIs
- media play/pause through a reliable macOS-compatible mechanism
- Terminal launch via NSWorkspace
Keep action execution separated from BLE code.
For example:
BLE Peripheral
    ↓
Protocol Parser
    ↓
Command Dispatcher
    ↓
MacOSController
Do not put all command logic directly inside CoreBluetooth delegate methods.
Android Controls
Add functional buttons/actions to the existing Android UI for:
- Volume Up
- Volume Down
- Mute
- Play/Pause
- Open Terminal
These buttons should only send commands when a desktop is connected.
If no desktop is connected:
- do not crash
- show a lightweight error/status indication
The existing section structure may remain, but TASK-003 should expose enough controls to perform an end-to-end test.
Do not implement complex navigation if not needed.
Connection State
The Android app should clearly show:
- disconnected
- scanning
- connecting
- connected
- connection error
When connected, show the remote computer name where available.
Do not show a large ALI CONTROL title.
The macOS helper should notify Android with an initial state message after subscription/connection if practical.
Security
This task must not create an unauthenticated TCP/HTTP server.
Do not expose any LAN port.
BLE only.
Do not implement cloud communication.
Do not collect or transmit user data.
Only execute the explicitly supported command allowlist.
Do not implement arbitrary shell command execution.
open_terminal must be a hard-coded supported action, not a generic command runner.
Permissions
Document all macOS permissions required.
If Accessibility permission is not required for the chosen implementation, do not request it.
Prefer implementations that avoid powerful permissions when possible.
If any permission is required:
- explain exactly why
- request only the minimum necessary
Android permissions should remain compatible with Android 8.1 / API 27.
Architecture
Keep platform-specific control logic isolated.
Suggested macOS structure:
macos/
├── AliControlMac/
│   ├── BLE/
│   │   └── PeripheralManager.swift
│   ├── Protocol/
│   │   └── ProtocolMessage.swift
│   ├── Control/
│   │   ├── CommandDispatcher.swift
│   │   └── MacOSController.swift
│   └── App/
│       └── ...
Exact naming may differ if there is a good reason.
The architecture should make a future Windows implementation possible without changing the Android protocol.
Testing
Add tests for logic that can be tested without physical BLE hardware.
At minimum:
- protocol parsing
- unsupported commands
- command dispatch logic
- state/ack serialization
Build and test both projects:
Android
- clean build
- Android unit tests
macOS
- Xcode build
- macOS unit tests where practical
Do not mark TASK-003 complete unless both projects build successfully.
Manual End-to-End Test Target
After implementation, the expected manual test is:
1. Launch the macOS helper.
2. Mac advertises Ali Control BLE service.
3. Launch Ali Control on Alcatel tablet.
4. Tap Connect.
5. Mac appears in the BLE device list.
6. Connect.
7. Android displays connected state.
8. Press Volume Up.
9. Mac volume increases.
10. Press Volume Down.
11. Mac volume decreases.
12. Press Mute.
13. Mac mute state toggles.
14. Press Play/Pause.
15. Media playback toggles where supported.
16. Press Open Terminal.
17. Terminal opens.
18. Disconnect and verify both sides recover cleanly.
Manual testing may be performed after the task build finishes.
Documentation
Update README.md with:
- overall Android ↔ macOS architecture
- BLE roles
- UUIDs
- supported commands
- how to build Android
- how to build macOS helper
- how to run the macOS helper
- manual pairing/test instructions
- any macOS permissions required
Out of Scope
Do NOT implement:
- Windows agent
- Wi-Fi transport
- USB transport
- arbitrary shell commands
- CPU/GPU/RAM telemetry
- Spotify-specific integration
- Discord integration
- custom ROM
- kiosk/device-owner mode
- automatic startup
- complex auto-reconnection
- cloud communication
- HTTP server
- external remote access