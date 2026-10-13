# TASK-006 — Action Library Foundation

> **Historical document.** Written when macOS was the reference platform. The action *library architecture* is unchanged, but the macOS-only actions were replaced by Windows equivalents (`spotlight`→`windows_search`, `mission_control`→`task_view`, `open_finder`→`open_explorer`, `open_activity_monitor`→`open_task_manager`), `ACCESSIBILITY` became `INPUT_SIMULATION`, and `MACOS` was retired from `Platform`. See [docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md) §8.

## Goal

Create a structured, platform-aware action library for Ali Control.

The purpose of this task is to move away from hardcoded individual control buttons
and introduce a reusable action registry that can later power:

- customizable layouts
- action picker UI
- profiles
- macOS / Windows platform-specific controls
- future developer integrations

Do not implement the customizable layout editor yet.

The existing Android ↔ macOS BLE functionality must continue to work.

---

## Core Concept

Every action should be represented as structured metadata.

Suggested model:

```text
ActionDefinition

- id
- label
- category
- supportedPlatforms
- dangerLevel
- permissionRequirement
Exact naming may differ if there is a good architectural reason.
Example:
id = volume_up
label = Volume Up
category = MEDIA
supportedPlatforms = [MACOS, WINDOWS]
dangerLevel = SAFE
permissionRequirement = NONE
Example macOS-only action:
id = mission_control
label = Mission Control
category = SYSTEM
supportedPlatforms = [MACOS]
dangerLevel = SAFE
permissionRequirement = ACCESSIBILITY
Platform Awareness
Introduce platform metadata from the beginning.
Supported platform enum should include at minimum:
- MACOS
- WINDOWS
Windows implementation itself remains out of scope.
The Android app must be able to determine which actions are relevant for the
currently connected platform.
Do not create separate Android applications or separate UI codebases for macOS and Windows.
The same Ali Control Android app should eventually show a platform-specific subset
of the shared action library.
Action Categories
Create categories at minimum for:
- MEDIA
- SYSTEM
- APPS
- WINDOW
- PRODUCTIVITY
Keep the categories generic enough to support both macOS and Windows later.
Initial Action Library
Implement the following first-batch actions.
Media
play_pause
Platforms:
- macOS
- Windows
Already implemented on macOS.
next_track
Platforms:
- macOS
- Windows
previous_track
Platforms:
- macOS
- Windows
volume_up
Platforms:
- macOS
- Windows
Already implemented.
volume_down
Platforms:
- macOS
- Windows
Already implemented.
mute_toggle
Platforms:
- macOS
- Windows
Already implemented.
System
lock_screen
Platforms:
- macOS
- Windows
Lock the current desktop session.
This must not log the user out.
sleep
Platforms:
- macOS
- Windows
Put the computer to sleep.
This action should be marked as requiring confirmation / elevated danger level.
Do not add shutdown or restart in this task.
display_sleep
Platforms:
- macOS
Turn the display off / sleep the display without sleeping the Mac if practical.
If this cannot be implemented reliably without excessive permissions,
degrade gracefully and document the limitation.
screenshot
Platforms:
- macOS
- Windows
Trigger the platform's standard screenshot action.
Prefer normal system behavior.
Do not capture or transmit screenshot image data through BLE.
show_desktop
Platforms:
- macOS
- Windows
Show the desktop / equivalent system action.
spotlight
Platforms:
- macOS only
Open Spotlight.
mission_control
Platforms:
- macOS only
Open Mission Control.
Apps
open_terminal
Platforms:
- macOS
- Windows
Existing macOS functionality should be migrated into the new action registry.
open_vscode
Platforms:
- macOS
- Windows
Open Visual Studio Code if installed.
If not installed:
- fail safely
- return an error acknowledgement
- do not crash
open_finder
Platforms:
- macOS only
Open Finder.
open_browser
Platforms:
- macOS
- Windows
Open the user's default web browser.
Do not hardcode Safari or Chrome.
open_spotify
Platforms:
- macOS
- Windows
Open Spotify if installed.
Fail safely if unavailable.
open_system_settings
Platforms:
- macOS
- Windows
Open system settings.
On macOS this should open System Settings.
open_activity_monitor
Platforms:
- macOS only
Open Activity Monitor.
Window Controls
minimize_window
Platforms:
- macOS
- Windows
Minimize the currently active window.
If Accessibility permission is required on macOS, this action must degrade gracefully.
Do not silently request broad permissions.
close_window
Platforms:
- macOS
- Windows
Close the currently active window.
This must not force-quit the application.
fullscreen_toggle
Platforms:
- macOS
- Windows
Toggle fullscreen for the current application/window where practical.
Productivity
copy
Platforms:
- macOS
- Windows
paste
Platforms:
- macOS
- Windows
undo
Platforms:
- macOS
- Windows
redo
Platforms:
- macOS
- Windows
These actions may require input-event APIs or Accessibility permission on macOS.
If so:
- mark the metadata correctly
- degrade gracefully without permission
- do not prevent other actions from working
Danger Levels
Introduce a simple danger classification.
Suggested values:
- SAFE
- CONFIRM
For this task:
SAFE examples:
- volume controls
- media controls
- open apps
- screenshot
- spotlight
- mission control
CONFIRM:
- sleep
Do not implement:
- shutdown
- restart
- logout
- destructive file actions
in TASK-006.
Permission Metadata
Action metadata should allow a permission requirement to be described.
Suggested values:
- NONE
- ACCESSIBILITY
Do not automatically request Accessibility permission.
If an action requires Accessibility and permission is not available:
- return a clear action error
- keep BLE connection healthy
- keep unrelated actions working
- do not crash
- do not repeatedly prompt
The existing Play/Pause behavior should be reviewed and categorized correctly.
Android Action Model
The Android side should have a local representation of available actions.
Do not hardcode all future UI buttons directly into MainActivity.
Create an action library / registry layer that can later be consumed by TASK-007.
Suggested structure:
android/
└── app/
    └── src/main/java/com/alicontrol/app/
        └── actions/
            ├── ActionDefinition.kt
            ├── ActionCategory.kt
            ├── Platform.kt
            ├── DangerLevel.kt
            ├── PermissionRequirement.kt
            └── ActionLibrary.kt
Exact organization may differ if justified.
macOS Action Registry
Replace the current growing hardcoded dispatcher switch with a cleaner structured registry
or equivalent maintainable dispatch architecture.
The protocol should still send stable action IDs such as:
{"type":"command","action":"next_track"}
The macOS agent should:
1. receive the action ID
2. validate it against the supported action registry
3. verify platform support
4. check any required permission
5. execute the mapped implementation
6. return ok or error
Do not allow arbitrary commands.
Do not allow shell text supplied by the tablet.
The desktop agent owns all executable behavior.
Protocol
Keep the existing transport-independent message envelope.
Do not change existing BLE UUIDs.
Existing actions must remain backward compatible.
Add the new action IDs only.
Example:
{"type":"command","action":"open_vscode"}
Example acknowledgement:
{"type":"state","action":"open_vscode","status":"ok"}
On failure:
{"type":"state","action":"open_vscode","status":"error"}
Do not depend on JSON key order.
Android UI
Do not implement the full action picker or layout editor yet.
For TASK-006, update the current control screen enough to manually test the new actions.
A simple temporary categorized list/grid is acceptable.
The goal is functional verification, not final UI design.
Keep the UI clean and lightweight.
Do not add every action to one overcrowded screen if that makes the interface unusable.
A simple temporary second action screen is acceptable if needed.
TASK-007 will replace this with the customizable layout system.
Existing Features
Preserve:
- BLE connection
- reconnect logic
- MTU handling
- kiosk mode
- launch on boot
- Device / Settings
- menu-bar macOS app
- Open at Login
- BLE advertising
- existing five remote controls
No regression is acceptable in these areas.
Performance
Keep both applications event-driven.
Do not introduce:
- polling loops
- background action checks
- busy waits
- web servers
- heavy dependencies
Action metadata should be static/in-memory.
Security
Do not implement generic remote execution.
Never accept messages such as:
run this shell command
or:
execute arbitrary application path
The tablet sends only predefined action IDs.
The Mac implementation decides what each ID means.
No HTTP, TCP server, cloud service, or internet-based remote access should be introduced.
Testing
Add unit tests for:
- action registry completeness
- duplicate action IDs
- category metadata
- platform support metadata
- danger levels
- permission requirements
- dispatch of supported actions
- rejection of unsupported action IDs
- action failure acknowledgement
- existing protocol fixture compatibility
- existing commands remain functional
Where practical, use fake/mocked action executors rather than invoking real OS actions during unit tests.
Do not open apps or manipulate the real Mac during automated tests.
Build Verification
Android:
./gradlew clean assembleDebug testDebugUnitTest
macOS:
swift build
swift test
and where applicable:
xcodebuild build
xcodebuild test
Rebuild the local .app using the existing packaging script.
All tests must pass.
Manual Test Target
After implementation, manually verify at minimum:
1. Launch AliControlMac.app.
2. Connect the tablet.
3. Existing actions still work.
4. Test:
   - Play/Pause
   - Next Track
   - Previous Track
   - Volume Up
   - Volume Down
   - Mute
   - Lock Screen
   - Display Sleep
   - Screenshot
   - Show Desktop
   - Spotlight
   - Mission Control
   - Terminal
   - VS Code
   - Finder
   - Default Browser
   - Spotify
   - System Settings
   - Activity Monitor
   - Minimize Window
   - Close Window
   - Fullscreen Toggle
   - Copy
   - Paste
   - Undo
   - Redo
5. Verify unsupported/unavailable application actions fail safely.
6. Verify an Accessibility-dependent action fails cleanly if permission is absent.
7. Verify normal BLE controls continue working afterward.
Sleep should be tested last because it intentionally interrupts the session.
Documentation
Update README.md with:
- action library architecture
- available actions
- platform support
- danger levels
- permission-dependent actions
- known macOS limitations
- how future Windows implementations will plug into the same action IDs
Out of Scope
Do NOT implement:
- Windows agent implementation
- custom layout editor
- drag/drop controls
- resizing widgets
- user-created action IDs
- arbitrary custom shell commands
- profiles
- themes
- performance monitoring
- Claude Code / Codex integrations
- fan control
- shutdown
- restart
- logout
- cloud services
- HTTP/TCP servers