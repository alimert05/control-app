# TASK-008 — Dashboard Refinement & Customization Engine

> **Historical document.** Written when macOS was the reference platform; the dashboard architecture described here is unchanged. Action names such as Spotlight / Mission Control / Finder / Activity Monitor are now Windows Search / Task View / File Explorer / Task Manager, and saved layouts are migrated automatically (schema 1 → 2). See [docs/WINDOWS_MIGRATION.md](../WINDOWS_MIGRATION.md).

## Goal

Refine the current multi-page dashboard design and implement the first real dashboard customization system.

TASK-008 should transform the dashboard from a static set of default pages into a user-editable control surface.

This task includes:

- default dashboard visual refinement
- a 6×4 logical customization grid
- edit mode
- drag/reposition widgets
- resize widgets
- add/remove widgets
- add/remove dashboard pages
- rename pages
- page reordering
- per-page layout persistence
- action selection from the existing Action Library
- widget size constraints
- safe handling of unsupported layouts
- preserving the existing multi-page dashboard architecture

This task does **not** implement real CPU/GPU telemetry, real weather, live calendar integration, or media metadata.

---

## Design Direction

The dashboard should continue to feel like:

- a dedicated control surface
- a mini operating environment
- compact
- modern
- touch-first
- dark
- bento-inspired
- visually clean
- information dense without becoming cluttered

Customization must remain simple enough to use comfortably on a 7-inch landscape tablet.

Do not make edit mode feel like a desktop design tool.

The user should be able to arrange widgets primarily based on what visually feels right.

---

## Default Dashboard Refinement

Before exposing customization, improve the current default Dashboard page.

### Remove Large Media Player From Dashboard

The main Dashboard page should no longer contain a large full-size media player.

The dedicated Media page already provides space for the full media experience.

The Dashboard should only contain compact media controls.

Recommended:

```text
⏮   ▶/⏸   ⏭   Mute   Vol-/Vol+
```

The full MEDIA widget remains available on the Media page and later through the widget picker.

---

## Daily Overview Widget

Add a new widget type:

```text
DAILY_OVERVIEW
```

This should become one of the primary visual elements on the default Dashboard page.

It should visually combine:

- local time
- local date
- weather area
- calendar/events area
- next-event area

Example:

```text
┌──────────────────────────────────┐
│ 09:54        Tue, 8 Sep          │
│ Edinburgh • Weather unavailable  │
│                                  │
│ TODAY                            │
│ No calendar data                 │
│                                  │
│ Next event: --                   │
└──────────────────────────────────┘
```

In TASK-008:

- time is real local tablet time
- date is real local tablet date
- weather is placeholder/unavailable
- calendar is placeholder/unavailable
- next event is placeholder/unavailable

Do not display fake weather or fake events.

Future tasks may connect this widget to:

- Microsoft / Outlook Calendar
- Microsoft Graph
- university timetable data
- a weather provider

Do not implement those integrations now.

---

## Default Dashboard Layout

Refine the default page toward a layout that takes advantage of a 6-column landscape grid.

Example design direction:

```text
┌──────────┬──────────┬──────────┬──────┬──────┬──────┐
│          │          │          │ CPU  │ GPU  │ RAM  │
│          DAILY OVERVIEW        ├──────┼──────┼──────┤
│                               │ Lock │ Shot │Clock │
├──────────┼──────────┼──────────┼──────┼──────┼──────┤
│ Terminal │ VS Code  │Spotlight │Mission│Finder│Browser│
├──────────┴──────────┴──────────┼──────┴──────┴──────┤
│       ⏮   ▶/⏸   ⏭            │   Vol-  Mute  Vol+ │
└───────────────────────────────┴─────────────────────┘
```

This is only a visual direction.

Exact widget placement may be refined to fit the physical tablet screen cleanly.

Goals:

- small single-action controls should not consume excessive space
- six compact 1×1 action widgets should fit in one row if desired
- important information widgets may occupy larger areas
- users should not be forced into a fixed "24 buttons" layout
- the 24 grid cells are placement units, not required button slots

---

## Top Control Row Consistency

The following top-level controls must use the same visual component/style:

- Device Settings
- Connect / Disconnect
- All Actions

They must have consistent:

- height
- vertical padding
- horizontal padding
- corner radius
- icon size
- text size
- baseline alignment
- visual weight

`Device Settings` must not appear taller, thicker, or more padded than the others.

If practical, render all three using the same reusable view/style.

---

## Customization Grid

Use a logical:

```text
6 columns × 4 rows
```

grid per dashboard page.

This provides:

```text
24 logical cells
```

The 24 cells are not 24 mandatory buttons.

They are simply placement units that allow widgets of different sizes to be arranged flexibly.

The grid is conceptual and invisible during normal dashboard use.

