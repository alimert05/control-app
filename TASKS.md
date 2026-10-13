# Ali Control — Task Roadmap

## Project Status

Ali Control is now a Windows-focused control-panel system built around:

    Android Tablet
          │
          │ Bluetooth Low Energy
          ▼
    Windows 11 Desktop Agent

The previous macOS desktop agent has been removed.

The Android application remains the primary UI and shared control surface.

The Windows agent is responsible for:

- BLE peripheral/server behaviour
- Windows actions
- telemetry
- media state
- artwork
- system tray integration
- startup behaviour
- platform capabilities

The current development priority is to finish the Windows version as a polished,
stable product before expanding into more advanced integrations.

---

# Core Product Principles

All future tasks should preserve the following principles.

## Local First

Core control should remain local.

Current primary transport:

    Bluetooth Low Energy

Do not introduce cloud dependencies for core PC control.

Avoid adding LAN servers unless there is a strong future requirement.

---

## Allowlisted Remote Actions

The Android tablet must never become a generic remote shell.

The tablet sends predefined action/workflow IDs.

The Windows agent maps those IDs to controlled implementations.

Do not introduce:

- arbitrary PowerShell
- arbitrary cmd commands
- arbitrary executable paths
- arbitrary scripts
- arbitrary remote code execution

---

## Lightweight Android Client

Primary Android target:

- Alcatel 1T 7 8068
- Android 8.1
- API 27
- MT6580
- armeabi-v7a

The Android application should remain suitable for low-end hardware.

Prefer:

- Kotlin
- XML Views
- lightweight custom views
- bounded caches
- shared state
- simple persistence

Avoid:

- Jetpack Compose
- unnecessarily heavy frameworks
- expensive background animation
- duplicated polling
- unnecessary databases

---

## Windows-First Desktop Architecture

Ali Control now targets Windows as the only desktop platform.

New desktop features should use Windows-native mechanisms where practical.

Prefer:

- .NET
- WinRT
- Win32
- standard Windows APIs
- standard-user permissions

Avoid requiring administrator privileges unless a future feature explicitly justifies it.

---

## Shared State

Telemetry, media and artwork should be collected once and shared across the application.

Do not create one sampler/provider per widget.

Application-scoped state should remain the source of truth.

---

## Safe Hardware Behaviour

If a sensor or hardware feature cannot be retrieved reliably:

- report unavailable
- disable the capability
- do not fabricate values
- do not use fragile undocumented hacks solely to display a number

---

# Completed Foundation

## TASK-001 — Android Control Panel Foundation

Status:

    COMPLETE

Implemented:

- native Android application
- Kotlin
- XML Views
- landscape control-panel UI
- low-end tablet support
- basic navigation
- initial control surface
- Android project structure

---

## TASK-002 — Bluetooth Transport & Pairing

Status:

    COMPLETE

Implemented:

- Android BLE central/client
- desktop BLE peripheral model
- device discovery
- GATT connection
- service discovery
- command writes
- state notifications
- reconnect behaviour
- protocol framing
- MTU handling
- bounded reconnect retries

Current Windows implementation supports both the original service UUID and a compact
service alias for Windows adapters that cannot advertise the full 128-bit UUID.

---

## TASK-003 — Desktop Agent & Core Controls

Status:

    COMPLETE

Originally implemented for macOS and later migrated to Windows.

Current Windows implementation includes:

- native .NET tray agent
- BLE GATT server
- action dispatch
- acknowledgements
- predefined system actions
- protocol tests
- safe action registry

The old macOS implementation has been removed.

---

## TASK-004 — Device / Kiosk Mode

Status:

    COMPLETE

Implemented:

- immersive fullscreen UI
- landscape mode
- keep screen awake
- boot behaviour
- Device Settings
- brightness controls
- tablet volume controls
- Wi-Fi settings access
- Bluetooth settings access
- safe kiosk exit

No root, Device Owner, or custom ROM is required.

---

