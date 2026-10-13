# TASK-010 — Dashboard Modes & Profiles

## Status

Implemented. The specification below is unchanged; the user-facing description is the README's
*Dashboard profiles* section and the task's status and known limitations are in
[TASKS.md](../../TASKS.md).

## Goal

Introduce multiple independently customizable dashboard profiles/modes to Ali Control.

Ali Control currently has a single dashboard configuration with multiple pages.

TASK-010 changes this model so the tablet can contain multiple complete dashboard configurations such as:

- Default
- Dev
- Game
- Media

Each profile owns its own complete dashboard:

    DashboardProfile
    └── Dashboard
        ├── Page
        │   └── Widgets
        ├── Page
        │   └── Widgets
        └── ...

Switching profile changes the complete dashboard/page set without disconnecting from the Windows PC or restarting live telemetry/media state.

This task is primarily Android-side.

No major Windows agent changes should be required.

---

## Current Platform

Ali Control is now a Windows-only desktop control system:

    Android Tablet
          │
          │ BLE
          ▼
    Windows Agent

The previous macOS agent has been removed.

TASK-010 must use the current Windows action IDs, capabilities and live-state system.

Do not reintroduce macOS concepts or platform abstractions that are no longer needed.

---

## Existing Systems That Must Be Reused

TASK-010 must build on the existing implementation rather than replacing it.

Reuse:

- Dashboard
- DashboardPage
- DashboardWidget
- WidgetPlacement
- DashboardEditor
- DashboardSerializer
- DashboardStore
- current SharedPreferences persistence
- DashboardPagerAdapter
- DashboardPageRenderer
- DashboardWidgetViewFactory
- Edit Mode
- page CRUD
- widget move/resize
- widget add/remove
- ActionLibrary
- DesktopStateStore
- live telemetry
- live media state
- artwork state/cache
- BLE connection state
- capability state
- Windows action support

Do not implement parallel copies of these systems.

---

## Profile Model

Introduce a model equivalent to:

    DashboardProfile
    ├── id
    ├── name
    ├── order
    ├── builtIn
    ├── dashboard
    └── optional future metadata

Exact naming may differ if justified by the current architecture.

A profile must own exactly one existing Dashboard object.

Do not duplicate the Dashboard/Page/Widget model hierarchy.

---

## Stable IDs

Built-in profile IDs:

    default
    dev
    game
    media

Profile IDs must be separate from display names.

Example:

    id   = "dev"
    name = "Development"

Renaming the profile must never change its ID.

Custom profiles should use generated stable IDs.

---

## Built-In Profiles

Ship with four built-in profiles:

- Default
- Dev
- Game
- Media

All must use the existing:

    6 columns × 4 rows

layout model.

Every built-in template must be valid under the existing widget placement and size constraints.

---

## Default Profile

Purpose:

General everyday Windows control dashboard.

Suggested pages:

- Dashboard
- Media
- System

Suggested Dashboard content:

- Daily Overview
- CPU
- RAM
- GPU
- Windows Search
- Task View
- File Explorer
- Browser
- Lock
- Screenshot
- Device Settings
- compact media controls
- volume controls

Preserve the visual direction of the existing Dashboard where practical.

Do not unnecessarily redesign working UI.

---

## Dev Profile

Purpose:

Development and programming workflow.

Suggested pages:

- Dev
- Tools
- System

Suggested actions/widgets:

- VS Code
- Windows Terminal
- Browser
- File Explorer
- Task Manager
- Windows Search
- Screenshot
- CPU
- RAM
- GPU
- Device Settings

TASK-010 must NOT implement:

- Claude Code controls
- Codex controls
- Git controls
- test/build workflows
- project registry

Those belong to TASK-014.

The Dev profile may reserve visual space for future TASK-014 widgets, but should not create fake/non-functional widgets.

---

## Game Profile

Purpose:

Gaming-oriented system and media dashboard.

Suggested pages:

- Game
- Media
- System

Suggested widgets/actions:

- CPU
- RAM
- GPU
- media playback
- volume controls
- mute
- Spotify launcher
- Task Manager
- show desktop
- lock
- Device Settings

Only use action IDs currently defined by ActionLibrary.

Do not invent:

- Steam actions
- Discord actions
- fan controls
- temperature controls
- game detection

unless those already exist.

Those features belong to later tasks.

---

## Media Profile

Purpose:

Large Now Playing and media control experience.

Suggested pages:

- Now Playing
- Controls
- Apps

Primary page should use the existing MEDIA widget at a large size.

Reuse:

- Windows Media Session metadata
- title
- artist
- album
- playback state
- timeline
- artwork
- artwork cache
- Play/Pause
- Previous
- Next
- volume controls
- mute