In Edit Mode, subtle grid boundaries may become visible.

Example:

```text
┌────┬────┬────┬────┬────┬────┐
│    │    │    │    │    │    │
├────┼────┼────┼────┼────┼────┤
│    │    │    │    │    │    │
├────┼────┼────┼────┼────┼────┤
│    │    │    │    │    │    │
├────┼────┼────┼────┼────┼────┤
│    │    │    │    │    │    │
└────┴────┴────┴────┴────┴────┘
```

---

## Widget Placement Model

Each widget must have explicit grid placement.

Suggested model:

```text
WidgetPlacement
├── x
├── y
├── width
└── height
```

Constraints:

```text
x      = 0..5
y      = 0..3
width  = 1..6
height = 1..4
```

A widget must satisfy:

```text
x + width  <= 6
y + height <= 4
```

Widgets must not overlap.

---

## Supported Widget Sizes

The layout engine should support any valid rectangular size that fits within the 6×4 grid.

Examples:

```text
1×1
1×2
1×3
1×4

2×1
2×2
2×3
2×4

3×1
3×2
3×3
3×4

4×1
4×2
4×3
4×4

5×1
5×2
5×3
5×4

6×1
6×2
6×3
6×4
```

Not every widget type must support every size.

Widget types may define minimum and maximum dimensions.

---

## Widget Size Constraints

Suggested initial constraints:

```text
ACTION
min: 1×1
max: 2×2

ACTION_GROUP
min: 2×1
max: 6×2

METRIC
min: 1×1
max: 2×2

GAUGE
min: 1×1
max: 2×2

CLOCK
min: 2×1
max: 4×2

DAILY_OVERVIEW
min: 3×2
max: 6×4

MEDIA
min: 3×2
max: 6×4

SLIDER
min: 2×1
max: 6×2
```

Exact constraints may be refined if the physical tablet reveals usability problems.

Do not allow the user to resize a widget into a state where its content becomes unusable.

---

## Dashboard Model

Extend/refine the existing TASK-007 dashboard model.

Suggested:

```text
Dashboard
└── pages: List<DashboardPage>

DashboardPage
├── id
├── name
├── order
└── widgets: List<DashboardWidget>

DashboardWidget
├── id
├── type
├── placement
└── configuration

WidgetPlacement
├── x
├── y
├── width
└── height
```

The model must not assume exactly three pages.

The default configuration may still ship with:

1. Dashboard
2. Media
3. Dev

but the user must be able to create more.

---

## Edit Mode

Add an explicit dashboard editing mode.

Normal use and edit mode must be clearly different.

Preferred entry:

- visible Edit icon/button

When Edit Mode is active:

- show subtle 6×4 grid boundaries
- show widget selection state
- enable drag/reposition
- expose resize control/handle
- expose remove action
- expose Add Widget
- expose page-management controls
- prevent normal action execution from accidental taps where appropriate

The user must always have a clear:

```text
Done
```

control to return to normal mode.

---

## Drag / Reposition

In Edit Mode, widgets should be movable.

Requirements:

- deliberate drag gesture
- snap to grid cells
- remain inside 6×4 bounds
- never overlap another widget
- invalid placement must be rejected
- current valid position remains if drop is invalid

Touch behavior must be stable on the 7-inch tablet.

Do not require pixel-perfect dragging.

---

## Resize

Widgets should be resizable in Edit Mode.

Use a touch-friendly mechanism.

Preferred:

- lower-right resize handle

Resize behavior:

- snaps to grid
- respects widget type minimum size
- respects widget type maximum size
- remains inside page bounds
- cannot overlap another widget
- invalid resize is rejected

Do not implement free-form pixel resizing.

---

## Add Widget

Provide an:

```text
Add Widget
```

flow.

The user should be able to choose a widget type.

At minimum:

- Action
- Action Group
- Metric
- Gauge
- Clock
- Daily Overview
- Media
- Slider

Some widgets may still be placeholder-only because live providers are not implemented.

---

## Add Action Widget

When adding an ACTION widget:

1. open action picker
2. show actions from the existing Action Library
3. respect connected platform support
4. allow user to select one action
5. add the widget to a valid free grid location

The action picker should reuse:

```text
ActionLibrary
```

Do not duplicate action definitions.

Do not allow arbitrary action IDs.

---

## Action Picker

Organize actions by existing categories:

- Media
- System
- Apps
- Window
- Productivity

Show:

- icon
- label

If useful:

- permission indicator
- confirmation indicator

Do not make the picker unnecessarily complex.

---

## Add Widget Placement

When a new widget is added:

- choose a valid default size
- find a valid free position
- prefer top-left-most available placement or another deterministic strategy

If no suitable free area exists:

- do not overlap existing widgets
- show a clear "Not enough space" state

Do not silently delete or move other widgets.

