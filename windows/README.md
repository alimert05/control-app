# Ali Control — Windows agent

The native Windows 11 (x64) desktop agent for Ali Control: a tray app (with a small idle footprint, see
[Performance](../README.md#performance)) that acts as the BLE peripheral the Android tablet connects to. Setup instructions are in the
repository [README](../README.md); the agent itself is described in
[docs/WINDOWS_AGENT.md](../docs/WINDOWS_AGENT.md) and the protocol in
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

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
| Output | `dist\AliControl\AliControl.exe` (self-contained single file, 135 MiB on disk; about 75 MB working set / 14 MB private memory when idle, measured on one PC — see README Performance) |