Do not create another media-state implementation.

Do not create another artwork cache.

The existing DesktopStateStore remains the single source of truth.

---

## Current Spotify Physical Issue

The current Windows agent uses Windows system media sessions and is designed to support Spotify metadata and artwork.

However, during the first real tablet test the tablet did not receive Spotify media state correctly.

TASK-010 must not hide or work around that issue with fake data.

Profile implementation should continue using the existing media model.

Spotify/Windows media debugging should be handled separately from the profile system.

---

## Profile Switcher

Add a compact mode/profile switcher to the dashboard header.

Preferred conceptual UI:

    [ Default ▾ ]

Tapping opens a compact selector:

    Default
    Dev
    Game
    Media
    ────────
    Manage Profiles

The switcher should remain usable with touch.

Do not make the header substantially taller.

Preserve the existing header controls.

---

## Profile Switching Behaviour

When switching profiles:

1. safely leave Edit Mode if active
2. persist any current layout changes
3. change active profile
4. display that profile's dashboard
5. navigate to its preferred/default page
6. update page indicators
7. keep BLE connected
8. keep Windows desktop state
9. keep telemetry subscriptions
10. keep media state
11. keep artwork cache
12. keep capability state

Profile switching must not reconnect to the Windows agent.

---

## Independent Customization

Each profile must be fully independent.

Example:

    Default
    └── custom dashboard A

    Dev
    └── custom dashboard B

    Game
    └── custom dashboard C

    Media
    └── custom dashboard D

Changing a widget in Dev must not modify Default.

Reuse all existing TASK-008 customization behaviour:

- move
- resize
- add widget
- remove widget
- page create
- page rename
- page reorder
- page delete

Edit Mode must operate only on:

    activeProfile.dashboard

---

## Persistence

Persist:

- profile schema version
- profile list
- profile IDs
- profile names
- profile order
- built-in/custom state
- active profile ID
- default profile ID
- each profile dashboard

Use a lightweight persistence strategy consistent with the existing Android application.

Prefer extending the existing JSON / SharedPreferences persistence.

Do not add:

- Room
- SQLite
- cloud storage
- account synchronization

unless absolutely necessary.

---

## Persistence Schema

Introduce a clearly versioned profile persistence schema.

Example conceptual structure:

    {
      "schemaVersion": 3,
      "activeProfileId": "dev",
      "defaultProfileId": "default",
      "profiles": [
        {
          "id": "default",
          "name": "Default",
          "builtIn": true,
          "order": 0,
          "dashboard": {}
        }
      ]
    }

Exact version number should follow the repository's existing schema history.

Do not blindly use `3` if another version is already current.

---

## Existing Layout Migration

Existing users currently have a single dashboard layout.

TASK-010 must migrate that layout safely.

Required behaviour:

    existing dashboard
            ↓
    Default profile

The user's existing layout must become the Default profile dashboard.

Then create new shipped templates for:

- Dev
- Game
- Media

Do not silently discard an existing custom dashboard.

Migration must:

- run once
- be deterministic
- be tested
- survive app restart
- handle corrupted legacy data safely

---

## Existing Windows Action Migration

TASK-010 must preserve the current Windows action IDs.

The existing migration from old macOS IDs must remain functional:

    spotlight
    → windows_search

    mission_control
    → task_view

    open_finder
    → open_explorer

    open_activity_monitor
    → open_task_manager

Do not remove this compatibility when changing persistence.

Layouts containing migrated action IDs must continue to load correctly.

---

## Custom Profiles

Support user-created profiles.

Provide:

    Add Profile

A user should be able to:

- create an empty profile
- duplicate an existing profile

New profiles must receive a generated stable ID.

Example:

    custom_7f912...

Do not derive IDs only from profile names.

---

## Rename Profile

Allow profile display names to be renamed.

Validate:

- trimmed
- not empty
- sensible maximum length

Suggested maximum:

    24 characters

The profile ID must remain unchanged.

---

## Duplicate Profile

Provide:

    Duplicate Profile

Example:

    Dev
    ↓
    Duplicate
    ↓
    Dev Copy

The copy must contain independent copies of:

- dashboard
- pages
- widgets
- widget placement/config

Do not share mutable state between source and copy.

Generate new identifiers where identity uniqueness is required.

---

## Delete Profile

User-created profiles may be deleted.

Built-in profiles:

- default
- dev
- game
- media

should not be permanently deleted.

They may instead be reset.

Rules:

- cannot delete the final available profile
- confirm before deleting a customized user profile
- deleting active user profile must select another valid profile
- deletion must not affect other profiles

---

## Reset Built-In Profile

