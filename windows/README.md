# Ali Control — Windows agent

The native Windows 11 (x64) desktop agent for Ali Control: a lightweight tray app that
acts as the BLE peripheral the Android tablet connects to. Full documentation is in the
repository [README](../README.md#windows-agent) and
[docs/WINDOWS_MIGRATION.md](../docs/WINDOWS_MIGRATION.md).

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1    # restore, build, test, publish
.\dist\AliControl\AliControl.exe                              # run (tray icon, no console)
.\dist\AliControl\AliControl.exe --diagnose                   # setup / BLE report
.\dist\AliControl\AliControl.exe --quit                       # stop the running agent cleanly
```

| | |
|---|---|
| Target | `net10.0-windows10.0.19041.0`, x64, WinForms tray + WinRT Bluetooth/media/imaging |
| Dependencies | none (no NuGet packages in the agent) |
| Privileges | standard user (`asInvoker`); never elevates |
| Tests | `dotnet test AliControl.slnx` — 468 tests, none of which touch the real PC |
| Output | `dist\AliControl\AliControl.exe` (self-contained single file, ≈135 MB uncompressed; ≈80 MB resident when idle) |