## TASK-005 — Polish, Packaging & Reliability

Status:

    COMPLETE

Implemented:

- improved connection lifecycle
- app packaging
- desktop agent lifecycle
- reconnect handling
- startup behaviour
- user-friendly desktop helper/tray behaviour
- error handling
- reliability improvements

---

# Action and Dashboard Foundation

## TASK-006 — Action Library Foundation

Status:

    COMPLETE

Implemented:

- central ActionLibrary
- categories
- action metadata
- confirmation requirements
- permission metadata
- stable action IDs
- platform/action support
- All Actions interface

Current Windows actions include:

### Media

- play_pause
- next_track
- previous_track
- volume_up
- volume_down
- mute_toggle

### System

- lock_screen
- sleep
- display_sleep
- screenshot
- show_desktop
- windows_search
- task_view

### Applications

- open_terminal
- open_vscode
- open_explorer
- open_browser
- open_spotify
- open_system_settings
- open_task_manager

### Window

- minimize_window
- close_window
- fullscreen_toggle

### Productivity

- copy
- paste
- undo
- redo

### Protocol

- ping

Legacy macOS action IDs are migrated where appropriate:

    spotlight
    → windows_search

    mission_control
    → task_view

    open_finder
    → open_explorer

    open_activity_monitor
    → open_task_manager

---

## TASK-007 — Multi-Page Dashboard & Widget UI Foundation

Status:

    COMPLETE

Implemented:

- dashboard page model
- multiple pages
- widget abstraction
- widget rendering
- dashboard pager
- Action widgets
- Action Group widgets
- Metric widgets
- Gauge widgets
- Clock widget
- Daily Overview widget
- Media widget
- Slider widget
- Shortcut widget

---

## TASK-008 — Dashboard Refinement & Customization Engine

Status:

    COMPLETE

Implemented:

- 6 × 4 logical dashboard grid
- drag/move widgets
- resize widgets
- add/remove widgets
- collision detection
- placement validation
- page create
- page rename
- page reorder
- page delete
- dashboard persistence
- layout validation
- Edit Mode
- remote actions disabled while editing
- Windows action migration support
- refined default dashboard

The dashboard remains locally persisted on the tablet.

---

# Live Desktop State

## TASK-009 — Live System & Media Data

Status:

    COMPLETE

Implemented:

- application-scoped DesktopState
- live CPU state
- live RAM state
- media metadata
- playback state
- progress
- duration
- shared widget binding
- stale-state handling
- disconnect cleanup
- capability-aware data
- bounded BLE live-state protocol

Windows implementation additionally supports GPU telemetry.

---

## TASK-009A — Media Artwork over BLE

Status:

    COMPLETE

Implemented:

- artwork IDs
- request-on-cache-miss
- processed/compressed artwork
- bounded BLE artwork transfer
- binary chunk protocol
- Android transfer validation
- Android artwork cache
- stale-track protection
- disconnect cancellation
- transfer priority below command acknowledgements
- responsive Media widget artwork rendering

Current target artwork characteristics:

- approximately 160 × 160
- JPEG
- bounded compressed size
- bounded decoded Android cache

---

# Windows Migration

## Windows Desktop Migration

Status:

    COMPLETE

Ali Control was migrated from macOS to Windows.

Implemented:

- macOS agent removed
- Windows native .NET tray agent
- Windows BLE GATT server
- Windows action implementations
- Windows telemetry
- Windows GPU telemetry
- Windows system media sessions
- Windows artwork provider
- desktop_info protocol
- capabilities protocol
- Windows-aware Android state
- Windows-native default dashboard actions
- legacy action migration
- Open at Startup
- diagnostics
- logs
- Windows build/test pipeline

Current Windows automated test status at migration completion:

    468 / 468 tests passing

Current Android automated test status at migration completion:

    330 tests passing

---

# Current Integration Issue

## Windows Spotify Media Delivery

Status:

    OPEN BUG / INTEGRATION ISSUE