Built-in profiles should expose:

    Reset to Default Layout

This restores the shipped template for only that profile.

Require confirmation.

Do not reset:

- other profiles
- BLE settings
- device settings
- live media state
- artwork cache
- unrelated application settings

---

## Reset Custom Profile

For a custom profile, reset may simply restore an empty valid dashboard.

If the profile was duplicated from another profile, do not introduce complex historical template tracking solely to restore the original source.

Keep behaviour predictable.

---

## Reorder Profiles

Support changing profile order.

A simple management UI using:

- Move Left
- Move Right

or:

- Move Up
- Move Down

is sufficient.

Do not require drag-and-drop management.

The profile switcher must use persisted order.

---

## Startup Profile Policy

Persist:

    lastSelectedProfileId
    defaultProfileId

Startup resolution order:

    last selected profile
    → configured default profile
    → built-in default
    → first valid profile

This must be deterministic and tested.

If stored IDs are missing/corrupted, recover safely.

---

## Set Default Profile

Allow the user to mark a profile as the configured default.

Example:

    Set as Startup Default

This is a fallback target.

The application should normally restore the last selected profile.

Do not confuse:

    defaultProfileId

with the built-in profile ID:

    default

---

## Edit Mode

If the user attempts to switch profile during Edit Mode:

Preferred behaviour:

    save current changes
    → exit Edit Mode
    → switch profile

No edits should be silently discarded.

While Edit Mode is active:

- remote actions remain disabled as before
- only active profile layout changes
- profile manager must not corrupt editing state

---

## Live State

DesktopStateStore remains application-scoped.

Do not create one DesktopStateStore per profile.

The following state remains global:

- Windows connection
- desktop name
- capabilities
- CPU
- RAM
- GPU
- media metadata
- playback state
- playback progress
- artwork
- artwork cache

Switching profile must only change presentation/layout.

---

## Artwork

Reuse the existing artwork pipeline.

Do not request artwork again merely because:

    Default → Media

was selected.

If artwork already exists in the application cache, it should immediately appear in the Media profile.

Do not create profile-local bitmap caches.

---

## Capability Awareness

The Windows agent sends capabilities dynamically.

Profile widgets must continue to respect them.

Example:

    telemetry.gpu unavailable

should cause the GPU widget to display its existing unavailable/disabled state.

Do not delete the GPU widget from the profile.

Capabilities are runtime availability information, not persistent layout mutation.

---

## Windows-Only Product

Ali Control is now Windows-only.

Do not introduce:

- Mac profile
- Windows profile
- Platform-specific duplicate profile models

Profiles describe user contexts:

- Default
- Dev
- Game
- Media

not operating systems.

All built-in templates should now use Windows actions.

---

## Header

Integrate profile switching with the existing header.

The header must continue to provide the current important controls such as:

- connection status / desktop name
- profile switcher
- Edit
- Device
- other existing relevant controls

Avoid visual clutter.

If needed, move lower-priority profile management actions into a dialog rather than making the header taller.

---

## Profile Management UI

Provide a lightweight profile management dialog/screen.

Functions:

- create
- duplicate
- rename
- reorder
- set default
- reset
- delete custom profile

Keep it consistent with the current XML Views architecture.

Do not introduce Jetpack Compose.

---

## Performance

Target hardware:

- Alcatel 1T 7 8068
- Android 8.1
- API 27
- MT6580
- armeabi-v7a

Requirements:

- only active profile rendered
- inactive profiles remain serialized/data-only
- no expensive transition animation
- no repeated bitmap duplication
- no database
- no repeated live-state subscriptions
- no BLE reconnect
- no telemetry restart

Profile switching should feel immediate.

---

## Security

Profiles must not weaken the current remote-action security model.

A profile may store only:

- known widget types
- known ActionLibrary action IDs
- widget configuration
- layout data

Never store/execute:

- arbitrary PowerShell
- arbitrary cmd text
- arbitrary executable paths
- arbitrary scripts
- arbitrary URLs
- arbitrary action IDs

All remote Windows actions remain allowlisted by the Windows agent.

---

## Out of Scope

Do not implement:

- automatic profile switching
- foreground-app detection
- game process detection
- Claude Code integration
- Codex integration
- Git controls
- test/build controls
- themes
- custom colors
- ambient backgrounds
- weather
- calendar
- fan controls
- temperature controls
- hardware vendor integrations
- installer changes
- Windows agent rewrite
- Spotify media workaround
- arbitrary shell execution

---

## Automatic Switching

Do not automatically switch based on:

- VS Code
- Spotify
- games
- active process
- foreground window

This may be considered later.

TASK-010 is manual profile management only.

---

## Future TASK-011 Compatibility

