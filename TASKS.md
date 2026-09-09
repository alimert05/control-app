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
- [x] TASK-009 — Live System & Media Data
- [x] TASK-009A — Media Artwork over BLE
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

### TASK-009 — Live System & Media Data

Bring the existing placeholder widgets to life.

Planned:

- CPU usage
- RAM usage
- GPU usage where reliably available
- temperatures where reliably available without unsafe/private hacks
- Now Playing title
- artist
- album where available
- playback state
- media progress/duration where available
- live widget updates over the existing BLE connection

No fabricated metrics.

Album artwork was deliberately split into TASK-009A and delivered there with a
bounded, request-driven BLE transfer rather than expanding TASK-009's live JSON.

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
- future visual configuration

Future automatic mode suggestions may react to active applications, but automatic
switching is not required.

### TASK-011 — Themes & Visual Customization

Potential additions:

- accent color
- card styles
- widget styles
- background styles
- lightweight ambient effects
- micro-animations
- mode-specific appearance

Performance must remain suitable for the MT6580 tablet.

### TASK-012 — Advanced System Controls

Potential additions:

- additional desktop controls
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
- vendor/platform adapters

Implementation depends on actual hardware and platform capability.

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

No arbitrary shell execution.

### TASK-015 — Weather & Calendar Integrations

Potential integrations:

- live weather
- Microsoft / Outlook Calendar
- Microsoft Graph
- university timetable/calendar data
- Daily Overview event list
- next-event information

The Daily Overview widget should continue working with placeholder states until
these integrations are implemented.

---

## Deferred Platform Work

### Windows Agent

Windows support is intentionally deferred until Windows hardware is available
for physical testing.

The platform-aware Action Library, dashboard, telemetry models, and widget
architecture should remain reusable for the future Windows implementation.
