# Ali Control — Tasks

## MVP

- [x] TASK-001 — Android Control Panel Foundation
- [x] TASK-002 — Bluetooth Transport & Pairing
- [x] TASK-003 — macOS BLE Agent & Core Controls
- [x] TASK-004 — Device / Kiosk Mode
- [x] TASK-005 — Polish, Packaging & Reliability

## Post-MVP

- [x] TASK-006 — Action Library Foundation
- [x] TASK-007 — Multi-Page Dashboard & Widget UI Foundation
- [x] TASK-008 — Dashboard Refinement & Customization Engine
- [ ] TASK-009 — Live System & Media Data
- [ ] TASK-010 — Dashboard Modes & Profiles
- [ ] TASK-011 — Themes & Visual Customization
- [ ] TASK-012 — Advanced System Controls
- [ ] TASK-013 — Fan / Hardware Integrations
- [ ] TASK-014 — AI Developer Integrations
- [ ] TASK-015 — Weather & Calendar Integrations

## Deferred

- [ ] Windows Agent — implement when Windows hardware is available for physical testing

---

## Planned Feature Direction

### TASK-008 — Dashboard Refinement & Customization Engine

- 6×4 logical dashboard grid
- edit mode
- drag/reposition widgets
- resize widgets
- add/remove widgets
- action picker
- page creation/deletion/rename/reordering
- layout persistence
- Reset to Default
- Daily Overview widget
- refined default Dashboard
- top control row visual consistency

### TASK-009 — Live System & Media Data

- CPU usage
- RAM usage
- GPU usage where available
- temperatures where available
- live Now Playing metadata
- artist/title
- playback state
- media progress
- album artwork if practical over the chosen transport

### TASK-010 — Dashboard Modes & Profiles

Initial planned modes:

- Default
- Dev
- Game
- Media

Each mode may contain its own:

- pages
- widgets
- layout
- actions
- future theme/accent configuration

Future optional automatic mode suggestions may react to active applications,
but automatic switching should not be required.

### TASK-011 — Themes & Visual Customization

Future visual customization may include:

- accent color
- card styles
- widget appearance
- icon styles
- background styles
- lightweight ambient effects
- micro-animations
- mode-specific visual appearance

Performance must remain suitable for the MT6580 tablet.

### TASK-012 — Advanced System Controls

Potential additions:

- additional media/system actions
- display controls
- system presets
- application workflows
- safe predefined desktop actions

No arbitrary remote shell execution.

### TASK-013 — Fan / Hardware Integrations

Hardware-dependent features may include:

- fan RPM
- temperature sensors
- fan profiles
- hardware monitoring
- platform/vendor adapters

Implementation will depend on macOS/Windows hardware capabilities.

### TASK-014 — AI Developer Integrations

Potential developer control center features:

- Claude Code status
- Codex status
- active project
- active branch
- git status
- test/build status
- last activity
- safe predefined Claude/Codex workflows
- project registry
- developer dashboard widgets

The tablet must send only predefined workflow/action IDs.

Do not expose arbitrary shell execution.

### TASK-015 — Weather & Calendar Integrations

Potential integrations:

- live weather
- Microsoft / Outlook Calendar
- Microsoft Graph
- university timetable/calendar data
- Daily Overview event list
- next-event information

The current Daily Overview widget should remain functional with placeholder
states until these integrations are implemented.

---

## Deferred Platform Work

### Windows Agent

Windows support is intentionally deferred until Windows hardware is available
for physical testing.

The existing platform-aware Action Library and dashboard architecture should
remain reusable for the future Windows implementation.