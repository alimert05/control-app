# Architecture and protocol

How the Android client and the Windows agent are put together and how they talk to each other.
For installation and usage see the [README](../README.md).

## Components

```
Android tablet (BLE central)
  ui/                      MainActivity + secondary screens
    └─ ui/dashboard/       ViewPager2, grid/page/widget renderers, edit gestures
         └─ dashboard/     UI-independent page/widget/6x4 grid data, editor, serializer
                           (+ schema migration), layout store, widget availability rules
              └─ live/     DesktopStateStore — one shared source of live telemetry,
                           media state and the connected desktop's identity (never persisted)
                   └─ actions/  ActionLibrary metadata + shared ActionDispatcher
                        └─ transport/ + ble/   BluetoothTransport, protocol messages
                                  ⇅ BLE
Windows agent (BLE peripheral)
  App/                     tray lifecycle, Open at Startup, diagnostics, composition root
    └─ BLE/                GATT server (WinRT), connection state machine, priority queue/pump
         └─ Protocol/      ProtocolMessage, action IDs, desktop_info / capabilities
              └─ Actions/  ActionRegistry + fixed Windows implementations
              └─ Live/     LiveStatePublisher — one 2 s sampler, wire messages
                   └─ Telemetry/  CPU / RAM / GPU samplers
                   └─ Media/      Windows media-session source + selection policy
                   └─ Artwork/    resize/compress/cache + chunked transfer

Android kiosk/ and device/ remain independent supporting layers for immersive
mode, boot launch, keep-awake, and tablet-local settings/status.
```

Both sides implement the *same* protocol independently in their own language — Kotlin
and C# — with identical UUIDs, identical JSON message shapes and an identical
action-ID allowlist. Cross-platform fixtures on both sides (the literal JSON strings in
`ProtocolFixtureTest.kt` and in the agent's `Protocol/` tests, and a shared SHA-256
artwork-id fixture) exist to catch wire drift.

## BLE architecture

- **Android tablet = BLE central/client.** It scans, connects, writes commands and
  subscribes to state notifications
  ([`BluetoothTransport.kt`](../android/app/src/main/java/com/alicontrol/app/transport/BluetoothTransport.kt)).
- **Windows PC = BLE peripheral/server.** It advertises the Ali Control service, exposes
  the command and state characteristics and executes commands as they arrive
  ([`WinRtGattServer.cs`](../windows/AliControlWindows/BLE/WinRtGattServer.cs),
  [`PeripheralManager.cs`](../windows/AliControlWindows/BLE/PeripheralManager.cs)) using the
  WinRT `GattServiceProvider`. No pairing dialog, no extra hardware, no admin rights.
- The adapter must support the **peripheral role** (`BluetoothAdapter.IsPeripheralRoleSupported`);
  the tray says so if it does not. `AliControl.exe --diagnose` reports it.

### Service / characteristic UUIDs

Defined once on the Android side
([`AliControlBleProfile.kt`](../android/app/src/main/java/com/alicontrol/app/ble/AliControlBleProfile.kt))
and reused verbatim on Windows
([`AliControlBleProfile.cs`](../windows/AliControlWindows/BLE/AliControlBleProfile.cs)) —
**stable since the first protocol version**:

| Purpose | UUID |
|---|---|
| Ali Control service | `7eb4052b-fc5f-436f-ae69-c3c9e7e9809f` |
| Command characteristic (tablet writes, PC receives) | `6544209f-88b6-432e-9db1-c672b7a31916` |
| State characteristic (PC notifies, tablet subscribes) | `71dbc91d-eb99-4a50-b875-c3f045f4ea91` |
| Client Characteristic Config descriptor (standard; the tablet writes it, Windows manages it) | `00002902-0000-1000-8000-00805f9b34fb` |
| **Compact discovery alias** (fallback only, see below) | `a11c0e01-0000-1000-8000-00805f9b34fb` |

**The compact alias.** Some Bluetooth adapters/drivers refuse to advertise a connectable
GATT service whose 128-bit UUID plus the device name overflow the legacy advertisement
packet (Windows reports the advertisement status `Aborted`; this happens on the
development PC). When that happens the agent hosts the **same two characteristics** under
the 32-bit alias service above, which advertises reliably. The tablet scans for **both**
UUIDs and uses whichever service the device exposes, so nothing after discovery differs.
The tray shows "Advertising: Yes (compact discovery)" when the alias is in use.