The Windows agent can retrieve Spotify media information locally through Windows system
media sessions.

Desktop-side testing confirmed access to:

- title
- artist
- album
- playback state
- position
- duration
- artwork

However, during the first real tablet test, Spotify media state did not appear correctly
on the Android tablet.

This is treated as an integration/debugging issue, not a platform limitation.

Before relying heavily on the Media profile, verify end-to-end delivery:

    Spotify
       ↓
    Windows Media Session
       ↓
    Windows Live State Publisher
       ↓
    BLE
       ↓
    Android Protocol Parser
       ↓
    DesktopStateStore
       ↓
    Media Widget

Do not replace this with fake data or Spotify cloud authentication.

---

# Planned Product Tasks

## TASK-010 — Dashboard Modes & Profiles

Status:

    COMPLETE

(Automated tests and the Android build pass. The manual tablet checklist in the README,
steps 16–20, has not been run — see Known limitations below.)

Goal:

Add independently customizable dashboard profiles above the existing page system.

Built-in profiles:

- Default
- Dev
- Game
- Media

Concept:

    DashboardProfile
    └── Dashboard
        └── Pages
            └── Widgets

Implemented:

- profile switcher (compact header popup) and Manage Profiles dialog
- four built-in profiles with stable ids (`default`, `dev`, `game`, `media`)
- custom profiles: create (empty), duplicate (deep copy), rename, reorder, delete
- set startup default; startup order: last selected → startup default → built-in default →
  first valid
- reset a built-in profile to its template (a custom one to empty); built-ins can't be deleted
- independent customization per profile through the existing TASK-008 editor
- profile persistence (schema 3, `dashboard_profiles_json`) with per-profile recovery
- one-time, restart-safe migration of the existing single-dashboard layout into Default,
  with the retired-macOS-action-id migration preserved
- Edit Mode integration: switching leaves Edit Mode; every edit is already saved

Profile switching does not:

- disconnect BLE
- restart telemetry
- clear media state
- clear artwork
- recreate DesktopStateStore

(The profile layer holds no reference to any of them; this is enforced by tests.)

Android automated test status at TASK-010 completion:

    501 tests passing   (330 existing + 171 new profile tests)

Windows agent: not modified for TASK-010 (468 / 468 at migration completion).

Known limitations:

- Not verified on a physical tablet.
- A profile opens on its first page; the last-viewed page is not remembered per profile.
- The Default profile keeps the existing Dev page rather than the suggested System page.
- The Spotify end-to-end media issue (below) is unchanged and is not worked around.

Detailed specification:

    docs/tasks/TASK-010.md

User documentation:

    README.md → Dashboard profiles

---

## TASK-011 — Themes & Visual Customization

Status:

    PLANNED

Goal:

Make Ali Control visually customizable without sacrificing performance on the low-end
tablet.

Potential features:

- accent colors
- profile-specific themes
- card styles
- widget styles
- background styles
- icon styles
- custom wallpaper/background
- subtle gradients
- media artwork-derived accents
- lightweight glow/pulse effects
- acknowledgement animations
- connection/error animations
- ambient idle mode
- compact status styling

Possible profile-specific appearance:

    Default → neutral
    Dev     → technical
    Game    → performance-focused
    Media   → artwork-driven

Performance rules:

- avoid expensive full-screen video backgrounds
- avoid continuous high-cost blur
- avoid high-frequency animation
- prefer native low-cost drawing
- preserve responsive touch performance
- keep memory use bounded

Do not turn the UI into a GPU-heavy animated dashboard.

---

## TASK-012 — Advanced System Controls

Status:

    PLANNED

Goal:

Expand the set of safe Windows controls available from the tablet.

Potential additions:

- brightness/display controls where supported
- monitor power controls
- additional audio controls
- audio-device switching
- microphone mute
- application-specific volume controls
- Wi-Fi controls where safely supported
- Bluetooth shortcuts
- power presets
- performance presets
- predefined workstation workflows
- additional window controls
- virtual desktop controls
- system information shortcuts

