# TASK-005 — Polish, Packaging & Reliability

> **Historical document.** This task was written when macOS was the reference desktop platform. Windows 11 is now the only supported desktop and the macOS agent was removed. Read it for the *product intent and constraints*; the macOS-specific implementation notes (Swift, CoreBluetooth, `.app` bundle, menu bar, Accessibility, Login Items) are superseded by [docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md) and the Windows agent in `windows/`.

## Goal

Turn the current working Android + macOS Ali Control prototype into a stable,
low-overhead, daily-usable MVP.

The existing functionality from TASK-001 through TASK-004 must continue to work.

This task should focus on:

- macOS application packaging
- menu-bar UX
- startup behavior
- BLE reliability
- reconnect behavior
- error handling
- permission handling
- resource usage
- release readiness
- cleanup

Do not add major new product features.

---

## Current Architecture

Android tablet:

- Alcatel 1T 7 8068
- Android 8.1 / API 27
- MT6580
- armeabi-v7a
- BLE Central
- kiosk-style fullscreen UI
- launch-on-boot
- device settings
- remote Mac controls

macOS:

- native Swift
- CoreBluetooth BLE Peripheral
- currently runnable through `swift run`
- supports:
  - ping
  - volume_up
  - volume_down
  - mute_toggle
  - play_pause
  - open_terminal

The current protocol and UUIDs must remain compatible.

---

## macOS Real Application Bundle

Replace the development-only `swift run` experience with a normal macOS `.app`.

The final result should be something equivalent to:

`AliControlMac.app`

Requirements:

- native macOS application
- Apple Silicon compatible
- real `.app` bundle
- proper Info.plist
- correct Bluetooth usage description
- no dependency on Terminal
- no requirement for Xcode to remain open
- launchable like a normal macOS application

Do not rely on the previous bundle-less executable workaround if a proper app bundle can replace it cleanly.

Keep the existing Swift package structure where practical, but reorganize if necessary for correct macOS application packaging.

---

## Menu Bar Application

The macOS application should behave primarily as a lightweight menu-bar helper.

Avoid a persistent normal application window.

Provide a small menu-bar item.

The menu should show basic status such as:

- Ali Control
- Bluetooth state
- tablet connection state
- connected device name if available
- advertising state
- Quit

Optional if lightweight:

- Open at Login state
- Restart Advertising
- Disconnect

Do not add unnecessary complex UI.

---

## Open at Login

Add a user-controllable:

`Open at Login`

option.

Use a native modern macOS mechanism where practical.

Requirements:

- disabled/enabled by the user
- no hidden persistence mechanism
- no shell scripts
- no LaunchDaemon
- no elevated privileges

If the chosen API has macOS-version limitations, document them clearly.

If a reliable native login-item implementation is not practical for the supported deployment target,
implement the cleanest supported alternative and document it.

---

## BLE Automatic Startup

When the macOS app launches:

1. initialize BLE
2. wait for Bluetooth to become powered on
3. register the Ali Control service
4. begin advertising automatically

The user should not need to manually click "Start Server".

Avoid repeated advertising restart loops.

Use CoreBluetooth state callbacks.

---

## Android Reconnection

Improve Android BLE connection recovery.

After an unexpected disconnect:

- update UI immediately
- do not leave stale "Connected" state
- clean up old GATT resources
- allow the user to reconnect without restarting the Android app

Add lightweight automatic reconnection if it can be implemented safely and predictably.

Preferred behavior:

- if a previously connected Ali Control desktop disconnects unexpectedly,
  attempt a limited number of reconnect attempts
- use sensible delays/backoff
- stop retrying after a small bounded number of failures
- allow manual Connect at all times

Do not implement infinite reconnect loops.

Do not continuously scan in the background.

---

## macOS Disconnect / Reconnect

The macOS peripheral should correctly handle:

- Android disconnect
- Android reconnect
- Bluetooth toggled off/on
- application sleep/wake where practical
- state characteristic re-subscription
- advertising restart when needed

Do not create polling loops.

Use CoreBluetooth delegate callbacks.

---

## Connection State Accuracy

Ensure Android connection states represent the actual usable BLE connection.

Do not report `Connected` merely because the physical GATT link exists.

Connected should mean:

- GATT connection established
- services discovered
- command characteristic available
- state characteristic available
- notification subscription enabled
- transport ready to send commands

Use an intermediate state if needed.

Example:

- Disconnected
- Scanning
- Connecting
- Preparing
- Connected
- Error

---

## MTU / Message Reliability

Preserve the working ATT MTU negotiation introduced after TASK-003 physical testing.

Requirements:

- request an adequate MTU before service discovery / normal command flow
- gracefully handle MTU negotiation failure
- never silently truncate protocol payloads
- validate message size before writing
- surface transport errors instead of sending corrupted JSON

Do not change the canonical protocol semantics.

---

## Protocol Reliability

Preserve the canonical JSON message format.

Commands:

```json
{"type":"command","action":"ping"}
{"type":"command","action":"volume_up"}
{"type":"command","action":"volume_down"}
{"type":"command","action":"mute_toggle"}
{"type":"command","action":"play_pause"}
{"type":"command","action":"open_terminal"}
State examples:
{"type":"state","status":"connected"}
{"type":"state","action":"volume_up","status":"ok"}
Do not depend on JSON key order.
Malformed or oversized messages must fail safely.
Unsupported actions must never execute.
Android App Polish
Keep the current simple UI.
Do not redesign the product in this task.
Improve only practical rough edges such as:
- loading/connection states
- disabled controls when disconnected
- duplicate scan result handling
- error text
- stale state cleanup
- reconnect UX
- returning from Android settings
- immersive mode restoration
Do not add themes or customizable layouts yet.
macOS Permissions
Review all permissions actually required.
Preferred:
- Bluetooth permission only
Avoid Accessibility permission unless the Play/Pause implementation genuinely cannot work without it.
If Play/Pause requires Accessibility on the actual macOS version:
- handle the missing permission gracefully
- clearly tell the user that only Play/Pause is affected
- do not block volume, mute, Terminal, or BLE functionality
- do not automatically open settings unless user explicitly chooses to do so
Do not request broad permissions unnecessarily.
Performance / Resource Usage
This is a lightweight background helper.
Review the macOS implementation for:
- polling loops
- repeating timers
- busy waits
- excessive log output
- unnecessary threads
- repeated AppleScript execution while idle
- unnecessary retained objects
- repeated BLE initialization
Expected design:
- event-driven
- nearly idle CPU usage while no commands are being received
- low memory footprint
Do not introduce Electron, Node.js, Python servers, web servers, or similar heavy runtimes.
Android should also remain lightweight for the MT6580 tablet.
Logging
Keep useful development diagnostics but remove noisy debug spam.
Logs should be concise and useful for:
- Bluetooth state
- advertising state
- connection/disconnection
- protocol errors
- command execution errors
Do not log sensitive information.
Do not continuously log while idle.
Release Build
Ensure both platforms can produce clean builds.
Android
Produce:
- debug APK
- release APK if signing configuration allows an unsigned/release build without requiring private credentials
Do not create or commit private signing keys.
macOS
Produce a normal .app build.
Code signing may use local development/ad-hoc signing if needed for testing.
Do not require paid Apple Developer distribution/notarization for this task.
Document the distinction between:
- local development build
- future distributable/notarized build
Existing Kiosk Features
Preserve TASK-004 behavior:
- immersive fullscreen
- status/navigation bars hidden where possible
- boot launch
- Device / Settings screen
- Wi-Fi settings entry
- Bluetooth settings entry
- tablet brightness
- tablet volume
- tablet battery
- keep-screen-awake
- development escape mechanism
Do not introduce Device Owner or custom ROM changes.
Testing
Add or update unit tests for reliability logic where practical.
At minimum test:
- connection state transitions
- reconnect decision logic
- retry limits/backoff logic
- malformed/oversized protocol messages
- unsupported commands
- existing protocol fixtures
- Android transport-ready state logic
- macOS command dispatch
Run Android:
./gradlew clean assembleDebug testDebugUnitTest
Run macOS using the project's final build system.
At minimum:
swift test
and/or:
xcodebuild build
xcodebuild test
as appropriate after packaging changes.
Both projects must build and test successfully.
Manual End-to-End Test Target
After implementation, manually verify:
1. Install the latest Android APK.
2. Launch AliControlMac.app normally, not with swift run.
3. Verify the menu-bar item appears.
4. Verify BLE advertising starts automatically.
5. Connect from the tablet.
6. Verify connection reaches a true ready/Connected state.
7. Test:
   - Volume Up
   - Volume Down
   - Mute
   - Play/Pause
   - Open Terminal
8. Force-close the macOS app.
9. Verify Android detects disconnect.
10. Relaunch macOS app.
11. Verify Android can reconnect without restarting the tablet app.
12. Toggle Mac Bluetooth off and back on.
13. Verify advertising recovers.
14. Toggle tablet Bluetooth off and on.
15. Verify the Android app recovers cleanly.
16. Reboot the tablet.
17. Verify Android boot-launch still works.
18. Restart the Mac.
19. If Open at Login is enabled, verify AliControlMac launches automatically.
20. Verify no Terminal or Xcode process is required for normal use.
21. Check Activity Monitor while idle.
22. Confirm CPU usage remains effectively idle and memory usage is reasonable.
Documentation
Update README.md with:
- final macOS application launch instructions
- .app build location
- menu-bar usage
- Open at Login
- BLE automatic startup
- reconnect behavior
- permissions
- Android APK install/update instructions
- manual test checklist
- known limitations
- local build vs distributable/notarized build explanation
Out of Scope
Do NOT implement:
- Windows agent
- Wi-Fi transport
- USB transport
- custom layouts
- draggable/resizable controls
- editable shortcuts
- themes
- profiles
- performance monitoring
- CPU/GPU telemetry
- fan control
- Claude Code integration
- Codex integration
- arbitrary shell execution
- custom ROM
- Device Owner
- root
- cloud communication
- HTTP/TCP server
- external remote access