### Connection readiness & reconnection

**"Connected" means genuinely usable, not just GATT-linked.** The tablet's
`BluetoothTransport.ConnectionState` is
`DISCONNECTED → SCANNING → CONNECTING → PREPARING → CONNECTED`; `PREPARING` covers MTU
negotiation (185), service discovery, characteristic lookup and the CCCD write — it only
advances once `onDescriptorWrite` *confirms* success. A single 15 s timeout covers
`CONNECTING`+`PREPARING`; stale callbacks from an abandoned attempt are ignored.

**Tablet auto-reconnect:** after an unexpected drop of a previously fully-connected link
the transport retries the same device by address (no scanning) up to 3 times (2 s, 5 s,
10 s), then stops; manual Connect is always available.

**Windows side** (all event-driven; nothing polls Bluetooth):

- the radio being switched **off** tears down the service, any connection, the queue and
  telemetry; **on** re-creates and advertises again;
- a tablet **subscribing** triggers the connection handshake and starts telemetry;
  **unsubscribing/disconnecting** cancels artwork, stops telemetry, and re-checks that
  advertising is still up (Windows may pause it while connected);
- after **sleep/resume** the service is rebuilt from scratch;
- advertising failures are retried with bounded back-off (3 s, 10 s, 30 s, 60 s), then the
  agent waits for **Restart Advertising**, a Bluetooth power cycle or a resume;
- on **Quit** or sign-out advertising is stopped explicitly.

## Protocol

A minimal JSON envelope, hand-rolled on Android (no JSON library) and in the agent
(byte-identical wire output); neither side depends on key order:

```json
{"type":"command","action":"volume_up"}
{"type":"state","action":"volume_up","status":"ok"}
{"type":"state","status":"connected"}
```

A failed command gets `"status":"error"` instead of silence. Inbound payloads are
strictly validated on both sides: complete JSON object, correct field types, bounded size
(the agent caps writes at 512 bytes and rejects invalid UTF-8) — a truncated payload can
never half-parse into a different command.

**MTU / never-truncate rule.** The tablet negotiates MTU 185 on connect and refuses to
write anything larger than the negotiated payload. The agent reads the client's real
`MaxNotificationSize` at send time and refuses to send a notification that does not fit
(it drops one telemetry update rather than corrupt the stream — another follows 2 s
later); every live message is designed to stay under 160 bytes, verified by tests.

### Live state

The desktop→tablet direction reuses the state characteristic; messages are told apart by
`type`, and each side ignores the other family entirely:

```json
{"type":"metrics","cpu":34,"memory":48,"gpu":12}
{"type":"media","available":true,"source":"spotify","playing":true,"position":83,"duration":214}
{"type":"track","title":"Song","artist":"Artist","album":"Album","artworkId":"0123456789abcdef"}
```

An **omitted metric means unavailable**, never zero — that is what lets a widget choose
between `0%` and `--`. Playback state (small, changes every tick) and track identity
(larger, sent only when the track changes) are separate messages. Track text is bounded in
escaped UTF-8 bytes on grapheme boundaries; with an `artworkId` present the three text
fields share the ~79 bytes left under the 160-byte limit (a short artist leaves room for a
long title), so short metadata is never truncated and no combination can overflow.

### Desktop identity and capabilities

Sent once per connection, right after the tablet subscribes (protocol version 2; nothing
that existed before changed):

```json
{"type":"desktop_info","platform":"windows","protocol":2,"name":"ALI-PC"}
{"type":"capabilities","ids":["actions","telemetry.cpu","telemetry.memory","telemetry.gpu","media","media.artwork"]}
```

The tablet stores the connected platform **dynamically** (`DesktopState.desktop`); no
connected platform is hardcoded anywhere. `capabilities` messages are additive (a long
list may be split, and a new `desktop_info` starts the set over). The agent announces
**only capabilities it probed as working on that machine**:

| Capability | Announced when |
|---|---|
| `actions` | always |
| `telemetry.cpu` / `telemetry.memory` | the native counters answer |
| `telemetry.gpu` | PDH `GPU Engine` counters exist on this PC |
| `media` / `media.artwork` | the Windows media-session service is reachable |
| `telemetry.temperature.cpu` / `.gpu` | **never** (no reliable user-mode source; see below) |

