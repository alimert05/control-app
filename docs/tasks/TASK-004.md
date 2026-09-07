# TASK-004 — Device / Kiosk Mode

## Goal

Turn the Android tablet into a dedicated Ali Control appliance.

The application should feel like the tablet's primary operating environment rather than a normal Android app.

Do not implement a custom ROM or modify the bootloader.

Target device:

- Alcatel 1T 7 8068
- Android 8.1
- API 27
- MediaTek MT6580
- armeabi-v7a

---

## Primary Objectives

Implement:

- persistent fullscreen / immersive mode
- hide Android status bar
- hide Android navigation bar where supported
- prevent accidental exits from the control interface
- support launch-on-boot
- add a dedicated Device / Settings area
- expose essential tablet controls inside the app
- preserve BLE control functionality from TASK-003

The app should remain lightweight.

---

## Kiosk Behaviour

The application should launch into fullscreen immersive mode.

Hide:

- status bar
- navigation bar

Restore immersive mode if system UI becomes visible after user interaction.

Use Android APIs compatible with API 27.

Do not use APIs requiring newer Android versions unless guarded appropriately.

Do not implement Device Owner provisioning yet unless absolutely required for a specific feature.

Do not lock the user into a state that cannot be exited during development.

Include a safe development escape mechanism.

For example:

- a hidden long-press gesture
- or a clearly documented development-only exit path

The escape mechanism must not be easy to trigger accidentally.

---

## Launch on Boot

Add support for launching Ali Control after Android boots.

Use a lightweight boot receiver compatible with Android 8.1.

Requirements:

- listen for system boot completion
- launch Ali Control after boot
- avoid unnecessary background services
- do not create polling loops

Document any Android limitations.

---

## Device / Settings UI

Create a dedicated in-app Device / Settings screen.

The user should be able to view or control essential tablet settings without leaving Ali Control where practical.

Include:

### Wi-Fi

Provide an entry point for Wi-Fi configuration.

For Android 8.1, if direct Wi-Fi network management would require excessive permissions or fragile APIs, it is acceptable to open the appropriate Android Wi-Fi settings screen.

Do not implement custom Wi-Fi credential storage.

### Screen Brightness

Provide an in-app brightness slider.

If changing global system brightness requires permission that should not be silently granted:

- detect the permission state
- provide a clear user flow to grant it
- fail safely

Do not request more privileges than necessary.

### Tablet Volume

Provide an in-app volume slider for the tablet's own media volume.

This is separate from remote Mac/Windows volume controls.

### Screen Timeout

Provide a simple screen behaviour setting.

At minimum support an option equivalent to:

- Keep screen awake while Ali Control is open

Avoid changing global system timeout settings unless necessary.

### Battery

Show current tablet battery percentage on the Device / Settings screen only.

Do not restore battery information to the main control dashboard.

### Bluetooth

Show basic local Bluetooth state.

Provide an entry point to Android Bluetooth settings if needed.

Do not replace the existing Ali Control BLE connection flow.

---

## Main UI

Keep the main screen focused on computer control.

Do not reintroduce:

- tablet battery
- tablet brightness
- tablet volume

to the main dashboard.

The main UI should remain minimal.

Keep the working controls from TASK-003:

- Volume Up
- Volume Down
- Mute
- Play/Pause
- Terminal

Preserve BLE connection status and Connect flow.

---

## Orientation

The application should remain landscape-first.

Prevent accidental orientation changes if practical.

Use a stable landscape orientation appropriate for the 7-inch tablet.

---

## Performance

This device is low-end hardware.

Avoid:

- animations that run continuously
- background polling loops
- unnecessary services
- large image assets
- heavy dependencies

Prefer event-driven updates.

Battery percentage does not need to refresh continuously at high frequency.

---

## Permissions

Use the minimum permissions required.

Document all permissions introduced in TASK-004.

Possible examples:

- RECEIVE_BOOT_COMPLETED
- WRITE_SETTINGS only if needed for system brightness

Do not add Accessibility permission.

Do not add Device Admin permissions unless a clear requirement exists.

Do not add network/server permissions unrelated to existing BLE functionality.

---

## Security and Safety

Do not:

- expose network ports
- add HTTP servers
- add remote shell execution
- add arbitrary command execution
- change bootloader/recovery/system partitions
- root the device
- uninstall system packages
- disable Android security mechanisms

This task is application-level kiosk behaviour only.

---

## Architecture

Keep device-specific functionality separate from the control UI.

Suggested structure:

```text
android/
└── app/
    └── src/main/java/com/alicontrol/app/
        ├── device/
        │   ├── DeviceController.kt
        │   ├── BrightnessController.kt
        │   ├── VolumeController.kt
        │   └── BatteryProvider.kt
        ├── kiosk/
        │   ├── KioskController.kt
        │   └── BootReceiver.kt
        └── ui/
            └── DeviceSettingsActivity.kt
Exact file names may differ if there is a good technical reason.
Do not put all device control logic directly inside an Activity.
Testing
Add unit tests for logic that can be tested without physical Android hardware.
Test where practical:
- brightness value conversions
- volume value conversions
- device state formatting
- kiosk state logic
- boot-launch decision logic
Run:
./gradlew clean assembleDebug testDebugUnitTest
The build and all tests must succeed.
Manual Test Target
After implementation, manual testing should verify:
1. Install updated APK.
2. Launch Ali Control.
3. App enters immersive fullscreen.
4. Status/navigation bars are hidden.
5. Existing BLE connection to macOS still works.
6. Existing remote controls still work.
7. Open Device / Settings.
8. Tablet volume slider works.
9. Brightness slider works or correctly guides the user through required permission.
10. Battery percentage is correct.
11. Wi-Fi settings entry works.
12. Bluetooth settings entry works.
13. Keep-screen-awake setting works.
14. Reboot tablet.
15. Ali Control launches after boot.
16. Development escape path works.
17. App does not crash after leaving and returning from Android settings.
Documentation
Update README.md with:
- kiosk behaviour
- boot launch behaviour
- Device / Settings screen
- permissions
- development escape mechanism
- manual testing instructions
- Android 8.1 limitations
Out of Scope
Do NOT implement:
- custom ROM
- boot animation replacement
- root
- Device Owner provisioning
- Android launcher replacement
- system app installation
- Windows agent
- Wi-Fi transport
- USB transport
- performance monitoring
- custom layouts
- themes
- custom shortcuts
- profiles
- telemetry
- hardware/fan control