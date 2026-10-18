# Manual end-to-end checklist

Automated tests cover each side in isolation (the Android client and the Windows agent are
tested against shared wire fixtures, and neither suite touches real Bluetooth or the real PC).
The connected loop needs a physical tablet and a Windows PC in the same room, so it is checked by
hand with this list. Run the riskier actions (Sleep, Close Window) last and with nothing
unsaved open.

1. Install the APK and launch Ali Control on the tablet.
2. Build and run `AliControl.exe`. Confirm the tray icon appears and, within a couple of
   seconds, shows `Advertising: Yes` (possibly `(compact discovery)` — both are fine).
   Optional first: `AliControl.exe --diagnose`.
3. On the tablet tap **Connect** (grant the location prompt on first run) and pick the PC.
   Confirm the tablet reaches a true **Connected** state and the tray icon turns green with
   the tablet's address. The header should show the PC's name.
4. Verify Dashboard, Media and Dev render on the 6×4 grid and swiping is smooth. Verify
   widgets are enabled, and that CPU/RAM (and GPU, if announced) change from `--` to real
   values within a few seconds and CPU rises under load.
5. Run the actions in roughly ascending risk order, **Sleep last**: Windows Search, Task
   View, File Explorer, Terminal, VS Code, Browser (confirm it is your real default
   browser), Spotify, Settings, Task Manager, Screenshot (check `Pictures\Screenshots`),
   Show Desktop, Lock Screen, Display Sleep, Minimize / Maximize-Restore / Close Window
   (close a Notepad window, not something unsaved), Copy / Paste / Undo / Redo, volume ±
   and mute, play/pause/next/previous — then Sleep (confirm the dialog appears first).
6. Play a track in Spotify (or another media app): confirm title, artist, album, state and
   position/duration appear and advance, artwork appears on the Media widget shortly after
   the metadata, and the tablet's Play/Pause/Next/Previous change what the PC plays.
   Skip tracks quickly: no stale artwork, no crash. Confirm commands stay responsive during
   an artwork transfer.
7. Verify an unavailable-app action fails cleanly (e.g. Spotify on a PC without it): an
   error toast, no crash, no dialog on the PC. Focus an *elevated* window (e.g. Task Manager
   run as administrator) and confirm Copy/Paste fails with an error while Volume still works.
8. Edit mode: move/resize/add/remove widgets, create/rename/reorder/delete pages, confirm
   persistence across an app restart and a tablet reboot, then **Reset to default**.
9. **Saved-layout migration:** if the tablet still has a layout saved by an early build
   that used the retired macOS action IDs (Spotlight, Mission Control, Finder, Activity
   Monitor), confirm after updating that those widgets now show Windows Search / Task View /
   File Explorer / Task Manager in the same places.
10. Disconnect and confirm remote widgets disable, CPU/RAM/GPU return to `--` and media to
    "Media unavailable", while editing, Device/Settings and All Actions keep working;
    reconnect and confirm everything recovers without restarting either app.
11. Quit the agent from the tray (or `AliControl.exe --quit`): the tablet must notice
    immediately. Relaunch it: the tablet reconnects (auto-reconnect or manual Connect).
12. Toggle PC Bluetooth off and on: advertising must recover by itself. Toggle tablet
    Bluetooth off and on: the tablet must recover. Put the PC to sleep and wake it: the
    agent must advertise again within a few seconds.
13. Tick **Open at Startup**, sign out and back in: the agent must launch by itself and appear
    in Settings → Apps → Startup; untick it and confirm the entry disappears.
14. Leave both idle for several minutes: the agent must stay near 0 % CPU in Task Manager and
    `agent.log` must stay quiet. Reboot the tablet and confirm Ali Control launches on boot.
15. On the tablet exercise Device/Settings (brightness, tablet volume, battery, Wi-Fi /
    Bluetooth entries, keep-awake), the development escape hatch, and returning from Android
    Settings.
16. **Profiles — switching** (with the PC connected and a track playing): open the header
    switcher and confirm *Default / Dev / Game / Media* and *Manage Profiles*. Switch
    Default → Dev → Media → Default. Confirm the header stays **Connected** with the PC's
    name, CPU/RAM/GPU keep updating, the current track and its artwork are still shown on the
    Media profile immediately (cached artwork is not re-fetched), and the page dots follow
    each profile's own page count.
17. **Profiles — independence and Edit Mode:** in Default enter Edit Mode and move a widget,
    then switch to Dev *while still editing* (Edit Mode ends, nothing is lost). Confirm Dev is
    unchanged, switch back and confirm the move persisted. Customise Dev, then restart the app
    and confirm the **last selected** profile reopens.
18. **Profiles — management:** from *Manage Profiles* create a profile, rename it, add and
    remove widgets, duplicate it and change the copy (original unchanged), reorder, **Set as
    startup default**, delete the custom profile, try to delete a built-in (the manager
    explains and offers the reset), **Reset** Dev and confirm Default is unchanged. Restart and
    confirm order, names and the startup default persisted; delete the profile you were on,
    restart, and confirm the startup default opens.
19. **Profiles — migration:** after updating from a build that has a customised single
    dashboard, confirm that layout is now the **Default** profile (same pages, widgets and
    places), and that Dev, Game and Media are present with their shipped layouts.
20. **Profiles — offline:** disconnect the PC, switch and edit profiles (remote widgets stay
    dimmed), reconnect, and confirm the active profile's widgets recover live state. Reboot the
    tablet and confirm the profiles, the selection and kiosk mode persist.