**How the tablet uses them** (`WidgetSupport`): a widget the connected desktop cannot
serve — an unknown platform, or a capability it did not announce — is **dimmed and not
tappable, but never removed from the saved layout**, and works again as soon as a desktop
that supports it connects. Until the agent has introduced itself / announced capabilities,
nothing is gated. While disconnected, remote widgets are dimmed.

### Artwork over BLE

Artwork stays local and request-driven. The agent reads the thumbnail the Windows media
session itself provides (never a URL), centre-crops to a square, resizes to **160×160** and
encodes JPEG at quality **0.60, then 0.40, then 0.25** until it is at most **20 KiB**
(otherwise it is skipped; real Spotify thumbnails come out ≈ 4 KB). `artworkId` is the
lowercase hex of the first eight bytes of SHA-256 over the *final compressed bytes*.
Track metadata carries only that ID; the tablet checks its own cache and sends, only on a
miss:

```json
{"type":"artwork_request","artworkId":"0123456789abcdef"}
```

The agent only answers IDs in its own processed-artwork cache; the request is a closed
message (any extra field rejects it) and cannot name a path, filename or URL. A transfer
is an `artwork_begin` envelope, ordered binary chunks, and an `artwork_end` envelope; each
binary frame is `0xAC | 0x01 | artworkId (8 raw bytes) | chunk index (uint16 BE) | JPEG bytes`.
The 12-byte header is subtracted from the client's real notification limit. Notifications
go through a two-tier queue — acknowledgements, handshake and live state are high
priority, artwork frames low — and the choice is re-made before **every** send, so a
button press during a transfer is acknowledged before the next artwork frame.

The tablet validates ID, MIME type, declared size (max 20 KiB), chunk count (max 512),
exact sequential indices, byte count and the final content hash before decoding; it
decodes only completed JPEGs after a bounds pass, uses `RGB_565`, and keeps at most 8
bitmaps / 1 MiB in an LRU cache. A disconnect, track change or different artwork ID
discards the partial buffer; completed artwork survives reconnects.

## Action library

The full, explicit allowlist — nothing outside this list is ever sent or executed on
either side (no generic command path, no shell strings, no application paths over BLE).
Every action has structured metadata mirrored on both sides:

- **id** — the stable wire string ([`AliControlCommands.kt`](../android/app/src/main/java/com/alicontrol/app/ble/AliControlCommands.kt) /
  [`AliControlActions.cs`](../windows/AliControlWindows/Protocol/AliControlActions.cs))
- **label**, **category** (`MEDIA` / `SYSTEM` / `APPS` / `WINDOW` / `PRODUCTIVITY`) —
  tablet-side display metadata
- **supportedPlatforms** — currently `WINDOWS` for all
- **dangerLevel** — `SAFE` (sent immediately) or `CONFIRM` (the tablet asks first). This is a tablet UI
  convenience, not a security boundary: the agent does not check it, and `SAFE` does not mean harmless.
- **permissionRequirement** — `NONE` or `INPUT_SIMULATION`

The tablet's [`ActionLibrary.kt`](../android/app/src/main/java/com/alicontrol/app/actions/ActionLibrary.kt)
only *describes* what may be sent; the agent's
[`ActionRegistry`](../windows/AliControlWindows/Actions/ActionRegistry.cs) independently
re-validates every ID against its own allowlist.