All actions must remain allowlisted.

Do not introduce arbitrary shell execution.

High-impact actions should use confirmations.

---

## TASK-013 — Fan / Hardware Integrations

Status:

    PLANNED

Goal:

Expose richer Windows hardware information and controls when reliable hardware APIs are
available.

Potential telemetry:

- CPU temperature
- GPU temperature
- fan RPM
- CPU package power
- GPU power
- VRAM usage
- CPU clock
- GPU clock
- storage temperatures
- motherboard sensors

Potential controls:

- fan profiles
- cooling presets
- performance profiles

Constraints:

- no fake values
- avoid fragile vendor hacks
- avoid kernel-driver requirements unless explicitly evaluated
- do not require administrator privileges unnecessarily

Hardware integrations may vary by machine.

A capability-based provider architecture should be used.

---

## TASK-014 — AI Developer Integrations

Status:

    PLANNED

Goal:

Turn the Dev profile into a developer control center.

Potential live information:

- Claude Code running / idle
- Codex running / idle
- active project
- active repository
- Git branch
- clean/dirty status
- tests
- build status
- last activity
- process CPU/RAM
- task state

Potential safe actions:

- Open VS Code
- Open Terminal
- Open repository
- Git status
- Run predefined tests
- Run predefined build
- Start predefined Claude workflow
- Start predefined Codex review
- Open logs
- Open project folder

Future workflow model:

    Tablet
      ↓
    predefined workflow ID
      ↓
    Windows Agent
      ↓
    local project registry
      ↓
    controlled developer workflow

Do not allow the tablet to send arbitrary terminal commands.

Potentially dangerous operations such as:

- commit
- push
- merge
- reset
- destructive Git actions

must require explicit confirmation and should be considered separately.

Token/quota/context information should only be displayed when Claude Code or Codex
provides a reliable local or official machine-readable source.

Do not fabricate usage information.

---

## TASK-015 — Weather & Calendar Integrations

Status:

    PLANNED

Goal:

Complete the Daily Overview experience.

Planned weather information:

- current condition
- temperature
- daily high/low
- compact forecast
- weather icon

Planned calendar information:

- next event
- upcoming classes
- event time
- event location
- Daily Overview schedule

Preferred calendar integration:

- Microsoft account
- Outlook Calendar
- Microsoft Graph

Potential university calendar information can be sourced through the user's existing
Microsoft calendar where available.

Daily Overview target concept:

    ┌──────────────────────────────────┐
    │ 10:42      Wednesday, 7 October │
    │                                  │
    │ 12°C · Cloudy                    │
    │                                  │
    │ Next                             │
    │ Machine Learning Practical       │
    │ 13:10 · Informatics Forum        │
    │                                  │
    │ Later                            │
    │ ...                              │
    └──────────────────────────────────┘

The widget should remain useful even when weather or calendar data is temporarily
unavailable.

---

# Deferred Product Work

## Installer / Distribution

Status:

    DEFERRED

Potential future work:

- installer
- MSIX
- code signing
- versioned releases
- auto-update
- release channel
- public distribution packaging

Functional development takes priority over installer polish.

---

## Authenticated BLE Pairing

Status:

    DEFERRED

Current BLE control is allowlisted but does not require authenticated Ali Control
pairing.

Potential future work:

- first-time pairing
- trusted tablet identity
- challenge/response
- shared secret
- encrypted application-level session
- device revocation

This should be implemented before treating Ali Control as a broadly distributed
security-sensitive product.

---

## Bluetooth Adapter Hot-Plug Detection

Status:

    DEFERRED

Current limitation:

A Bluetooth adapter connected after Ali Control starts may require restarting the
Windows agent.

Future work may add dynamic adapter discovery.

---

## Windows Packaging Optimization

Status:

    DEFERRED

Current self-contained build prioritizes runtime memory behaviour over smallest file
size.

