# TASK-007 — Multi-Page Dashboard & Widget UI Foundation

## Goal

Replace the current simple control-grid experience with a polished, multi-page dashboard foundation built around reusable widgets.

The result should feel more like a dedicated control surface / mini operating environment and less like a basic Android utility screen.

This task introduces:

- multiple dashboard pages
- page navigation
- widget-based UI
- different widget sizes
- icon-based action buttons
- default dashboard templates
- layout data models that TASK-008 can later make editable

Do **not** implement user customization/edit mode yet.

---

## Design Direction

The target visual direction is:

- dark
- minimal
- modern
- touch-friendly
- information-dense without feeling cramped
- bento/grid-inspired
- different widget/card sizes
- icon-forward rather than text-heavy

The dashboard should look appropriate on a dedicated 7-inch landscape tablet.

Avoid:

- large empty areas
- oversized text-only buttons
- excessive gradients
- animations running continuously
- overly decorative visual effects
- tiny touch targets

Keep performance suitable for the MT6580 device.

---

## Core Architecture

Introduce a dashboard model independent from the UI.

Suggested model:

```text
Dashboard
└── pages: List<DashboardPage>

DashboardPage
├── id
├── name
├── order
└── widgets: List<DashboardWidget>
```

Each widget should contain enough layout information to support TASK-008 later.

Suggested:

```text
DashboardWidget
├── id
├── type
├── gridX
├── gridY
├── gridWidth
├── gridHeight
└── configuration
```

Exact implementation may differ if there is a clear architectural reason.

The number of pages must **not** be hardcoded.

TASK-007 may ship with 3 default pages, but the model must support an arbitrary number of pages so TASK-008 can add/remove pages later.

---

## Grid System

Use a logical grid layout.

Recommended:

```text
12 columns × 8 rows
```

The exact row count may be adapted if necessary for the physical tablet aspect ratio, but the layout engine must use a stable logical grid.

Widgets should be able to occupy different cell sizes.

Examples:

```text
Small Action Button: 2 × 2
Wide Action Button:  4 × 2
Metric Widget:       4 × 2
Media Widget:        8 × 4
System Info Widget:  4 × 4
Clock Widget:        4 × 2
```

Do not implement user resizing yet.

The default layouts are defined programmatically or from static local data.

---

## Widget Types

Create a reusable widget type system.

At minimum support the following widget types.

### ACTION

Displays and sends one action from the existing Action Library.

Example:

```text
┌─────────┐
│   🔒    │
│  Lock   │
└─────────┘
```

Production implementation must use vector icons rather than emoji.

The widget must use the existing action registry/action IDs.

Do not duplicate action definitions inside the widget layer.

---

### ACTION_GROUP

A compact widget containing multiple closely related actions.

Useful examples:

```text
⏮   ▶   ⏭
```

or:

```text
Vol -   Mute   Vol +
```

The widget internally references existing Action Library IDs.

---

### METRIC

Displays a system value visually.

Examples:

```text
CPU
34%
```

```text
RAM
43%
```

```text
GPU
18%
```

Real telemetry is **not** implemented in this task.

Use an honest unavailable state such as:

```text
CPU
--
```

Do not fake live CPU/GPU/RAM values.

---

### GAUGE

Visual representation of a metric.

Future examples:

- CPU usage
- GPU usage
- CPU temperature
- GPU temperature
- fan speed

Real data is out of scope.

For TASK-007, the widget may render a disabled or unavailable state.

Avoid heavy Canvas animations or continuous redraws.

---

### CLOCK

Displays local tablet time.

Requirements:

- current local time
- optional date
- lightweight updates

Do not update more frequently than necessary.

If the UI displays only hours/minutes, update on minute boundaries instead of using a high-frequency timer.

---

### MEDIA

Create a visual Now Playing widget shell.

Example:

```text
┌─────────────────────────┐
│       NOW PLAYING       │
│                         │
│    Media unavailable    │
│                         │
│     ⏮    ▶    ⏭        │
└─────────────────────────┘
```

In TASK-007:

- Previous must work
- Play/Pause must work
- Next must work
- media metadata does not need to work
- album artwork does not need to work

Show a tasteful empty/unavailable state for metadata.

Do not implement album-art transfer over BLE in this task.

---

### SLIDER

Create the visual foundation for slider-based widgets.

Future examples:

- computer volume
- brightness
- fan speed

For TASK-007, remote-volume synchronization is optional.

If proper two-way remote volume state would require protocol expansion, do not fake the slider value.

A compact Volume Down / Mute / Volume Up action group is acceptable for the default dashboard.

Do not implement fan-speed control in this task.

---

## Icon System

Action buttons should no longer rely primarily on text.

Introduce a consistent vector icon system.

Prefer:

- Android vector drawables
- Material-style icons
- locally bundled resources

Avoid:

- emoji as production icons
- downloading icons at runtime
- large PNG assets for standard controls
- inconsistent icon families