---

## Remove Widget

In Edit Mode, allow widgets to be removed.

Removing a widget only removes it from the current dashboard layout.

It must not:

- remove the action from Action Library
- modify the macOS registry
- affect other pages

---

## Page Management

TASK-008 should support dashboard page customization.

Allow:

- create page
- rename page
- delete page
- reorder pages

The model must support an arbitrary page count.

Do not hardcode three pages.

---

## Create Page

The user should be able to create a new empty page.

Suggested initial name:

```text
New Page
```

New pages start with an empty 6×4 grid.

---

## Rename Page

Allow arbitrary sensible page names such as:

```text
Dashboard
Media
Dev
Gaming
System
AI
Music
```

Validate obvious empty/invalid values.

Internal page IDs remain unique.

---

## Delete Page

Allow pages to be deleted.

Safety:

- do not allow deleting the final remaining page
- require confirmation before deleting a page if it contains widgets

---

## Page Reordering

Allow page order to change.

A simple implementation is acceptable:

- move left
- move right

or a small management list.

Do not overengineer page dragging.

---

## Page Indicator

Preserve the compact page indicator.

It must update when pages are:

- created
- deleted
- reordered
- selected

Do not hardcode three dots.

---

## Persistence

TASK-008 introduces real user layout persistence.

Persist:

- page list
- page names
- page order
- widgets
- widget types
- widget configurations
- widget positions
- widget sizes

Use a lightweight local persistence approach.

Preferred:

- JSON stored locally
- SharedPreferences + serialized JSON
- lightweight file-based storage

Avoid:

- Room
- SQL
- cloud persistence

---

## Default Layout Migration

Existing users should receive the new refined default dashboard if no customized layout exists.

Once the user has saved a custom layout:

- do not silently overwrite it with a future default layout

Introduce a simple layout schema/version field if useful.

Example:

```text
schemaVersion = 1
```

---

## Reset Layout

Provide:

```text
Reset to Default
```

This must:

- require confirmation
- delete only the user-customized dashboard layout
- restore the shipped default dashboard/pages

Do not reset unrelated app settings.

---

## Widget Configuration

Introduce a lightweight configuration model suitable for future widgets.

Examples:

```text
ActionWidgetConfig
actionId

MetricWidgetConfig
metricType

MediaWidgetConfig
...

DailyOverviewConfig
...
```

Prefer a type-safe approach where practical.

Avoid a completely untyped generic map if possible.

---

## Icons

Preserve the TASK-007 icon system.

Action widgets must continue to use the existing ActionIconRegistry or equivalent.

When selecting an action, show its icon.

Widgets should remain icon-forward.

Suggested responsive behavior:

```text
1×1
icon + short label

2×1
icon + full label

2×2
larger icon + label
```

---

## Widget Responsive Rendering

Widgets must adjust their internal presentation based on allocated grid size.

Examples:

### ACTION 1×1

```text
┌───────┐
│  🔒   │
│ Lock  │
└───────┘
```

Production icon should be vector-based.

### ACTION 2×1

```text
┌────────────────┐
│ 🔒  Lock Screen │
└────────────────┘
```

### METRIC 1×1

```text
CPU
--
```

### DAILY_OVERVIEW 3×2

```text
09:54
Tue, 8 Sep
Weather --
Calendar --
```

Do not allow text overflow or unreadably small text.

---

## Connection Behavior

Preserve existing BLE behavior.

When disconnected:

- dashboard still renders
- edit mode still works
- layout management still works
- Device Settings still works
- remote action widgets remain disabled

Customization must not require a desktop connection.

---

## Platform Awareness

Widgets reference stable action IDs.

If an action is unsupported by the currently connected platform:

- preserve it in the saved layout
- render it disabled

Do not silently remove it.

---

## Confirmation Actions

ActionLibrary danger metadata must still be respected.

Custom widgets must not bypass confirmation.

If the user adds:

```text
sleep
```

to a dashboard, tapping it in normal mode must still show confirmation.

---

## All Actions

Preserve the existing All Actions screen.

It remains useful for:

- testing
- browsing the full action library
- fallback access

---

## Media Page

Keep the dedicated Media page.

The default Media page should continue to contain the larger MEDIA widget.

In TASK-008:

- media metadata remains unavailable
- previous/play/next controls remain functional
- volume/mute remain functional

Do not implement media metadata or album art.

---

## Dev Page

Keep/refine the default Dev page.

It may include:

- VS Code
- Terminal
- Browser
- Finder
- Spotlight
- Activity Monitor
- Screenshot
- System Settings

Do not add Git/Claude/Codex functionality yet.

---

## Performance

Target:

- Alcatel 1T 7 8068
- Android 8.1
- API 27
- MT6580
- armeabi-v7a

Do not introduce:

