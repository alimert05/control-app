# Control App v0.1.0

**Initial MVP Release**

Control App turns a spare Android tablet into a dedicated control panel for a Windows PC over
Bluetooth Low Energy. This first release is a working MVP. The project is under active development
and is not presented as production-ready software. It is released under the MIT License.

## Included

**Android tablet app**

- Fullscreen, kiosk-style dashboard (immersive mode, optional keep-awake, launch on boot)
- Multi-page dashboard on a 6×4 grid with an on-tablet Edit Mode: move, resize, add and remove
  widgets; create, rename, reorder and delete pages; layouts saved locally
- Dashboard profiles: built-in **Default**, **Dev**, **Game** and **Media**, plus custom profiles
  (create, duplicate, rename, reorder, set a startup default, reset, delete); each profile has its
  own independent layout
- 27 predefined PC actions: media, system, app launchers, window controls and clipboard/edit
  shortcuts (Sleep asks for confirmation)
- Live CPU, RAM and GPU usage, now-playing metadata and album artwork widgets; values the PC cannot
  provide show `--`
- BLE scan/connect screen, connection status, PC name in the header and bounded automatic reconnect
- Device screen: brightness, tablet volume, battery, keep-awake, Wi-Fi and Bluetooth shortcuts

**Windows desktop agent**

- Native tray application (C# / .NET 10, standard user, no installer required), published as a
  self-contained single-file build
- BLE peripheral with a fallback discovery UUID for adapters that cannot advertise the full one
- Allowlisted action execution, CPU / RAM / GPU telemetry and now-playing data from Windows media
  sessions, with artwork sent on request
- Capability announcement so the tablet dims widgets the PC cannot serve
- Open at Startup, Restart Advertising and a `--diagnose` setup report

**Local-only operation**

- Tablet and PC communicate directly over Bluetooth LE: no Wi-Fi, internet, cloud service or account is
  needed, the Windows agent opens no network port, and there are no analytics or telemetry uploads

**Quality**

- 501 Android unit tests and 468 Windows agent tests (neither suite touches real hardware)

## Known Limitations

- Windows Spotify now-playing data does not reliably reach the tablet yet (open issue found in the
  first physical-tablet test).
- Real-device testing has primarily been performed using an Alcatel 1T 7 (8068); other tablets and PCs
  have not been tested broadly.
- The BLE link has no pairing, authentication or encryption: any BLE device in range can connect to a
  running agent, send the allowlisted actions, read live PC state (PC name, CPU / RAM / GPU, now-playing
  details) and replace the tablet's connection. Only run the agent where that is acceptable. Stronger
  security is planned for future releases (not part of v0.1.0).
- Windows 11 (x64) is the only supported desktop; macOS support was removed.
- Actions that inject keystrokes cannot control windows running as administrator.
- No CPU/GPU temperature readings; GPU usage requires Windows GPU performance counters.
- Kiosk mode is application-level and cannot block Home/Recents; boot launch varies by tablet vendor.
- Daily Overview shows time and date only (weather and calendar are placeholders).
- Windows agent performance was measured at idle and for its telemetry loop in isolation; connected-tablet CPU and memory were not measured (no tablet attached during benchmarking).
- Builds are unsigned and there is no installer; no prebuilt binaries are attached to this release.

## Next Steps

Planned, not yet implemented:

- Resolve Windows media (Spotify) delivery and improve media metadata support
- Efficiency improvements to the agent's live-state loop and a connected-tablet performance measurement
- Themes and visual customization
- More system controls and hardware / fan controls where supported
- Developer-workflow widgets
- Weather and calendar widgets
- Authenticated pairing and stronger protection of the BLE link
- Installer and code signing
- Broader device support