Create an Android-side mapping from action ID to icon.

An `ActionIconRegistry` or equivalent is acceptable.

Example mappings:

```text
volume_up          -> volume_up
volume_down        -> volume_down
mute_toggle        -> volume_off
play_pause         -> play_arrow / pause
next_track         -> skip_next
previous_track     -> skip_previous
lock_screen        -> lock
screenshot         -> screenshot
spotlight          -> search
mission_control    -> view_quilt / dashboard
open_terminal      -> terminal
open_vscode        -> code
open_finder        -> folder
open_browser       -> language
open_spotify       -> music_note
open_settings      -> settings
```

App-specific branded icons are optional.

Do not spend excessive time recreating third-party logos.

---

## Page Navigation

Implement multi-page dashboard navigation.

Default pages:

1. Dashboard
2. Media
3. Dev

Users must be able to move between pages.

Preferred interaction:

- horizontal swipe

Also provide a visible page indicator near the bottom.

Example:

```text
● ○ ○
```

The current page should be visually clear.

Do not use large Android-style tab labels if they reduce the dedicated control-surface feel.

A lightweight ViewPager-style implementation is acceptable if compatible with API 27 and the existing project.

Avoid introducing a heavy dependency solely for paging.

---

## Default Page 1 — Dashboard

Create a visually balanced mixed dashboard.

Design direction:

```text
┌────────────┬────────────┬─────┬─────┬─────┐
│    CPU     │    GPU     │ 🔒  │ 📸  │ ⚙️  │
│     --     │     --     │     │     │     │
├────────────┴────────────┼─────┴─────┴─────┤
│                         │Terminal │ VSCode │
│       NOW PLAYING       ├─────────┼────────┤
│    ⏮    ▶    ⏭        │Spotlight│Mission │
│                         ├─────────┴────────┤
├────────────┬────────────┤ RAM              │
│  Volume    │    Mute    │ --               │
└────────────┴────────────┴──────────────────┘
```

Exact geometry may be adapted for the real tablet resolution.

Required concepts:

- CPU placeholder widget
- GPU placeholder widget
- RAM placeholder widget
- Lock action
- Screenshot action
- Device / Settings entry
- Terminal
- VS Code
- Spotlight
- Mission Control
- Media widget
- Volume controls
- Mute

Device / Settings must remain accessible.

---

## Default Page 2 — Media

Create a media-focused page.

Suggested structure:

```text
┌────────────────────────────────┐
│           NOW PLAYING          │
│                                │
│       Media unavailable        │
│                                │
│       ⏮     ▶     ⏭          │
├───────────────┬────────────────┤
│   Volume -    │   Volume +     │
├───────────────┼────────────────┤
│     Mute      │    Spotify     │
└───────────────┴────────────────┘
```

Required actions:

- `previous_track`
- `play_pause`
- `next_track`
- `volume_down`
- `volume_up`
- `mute_toggle`
- `open_spotify`

Do not implement real media metadata in this task.

---

## Default Page 3 — Dev

Create a development-focused page.

Suggested actions:

- `open_vscode`
- `open_terminal`
- `open_browser`
- `open_finder`
- `spotlight`
- `open_activity_monitor`
- `screenshot`
- `open_system_settings`

Suggested structure:

```text
┌───────────┬───────────┬───────────┐
│  VS Code  │ Terminal  │  Browser  │
├───────────┼───────────┼───────────┤
│ Spotlight │  Finder   │ Activity  │
├───────────┴───────────┼───────────┤
│      SYSTEM INFO      │ Screenshot│
│ CPU -- / RAM --       │           │
└───────────────────────┴───────────┘
```

Do not add Git, Claude Code, or Codex functionality yet.

---

## Action Execution

Widgets must use the existing Action Library and transport.

Do not create a second action dispatch system.

Expected flow:

```text
DashboardWidget
↓
ActionDefinition ID
↓
existing TransportManager
↓
BLE command
↓
macOS ActionRegistry
```

Existing:

- confirmation behavior
- Accessibility error behavior
- BLE acknowledgements
- platform filtering

must remain intact.

---

## Confirmation Actions

Actions marked `CONFIRM`, such as `sleep`, must still display confirmation.

The new widget UI must never bypass Action Library danger metadata.

Do not place `sleep` on the default dashboard unless there is a clear safe UX.

---

## Platform Awareness

Dashboard widget definitions should reference action IDs.

When rendering an action that is unsupported by the connected platform, use one consistent behavior:

- hide the widget

or

- render it disabled

Document the chosen behavior.

For the current macOS implementation, all default pages should render correctly.

Future Windows support must not require a new dashboard architecture.

---

## Connection State

The dashboard should remain visually usable while disconnected.

When disconnected:

- remote action widgets are disabled
- informational widgets remain visible
- Device / Settings remains usable
- Connect/reconnect path remains obvious

Do not navigate users back to the old control grid because BLE disconnects.

---

## Existing Actions Screen