- Jetpack Compose
- continuous animation
- high-frequency layout updates
- heavy drag/drop libraries
- heavy dashboard frameworks
- web rendering
- database frameworks
- complex physics
- large image assets

Use native Kotlin + XML Views.

---

## Security

Do not change the existing security model.

Customization must never allow user-provided shell commands.

The user selects predefined Action Library actions only.

Do not add:

- arbitrary remote execution
- script editor
- shell command text fields
- arbitrary app paths
- HTTP server
- TCP server
- cloud communication

---

## Testing

Add strong pure Kotlin tests.

At minimum test:

### Grid Validation

- valid 1×1 placement
- valid 6×4 placement
- reject negative coordinates
- reject width/height < 1
- reject width > 6
- reject height > 4
- reject placements outside bounds

### Collision

- overlapping widgets rejected
- touching edges allowed
- moving into occupied cell rejected
- resizing into occupied cells rejected

### Size Constraints

- ACTION respects min/max
- DAILY_OVERVIEW respects min/max
- MEDIA respects min/max
- invalid widget size rejected

### Pages

- arbitrary page count supported
- page IDs remain unique
- page ordering deterministic
- cannot delete last page
- rename preserves page ID
- reorder works

### Widgets

- add widget
- remove widget
- move widget
- resize widget
- deterministic default placement
- no duplicate widget IDs

### Action Integration

- Action widgets reference valid ActionLibrary IDs
- unsupported platform action remains stored
- unsupported action renders disabled state
- CONFIRM metadata preserved
- unknown action ID rejected during add

### Persistence

- serialize dashboard
- deserialize dashboard
- round-trip equality
- schema version preserved
- corrupted persisted layout fails safely
- invalid persisted layout falls back safely

### Defaults

- refined default dashboard is valid
- Daily Overview is present
- large MEDIA widget absent from main Dashboard
- compact media controls remain on Dashboard
- Media page still contains MEDIA widget
- Device Settings / Connect / All Actions remain accessible

Automated tests must not:

- send BLE commands
- launch real Mac apps
- modify system settings
- require physical tablet interaction

---

## Build Verification

Run:

```bash
./gradlew clean assembleDebug testDebugUnitTest
```

All tests must pass.

---

## Manual Test Checklist

After implementation:

1. Install the updated APK.
2. Launch Ali Control.
3. Verify the refined default Dashboard.
4. Verify the Dashboard uses the 6×4 placement model.
5. Verify the large media widget is gone from Dashboard.
6. Verify compact media controls remain.
7. Verify Daily Overview appears.
8. Verify time/date are real.
9. Verify weather/calendar are unavailable placeholders.
10. Verify Device Settings / Connect / All Actions have matching visual height/style.
11. Enter Edit Mode.
12. Verify subtle 6×4 grid appears.
13. Move a 1×1 ACTION widget.
14. Verify it snaps correctly.
15. Move a larger widget such as 3×2.
16. Attempt an invalid overlap.
17. Verify it is rejected.
18. Resize a widget.
19. Verify min/max constraints.
20. Add a new Action widget.
21. Select an action from Action Library.
22. Verify icon/label rendering.
23. Remove a widget.
24. Add another widget with a larger rectangular size.
25. Verify empty-space placement.
26. Create a page.
27. Rename it.
28. Add widgets.
29. Reorder pages.
30. Delete a non-final page.
31. Verify final page cannot be deleted.
32. Relaunch the app.
33. Verify layout persists.
34. Reboot tablet.
35. Verify layout persists.
36. Disconnect Mac.
37. Verify editing still works.
38. Verify remote actions disable.
39. Reconnect.
40. Verify actions recover.
41. Reset to Default.
42. Verify default layout returns.
43. Verify All Actions remains available.
44. Verify Media and Dev pages still work.
45. Verify kiosk/fullscreen behavior remains intact.

---

## Documentation

Update README.md with:

- 6×4 grid architecture
- 24 logical placement cells
- widget sizing
- Edit Mode
- drag/reposition
- resize behavior
- widget picker
- Action picker
- page management
- persistence
- Reset to Default
- Daily Overview placeholder behavior
- refined default dashboard
- platform-aware saved actions
- known limitations

---

## Out of Scope

Do **not** implement:

- real weather
- Microsoft Calendar integration
- Outlook integration
- Microsoft Graph
- university timetable integration
- real CPU telemetry
- real GPU telemetry
- RAM telemetry
- temperatures
- fan RPM
- fan control
- real media metadata
- album artwork
- media progress synchronization
- themes
- dashboard modes/profiles
- Dev Mode
- Game Mode
- Media Mode switching
- Claude Code integration
- Codex integration
- Git integration
- Windows agent
- custom shell commands
- arbitrary remote execution
- custom ROM
- cloud services