Future optimization may evaluate:

- framework-dependent deployment
- trimmed deployment
- installer-managed runtime
- code signing
- startup performance
- memory usage

---

# Suggested Development Order

Recommended order from the current state:

    Fix Windows Spotify media delivery
            ↓
    TASK-010 — Dashboard Modes & Profiles
            ↓
    TASK-011 — Themes & Visual Customization
            ↓
    TASK-012 — Advanced System Controls
            ↓
    TASK-013 — Fan / Hardware Integrations
            ↓
    TASK-014 — AI Developer Integrations
            ↓
    TASK-015 — Weather & Calendar Integrations
            ↓
    Packaging / Authentication / Release Polish

TASK-013 and TASK-014 may be reordered depending on development priorities.

---

# Current Milestone

The current product already provides:

- Android dedicated control panel
- Windows tray agent
- BLE connection
- predefined Windows actions
- dashboard customization
- multiple pages
- dashboard profiles (Default, Dev, Game, Media and user-created)
- CPU telemetry
- RAM telemetry
- GPU telemetry
- media state architecture
- artwork architecture
- capability negotiation
- kiosk mode
- device controls
- local-only operation

The next major product-layer milestone is:

    TASK-011 — Themes & Visual Customization

The current Spotify end-to-end media issue is still open and should be resolved before the
Media profile is relied on day to day.

---

# Definition of Done for Future Tasks

A task should only be marked complete when:

- implementation is finished
- existing behaviour is preserved
- automated tests pass
- build passes
- security assumptions remain valid
- performance remains appropriate for the tablet
- documentation is updated
- manual test items are clearly documented
- no fake/unverified capability is presented as functional

For Windows system integrations, automated tests should use abstractions/fakes and must
not trigger destructive real-system behaviour.

---

# Development Safety Rules

Automated tests must not:

- lock the real PC
- sleep the real PC
- shut down the PC
- close real user windows
- send real keyboard input
- manipulate real media playback
- change startup settings
- change Bluetooth settings
- perform destructive Git operations

Real hardware/system tests should be deliberate manual tests.

---

# Git / Agent Workflow

When Claude Code, Codex, or another coding agent implements a task:

- read TASKS.md
- read README.md
- read the task-specific specification
- inspect the existing implementation before changing architecture
- preserve working code
- add tests
- run the full relevant test suites
- do not install to the tablet automatically
- do not commit automatically
- do not push automatically

Recommended workflow:

    implementation
        ↓
    automated tests
        ↓
    code review / audit
        ↓
    manual Windows + tablet test
        ↓
    human commit
        ↓
    human push

---

# Current Task Summary

    [x] TASK-001 — Android Control Panel Foundation
    [x] TASK-002 — Bluetooth Transport & Pairing
    [x] TASK-003 — Desktop Agent & Core Controls
    [x] TASK-004 — Device / Kiosk Mode
    [x] TASK-005 — Polish, Packaging & Reliability
    [x] TASK-006 — Action Library Foundation
    [x] TASK-007 — Multi-Page Dashboard & Widget UI Foundation
    [x] TASK-008 — Dashboard Refinement & Customization Engine
    [x] TASK-009 — Live System & Media Data
    [x] TASK-009A — Media Artwork over BLE

    [x] macOS → Windows Desktop Migration

    [ ] Windows Spotify media end-to-end integration fix

    [x] TASK-010 — Dashboard Modes & Profiles
    [ ] TASK-011 — Themes & Visual Customization
    [ ] TASK-012 — Advanced System Controls
    [ ] TASK-013 — Fan / Hardware Integrations
    [ ] TASK-014 — AI Developer Integrations
    [ ] TASK-015 — Weather & Calendar Integrations

---

# Current Supported Product

    Android Tablet
          │
          │ BLE
          ▼
    Windows 11 PC

macOS is no longer an active supported target.

The project should continue evolving as a Windows control-panel product unless this
roadmap is explicitly changed.