TASK-011 will add themes and visual customization.

TASK-010's profile model may reserve a minimal nullable future field such as:

    themeId

only if doing so is architecturally clean.

Do not implement themes now.

---

## Future TASK-014 Compatibility

Dev Mode will later host AI Developer widgets.

TASK-014 may add:

- Claude Code
- Codex
- active repo
- branch
- Git status
- build/test state
- safe developer workflows

TASK-010 should not implement placeholders that pretend these features already exist.

---

## Tests

Add comprehensive unit tests.

### Model Tests

Test:

- built-in profile IDs unique
- names independent from IDs
- deterministic order
- arbitrary custom profile count
- built-in/custom classification
- dashboards independently owned

### Template Tests

Validate:

- Default
- Dev
- Game
- Media

For every profile:

- valid 6×4 placement
- no overlap
- valid widget sizes
- valid action IDs
- valid page order
- unique widget IDs where required

### Switching Tests

Test:

- Default → Dev
- Dev → Game
- Game → Media
- Media → Default
- active profile persistence
- switching does not modify inactive profile

### Live State Tests

Switching profile must not:

- clear DesktopState
- clear desktop name
- clear capabilities
- clear CPU/RAM/GPU
- clear media
- clear artwork
- rebuild BLE transport

### Customization Tests

Test independent:

- widget move
- resize
- add
- remove
- page create
- page rename
- page reorder
- page delete

for multiple profiles.

### Migration Tests

Test:

- old single dashboard → Default
- custom legacy layout preserved
- Dev/Game/Media templates added
- old Windows action migration preserved
- migration happens once
- corrupted legacy layout recovers safely

### CRUD Tests

Test:

- create empty profile
- duplicate
- rename
- reorder
- set default
- delete custom
- reset built-in
- cannot permanently delete built-in
- deleting active custom selects valid fallback

### Startup Tests

Test:

    last selected
    → configured default
    → built-in default
    → first valid

### Persistence Tests

Test:

- save/reload
- corrupted JSON
- unknown profile ID
- duplicate IDs
- missing dashboard
- invalid placements
- schema upgrade

Strict validation must not cause unrelated valid profiles to be lost unnecessarily if the chosen architecture can safely recover them independently.

---

## Build Verification

Run:

    ./gradlew clean assembleDebug testDebugUnitTest

All tests must pass.

Windows agent should not require changes.

If Windows code is unexpectedly modified, also run:

    dotnet build
    dotnet test

Do not trigger real system actions during automated testing.

---

## Manual Test Checklist

1. Install updated APK.
2. Start Windows Ali Control agent.
3. Connect tablet.
4. Verify PC name appears.
5. Open profile switcher.
6. Verify Default / Dev / Game / Media.
7. Switch Default → Dev.
8. Verify BLE remains connected.
9. Verify CPU/RAM/GPU continue updating.
10. Switch to Media.
11. Verify current media state persists.
12. Verify cached artwork persists when available.
13. Return to Default.
14. Enter Edit Mode.
15. Move a widget.
16. Exit Edit Mode.
17. Switch to Dev.
18. Verify Dev layout unchanged.
19. Return to Default.
20. Verify moved widget persisted.
21. Customize Dev independently.
22. Restart application.
23. Verify last-selected profile restoration.
24. Restart again after configuring another default.
25. Verify startup fallback logic.
26. Create a custom profile.
27. Rename it.
28. Add/remove widgets.
29. Duplicate it.
30. Modify duplicate.
31. Verify original unchanged.
32. Reorder profiles.
33. Verify order persists.
34. Delete custom profile.
35. Attempt built-in deletion.
36. Verify reset is offered instead.
37. Reset Dev.
38. Verify Default unchanged.
39. Disconnect Windows PC.
40. Switch/edit profiles offline.
41. Reconnect.
42. Verify active profile widgets recover live state.
43. Reboot tablet.
44. Verify profile persistence.
45. Verify kiosk mode still works.

---

## Documentation

Update README.md with:

- dashboard profile concept
- built-in profiles
- profile switcher
- profile manager
- persistence
- migration
- startup policy
- create/duplicate/rename/delete
- reset behaviour
- interaction with live Windows state
- interaction with capability state
- known limitations

Update TASKS.md only when implementation and automated tests are complete.

---

## Completion Criteria

TASK-010 is complete when:

- built-in profiles exist
- custom profiles supported
- profile switching works
- each profile independently customizable
- persistence works
- legacy dashboard migration works
- live state survives switching
- BLE remains connected
- artwork cache remains global
- Windows action mappings remain valid
- automated tests pass
- Android build passes
- README updated
- TASKS.md marked complete

Do not commit or push automatically.