| ID | Category | Danger | Input | Windows implementation |
|---|---|---|---|---|
| `ping` | *(protocol-only)* | — | — | acknowledged only |
| `play_pause` `next_track` `previous_track` | MEDIA | SAFE | — | system media keys (`SendInput`) |
| `volume_up` `volume_down` | MEDIA | SAFE | — | system volume keys ×5 (≈10 % step) |
| `mute_toggle` | MEDIA | SAFE | — | system mute key |
| `lock_screen` | SYSTEM | SAFE | — | `LockWorkStation()` |
| `sleep` | SYSTEM | **CONFIRM** | — | `SetSuspendState` (after the ACK is sent) |
| `display_sleep` | SYSTEM | SAFE | — | `SC_MONITORPOWER` |
| `screenshot` | SYSTEM | SAFE | ⌨ | Win+PrintScreen |
| `show_desktop` | SYSTEM | SAFE | ⌨ | Win+D |
| `windows_search` | SYSTEM | SAFE | ⌨ | Win+S |
| `task_view` | SYSTEM | SAFE | ⌨ | Win+Tab |
| `open_terminal` | APPS | SAFE | — | Windows Terminal (`wt.exe`); fixed PowerShell console if absent |
| `open_vscode` | APPS | SAFE | — | `Code.exe` from App Paths / standard locations |
| `open_explorer` | APPS | SAFE | — | `explorer.exe` |
| `open_browser` | APPS | SAFE | — | the user's real default browser (`AssocQueryString`) |
| `open_spotify` | APPS | SAFE | — | the registered `spotify:` URI |
| `open_system_settings` | APPS | SAFE | — | `ms-settings:` |
| `open_task_manager` | APPS | SAFE | — | `Taskmgr.exe` |
| `minimize_window` `close_window` | WINDOW | SAFE | — | `WM_SYSCOMMAND` to the foreground window (close never force-quits) |
| `fullscreen_toggle` | WINDOW | SAFE | — | maximize / restore (Windows has no OS-level fullscreen) |
| `copy` `paste` `undo` `redo` | PRODUCTIVITY | SAFE | ⌨ | Ctrl+C / V / Z / Y |

27 actions (6 media + 7 system + 7 apps + 3 window + 4 productivity). ⌨ = works by
injecting keyboard input into the focused window, which **Windows refuses toward elevated
(administrator) windows** — such an action fails safely with an `error` acknowledgement.
Window actions refuse the desktop, taskbar, invisible windows and the agent's own windows.
There is no shutdown / restart / sign-out / file action; `sleep` is the only confirmed one.

## Security model

The user-facing summary is in the README ([Security and privacy](../README.md#security-and-privacy)).
This is the technical basis for it.

**Safeguards that exist**

- **Allowlist only.** The tablet sends predefined action IDs and the agent maps each one to a
  fixed implementation (`ActionRegistry`). There is no generic command path: no shell strings,
  application paths, URLs or scripts cross the link. Unknown IDs get an `error` acknowledgement,
  and the Android editor has no free-text field that could introduce an ID.
- **Strict input handling.** Inbound writes are limited to 512 bytes and must be complete, valid
  JSON of a known shape; anything else is rejected rather than partially parsed.
- **No process arguments, no shell.** The only code that starts a process does so with a fixed
  target and no arguments, and no `cmd` / PowerShell command string exists. A source-audit test
  (`SourceAuditTests`) enforces this, together with the rules below.
- **No network code.** The agent has no listener, HTTP client, socket or URL, and no NuGet
  dependencies; the Android app declares no `INTERNET` permission.
- **Least privilege.** The agent runs as a standard user (`asInvoker`), never requests
  elevation, and installs no service, scheduled task or driver. Its only registry writes are the
  per-user **Open at Startup** values; its only files are its log and the optional diagnostics report
  (see [WINDOWS_AGENT.md](WINDOWS_AGENT.md)).
- **Limited blast radius.** There is no shutdown, restart, sign-out or file action. Keystroke
  actions are blocked by Windows toward elevated windows, and window actions refuse the desktop,
  taskbar, invisible windows and the agent's own windows.
- **One active client.** Commands are only acted on when they come from the client that is
  currently subscribed to the state characteristic.

**Known gaps**

- **No pairing or authentication.** Both GATT characteristics are created with
  `GattProtectionLevel.Plain`, and the Android client never bonds with the PC. The project neither
  requires nor verifies an authenticated or encrypted link.
- **No application-level encryption.** Messages are plain JSON.
- **Consequences.** Any BLE central within range of a running agent can connect and subscribe;
  it can then invoke any allowlisted action (including `lock_screen`, `sleep` and the
  keyboard-shortcut actions), receive the live state (PC name from `desktop_info`, CPU / RAM / GPU
  figures, now-playing metadata and artwork), and — because the newest subscriber becomes the active
  client — replace the tablet's session. The tablet's confirmation dialog for `sleep` is a UI
  convenience; the agent does not enforce it.
- **Mitigation today** is operational: run the agent only where this is acceptable and use **Quit**
  in the tray when it is not needed. Authenticated pairing and stronger protection of the BLE link are
  planned for future releases; they are not implemented in v0.1.0 (see the [roadmap](../README.md#roadmap)).