The temporary TASK-006 `ActionsActivity` may remain accessible for testing.

Do not make it the primary dashboard.

It may be exposed as:

```text
All Actions
```

Do not remove it unless all 27 actions remain manually testable somewhere else.

This screen will also remain useful until TASK-008 introduces the action picker.

---

## Android Navigation

The dashboard becomes the primary home screen.

Secondary destinations should remain available:

- Connect
- Device / Settings
- All Actions

Avoid a large traditional navigation drawer unless there is a strong reason.

Prefer compact icon-based controls integrated into the dashboard.

---

## Visual Style

Create a reusable visual system.

At minimum define consistent:

- widget/card corner radius
- widget padding
- spacing
- icon sizes
- typography hierarchy
- pressed state
- disabled state
- connection status
- error status

Keep colors consistent with the existing dark UI.

Do not implement user-selectable themes in this task.

---

## Performance

Target device:

- Alcatel 1T 7 8068
- Android 8.1
- MT6580
- armeabi-v7a

Avoid:

- Jetpack Compose
- real-time blur
- large dynamic shadows
- continuous animation
- high-frequency redraws
- complex shaders
- video backgrounds
- heavy charting libraries
- unnecessary third-party UI frameworks

Continue using native Kotlin + XML Views.

Keep widget rendering lightweight.

---

## Persistence

TASK-007 does not implement user editing.

However, default dashboard definitions must be represented as data rather than being hardcoded entirely inside Activities.

Suggested architecture:

```text
DefaultDashboardFactory
```

or equivalent.

TASK-008 will later persist user-modified layouts.

Do not introduce a database in TASK-007.

A clean in-memory/static model is sufficient.

---

## Testing

Add unit tests for the dashboard model and layout.

At minimum verify:

- default dashboard has exactly 3 initial pages
- page IDs are unique
- page order is deterministic
- widget IDs are unique within each page
- widget positions remain inside grid bounds
- widget sizes remain inside grid bounds
- widgets do not unintentionally overlap
- all action widgets reference valid `ActionLibrary` IDs
- unsupported platform handling works
- confirmation metadata is preserved
- default Dashboard page contains expected core actions
- default Media page contains expected media actions
- default Dev page contains expected development/app actions
- dashboard model itself supports an arbitrary number of pages
- icon registry covers every default action widget
- disconnected state prevents remote action execution

Where UI tests are impractical, keep layout/model validation in pure Kotlin unit tests.

---

## Build Verification

Run:

```bash
./gradlew clean assembleDebug testDebugUnitTest
```

All Android tests must pass.

macOS protocol/action behavior should not require structural changes for this task.

If macOS code is modified for any reason, run the existing macOS build/tests as well.

---

## Manual Test Target

After implementation, manually verify:

1. Install the updated Android APK.
2. Launch Ali Control.
3. Verify the new dashboard is the primary screen.
4. Verify fullscreen/kiosk behavior still works.
5. Verify Page 1 — Dashboard renders correctly.
6. Swipe to Page 2 — Media.
7. Swipe to Page 3 — Dev.
8. Verify the page indicator updates correctly.
9. Swipe repeatedly between pages and verify there is no lag or crash.
10. Connect to the Mac.
11. Verify remote action widgets enable.
12. Test Page 1:
    - Lock
    - Screenshot
    - Terminal
    - VS Code
    - Spotlight
    - Mission Control
    - Volume
    - Mute
    - Media controls
13. Test Page 2:
    - Previous
    - Play/Pause
    - Next
    - Volume Down
    - Volume Up
    - Mute
    - Spotify
14. Test Page 3:
    - VS Code
    - Terminal
    - Browser
    - Finder
    - Spotlight
    - Activity Monitor
    - Screenshot
15. Disconnect BLE.
16. Verify remote action widgets become disabled.
17. Verify Device / Settings remains accessible.
18. Verify All Actions still provides access to all 27 actions.
19. Reconnect.
20. Verify action widgets recover without restarting the app.
21. Verify CPU/GPU/RAM widgets show `--` or another clearly unavailable state.
22. Verify no fake telemetry is presented.
23. Verify TASK-004 kiosk/boot behavior still works.

---

## Documentation

Update `README.md` with:

- multi-page dashboard architecture
- default pages
- widget types
- grid model
- page navigation
- icon system
- placeholder metric behavior
- platform-aware widget behavior
- relationship between TASK-007 and TASK-008

---

## Out of Scope

Do **not** implement:

- user layout editing
- drag and drop
- resize handles
- widget picker
- add/remove widget UI
- page creation UI
- page deletion UI
- page renaming UI
- page reordering UI
- persisted custom layouts
- custom shortcuts
- profiles
- real CPU telemetry
- real GPU telemetry
- temperatures
- fan RPM
- weather API
- real media metadata
- album artwork
- themes
- Windows agent
- Claude Code integration
- Codex integration
- custom ROM
- arbitrary remote execution