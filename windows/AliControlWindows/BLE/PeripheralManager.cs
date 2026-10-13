using AliControlWindows.Actions;
using AliControlWindows.Artwork;
using AliControlWindows.Live;
using AliControlWindows.Protocol;
using AliControlWindows.State;

namespace AliControlWindows.BLE;

public sealed class PeripheralOptions
{
    public required string MachineName { get; init; }

    /// <summary>Capability ids announced after <c>desktop_info</c>. Only capabilities that are really implemented here.</summary>
    public IReadOnlyList<string> CapabilityIds { get; init; } = new[] { Protocol.CapabilityIds.Actions };

    /// <summary>Services to try, in order (see <see cref="AliControlBleProfile.AdvertisingCandidates"/>).</summary>
    public IReadOnlyList<Guid> AdvertisingCandidates { get; init; } = AliControlBleProfile.AdvertisingCandidates;

    /// <summary>
    /// Waits between automatic attempts after every candidate failed to advertise.
    /// Bounded: once exhausted the agent waits for the user (Restart Advertising),
    /// a Bluetooth power cycle or a resume from sleep rather than looping forever.
    /// </summary>
    public IReadOnlyList<TimeSpan> AdvertisingRetryDelays { get; init; } =
        new[] { TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(60) };

    /// <summary>After waking from sleep the Bluetooth controller state is not trustworthy; rebuild the GATT service after this settle time.</summary>
    public TimeSpan ResumeRestartDelay { get; init; } = TimeSpan.FromSeconds(3);

    /// <summary>After the tablet disconnects, check shortly afterwards that advertising is still up (Windows may pause it while connected).</summary>
    public TimeSpan ReadvertiseCheckDelay { get; init; } = TimeSpan.FromSeconds(2);
}

/// <summary>
/// The BLE peripheral's connection state machine: advertises the Ali Control
/// service, accepts the tablet's subscription, and moves raw bytes between the
/// command/state characteristics and <see cref="CommandDispatcher"/>.
///
/// It knows nothing about WinRT (see <see cref="IBleGattServer"/>) and nothing
/// about Windows actions: it decodes bytes into a <see cref="ProtocolMessage"/>,
/// hands it to the dispatcher, and encodes whatever comes back.
///
/// <para><b>Startup is automatic and event-driven.</b> As soon as the Bluetooth
/// radio reports On, the service is created and advertised. There is no "start
/// server" step and no polling for readiness: radio on/off, subscribe,
/// unsubscribe, writes and advertisement-status changes all arrive as events.</para>
///
/// <para><b>Threading.</b> Every event is posted to an <see cref="IAgentExecutor"/>
/// and handled one at a time, in order. Nothing in here needs a lock except the
/// two fields the notification pump also reads.</para>
///
/// <para><b>Priority.</b> Acknowledgements, the connection handshake and live-state
/// messages are high priority; artwork frames are low priority and are re-evaluated
/// before every send (see <see cref="PriorityNotificationQueue"/>).</para>
/// </summary>
public sealed class PeripheralManager
{
    private readonly IBleGattServer _server;
    private readonly IBluetoothRadio _radio;
    private readonly CommandDispatcher _dispatcher;
    private readonly LiveStatePublisher _live;
    private readonly PeripheralOptions _options;
    private readonly IAgentExecutor _executor;
    private readonly IScheduler _scheduler;
    private readonly Action<string>? _log;

    private readonly PriorityNotificationQueue _queue = new();
    private readonly NotificationPump _pump;

    private AgentStatus _status = new();
    private volatile string? _activeClient;
    private bool _wantAdvertising;
    private int _retryIndex;
    private IDisposable? _retryTimer;
    private IDisposable? _checkTimer;
    private string? _activeArtworkTransferId;
    private int _loggedOversized;
    private bool _started;

    public PeripheralManager(
        IBleGattServer server,
        IBluetoothRadio radio,
        CommandDispatcher dispatcher,
        LiveStatePublisher live,
        PeripheralOptions options,
        IAgentExecutor executor,
        IScheduler scheduler,
        Action<string>? log = null,
        TimeSpan? pumpRetryDelay = null)
    {
        _server = server;
        _radio = radio;
        _dispatcher = dispatcher;
        _live = live;
        _options = options;
        _executor = executor;
        _scheduler = scheduler;
        _log = log;
        _pump = new NotificationPump(_queue, SendOneAsync, pumpRetryDelay, log: log);
        _pump.Drained += () => _executor.Post(OnPumpDrained);
    }

    /// <summary>The current status. Safe to read from any thread.</summary>
    public AgentStatus Status => Volatile.Read(ref _status);

    /// <summary>Raised (on the agent executor) whenever <see cref="Status"/> changes.</summary>
    public event Action<AgentStatus>? StatusChanged;

    /// <summary>The notification pump — exposed for tests that wait for the link to go quiet.</summary>
    internal NotificationPump Pump => _pump;

    // -- lifecycle --

    public async Task StartAsync()
    {
        if (_started) return;
        _started = true;

        _server.ClientSubscribed += id => _executor.Post(() => OnSubscribed(id));
        _server.ClientUnsubscribed += id => _executor.Post(() => OnUnsubscribed(id));
        _server.WriteReceived += (id, data) => _executor.Post(() => OnWrite(id, data));
        _server.AdvertisingChanged += e => _executor.Post(() => OnAdvertisingEvent(e));
        _live.MessageReady += data => _executor.Post(() => SendLiveState(data));
        _live.ArtworkChanged += id => _executor.Post(() => OnArtworkChanged(id));
        _radio.Changed += availability => _executor.Post(() => OnAvailabilityAsync(availability));

        await _radio.InitializeAsync().ConfigureAwait(false);
        BluetoothAvailability initial = _radio.Current;
        _executor.Post(() => OnAvailabilityAsync(initial));
    }

    /// <summary>
    /// Manual recovery (a tray menu item) for the rare case advertising looks
    /// stuck, without a full relaunch. A no-op unless Bluetooth is on, and while a
    /// tablet is connected (advertising is irrelevant then and rebuilding the
    /// service would drop it). Never loops or retries on its own.
    /// </summary>
    public void RestartAdvertising() => _executor.Post(async () =>
    {
        if (!_wantAdvertising)
        {
            _log?.Invoke("restart advertising ignored: Bluetooth is not on");
            return;
        }
        if (_activeClient != null)
        {
            _log?.Invoke("restart advertising ignored: a tablet is connected");
            return;
        }
        _retryIndex = 0;
        _server.Stop();
        await BeginAdvertisingAsync().ConfigureAwait(false);
    });

    /// <summary>
    /// After the PC wakes from sleep the Bluetooth controller may have been reset
    /// underneath the GATT service; tear it down and rebuild it from scratch.
    /// </summary>
    public void OnSystemResumed() => _executor.Post(() =>
    {
        if (!_wantAdvertising) return;
        _log?.Invoke("system resumed: rebuilding the Bluetooth service");
        CancelTimers();
        ResetSession();
        _live.Stop();
        _activeClient = null;
        _server.Stop();
        SetStatus(s => s with { Advertising = AdvertisingState.Starting, Mode = AdvertisingMode.None, TabletLabel = null });
        _retryIndex = 0;
        _checkTimer = _scheduler.Schedule(_options.ResumeRestartDelay, () => _executor.Post(BeginAdvertisingAsync));
    });

    /// <summary>Stops advertising and everything sampling. Called on Quit and on session end so no advertisement is left behind.</summary>
    public Task StopAsync()
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _executor.Post(() =>
        {
            TearDown(BluetoothStateOf(_radio.Current));
            done.TrySetResult();
        });
        return done.Task;
    }

    // -- radio --

    private async Task OnAvailabilityAsync(BluetoothAvailability availability)
    {
        if (availability == BluetoothAvailability.On)
        {
            SetStatus(s => s with { Bluetooth = BluetoothState.On });
            if (_wantAdvertising && (_status.Advertising is AdvertisingState.Advertising or AdvertisingState.Starting)) return;
            _wantAdvertising = true;
            _retryIndex = 0;
            await BeginAdvertisingAsync().ConfigureAwait(false);
            return;
        }

        // Off, no adapter, unsupported or unknown: the system drops the service and
        // any connection with the radio, so clear every local trace of it. The next
        // transition to On re-creates everything instead of trusting stale state.
        _log?.Invoke($"Bluetooth {availability}");
        TearDown(BluetoothStateOf(availability));
    }

    private void TearDown(BluetoothState bluetooth)
    {
        _wantAdvertising = false;
        CancelTimers();
        ResetSession();
        _live.Stop();
        _activeClient = null;
        _server.Stop();
        SetStatus(_ => new AgentStatus(bluetooth, AdvertisingState.Stopped, AdvertisingMode.None, null));
    }

    // -- advertising --

    private async Task BeginAdvertisingAsync()
    {
        if (!_wantAdvertising || _activeClient != null) return;

        CancelTimers();
        SetStatus(s => s with { Advertising = AdvertisingState.Starting });

        foreach (Guid uuid in _options.AdvertisingCandidates)
        {
            GattStartResult result;
            try
            {
                result = await _server.StartAsync(uuid, CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                result = new GattStartResult(false, $"{e.GetType().Name}: {e.Message}");
            }

            if (result.Started)
            {
                _retryIndex = 0;
                AdvertisingMode mode = uuid == AliControlBleProfile.ServiceUuid
                    ? AdvertisingMode.FullServiceUuid
                    : AdvertisingMode.CompactDiscoveryUuid;
                SetStatus(s => s with { Advertising = AdvertisingState.Advertising, Mode = mode });
                _log?.Invoke($"advertising ({mode})");
                return;
            }

            _log?.Invoke($"advertising service {uuid} failed: {result.Detail}");
            _server.Stop();
        }

        SetStatus(s => s with { Advertising = AdvertisingState.Failed, Mode = AdvertisingMode.None });
        ScheduleAdvertisingRetry();
    }

    private void ScheduleAdvertisingRetry()
    {
        if (_retryIndex >= _options.AdvertisingRetryDelays.Count)
        {
            _log?.Invoke("giving up automatic advertising retries; use Restart Advertising");
            return;
        }
        TimeSpan delay = _options.AdvertisingRetryDelays[_retryIndex++];
        _log?.Invoke($"retrying advertising in {delay.TotalSeconds:0}s (attempt {_retryIndex}/{_options.AdvertisingRetryDelays.Count})");
        _retryTimer?.Dispose();
        _retryTimer = _scheduler.Schedule(delay, () => _executor.Post(BeginAdvertisingAsync));
    }

    private void OnAdvertisingEvent(AdvertisingEvent advertisingEvent)
    {
        if (!_wantAdvertising || advertisingEvent == AdvertisingEvent.Started) return;
        // Windows reports a transient "Aborted" and then "Started" within
        // milliseconds on some adapters, and may pause advertising while a client
        // is connected. So never react to the event itself: look again shortly
        // afterwards, at the real state.
        if (_activeClient != null) return;
        ScheduleCheck(TimeSpan.FromSeconds(2));
    }

    private void ScheduleCheck(TimeSpan delay)
    {
        _checkTimer?.Dispose();
        _checkTimer = _scheduler.Schedule(delay, () => _executor.Post(EnsureAdvertisingAsync));
    }

    private async Task EnsureAdvertisingAsync()
    {
        if (_wantAdvertising && _activeClient == null && !_server.IsAdvertising)
        {
            _log?.Invoke("advertising is not active; restarting");
            await BeginAdvertisingAsync().ConfigureAwait(false);
        }
    }

    // -- connection --

    private void OnSubscribed(string clientId)
    {
        if (!_wantAdvertising || _activeClient == clientId) return;
        if (_activeClient != null) _log?.Invoke("a second tablet subscribed; the newest connection wins");

        ResetSession();
        _live.Stop();
        _activeClient = clientId;
        Interlocked.Exchange(ref _loggedOversized, 0);
        SetStatus(s => s with { TabletLabel = ClientLabel.FromDeviceId(clientId) });
        _log?.Invoke($"tablet connected ({ClientLabel.FromDeviceId(clientId)})");

        // The handshake the tablet has always received, then who we are and what
        // we can do, so it can enable or disable widgets against the real desktop.
        Enqueue(ProtocolMessage.State(ProtocolMessage.StatusConnected).Encode());
        Enqueue(DesktopInfoMessage.Encode(_options.MachineName));
        foreach (byte[] capabilities in CapabilitiesMessage.EncodeAll(_options.CapabilityIds)) Enqueue(capabilities);

        // Telemetry exists only while someone is listening.
        _live.Start();
    }

    private void OnUnsubscribed(string clientId)
    {
        if (_activeClient != clientId) return;

        _activeClient = null;
        ResetSession();
        _live.Stop();
        SetStatus(s => s with { TabletLabel = null });
        _log?.Invoke("tablet disconnected");

        // Windows may have paused advertising while the tablet was connected.
        if (_wantAdvertising) ScheduleCheck(_options.ReadvertiseCheckDelay);
    }

    /// <summary>Drops everything tied to the current tablet: queued notifications, an in-flight send, any artwork transfer.</summary>
    private void ResetSession()
    {
        // Clear first, then cancel: a send that unwinds from the cancel must find nothing stale left to resend.
        _queue.ClearAll();
        _pump.Cancel();
        _activeArtworkTransferId = null;
    }

    // -- inbound --

    private void OnWrite(string clientId, byte[] payload)
    {
        if (_activeClient != clientId)
        {
            _log?.Invoke("ignored a command from a client that has not subscribed");
            return;
        }

        // Checked first: an artwork_request carries an `artworkId` field no real
        // command has, so this can never misfire on {"type":"command",...}. It is
        // not a command: no acknowledgement is sent either way — its "reply" is an
        // artwork transfer, or silence.
        if (ArtworkWire.DecodeRequest(payload) is { } artworkId)
        {
            HandleArtworkRequest(artworkId);
            return;
        }

        ProtocolMessage? message = ProtocolMessage.Decode(payload);
        if (message == null)
        {
            _log?.Invoke($"received an unparseable command payload ({payload.Length}B)");
            Enqueue(ProtocolMessage.State(ProtocolMessage.StatusError).Encode());
            return;
        }

        ProtocolMessage reply = _dispatcher.Dispatch(message);
        if (reply.Status == ProtocolMessage.StatusError)
        {
            _log?.Invoke($"command \"{reply.Action ?? "?"}\" failed");
        }
        Enqueue(reply.Encode());
    }

    /// <summary>
    /// Validates the id, looks it up in the live publisher's cache and — only if both
    /// succeed — replaces whatever artwork transfer might already be queued with this
    /// one. "Only a known artworkId" is enforced structurally: the lookup is a dictionary
    /// read keyed by content hash, and there is no code path from the string to a file
    /// path, filename or URL.
    /// </summary>
    private void HandleArtworkRequest(string artworkId)
    {
        ProcessedArtwork? artwork = _live.ProcessedArtwork(artworkId);
        if (artwork == null)
        {
            _log?.Invoke("artwork_request for an unknown id — ignored");
            return;
        }
        string? client = _activeClient;
        if (client == null) return;

        int maxNotification = MaxNotificationBytes(client);
        int chunkPayload = ArtworkTransferSession.ChunkPayloadSize(maxNotification);
        IReadOnlyList<byte[]>? frames = ArtworkTransferSession.BuildFrames(artwork.ArtworkId, artwork.MimeType, artwork.Data, chunkPayload);
        if (frames == null)
        {
            _log?.Invoke($"could not build artwork frames for {artworkId} (chunk payload {chunkPayload}B)");
            return;
        }
        if (!ArtworkTransferSession.FramesFitWithin(frames, maxNotification))
        {
            _log?.Invoke($"artwork frames do not fit the negotiated {maxNotification}B notification limit");
            return;
        }

        // A new transfer wholesale-replaces any transfer still queued — a genuine
        // re-request, or the tablet having moved on to another track's artwork.
        _activeArtworkTransferId = artwork.ArtworkId;
        _queue.ReplaceLowPriority(frames);
        _pump.Kick();
    }

    // -- outbound --

    private void Enqueue(byte[] data)
    {
        _queue.EnqueueHighPriority(data);
        _pump.Kick();
    }

    /// <summary>
    /// Queues one live-state notification after checking it actually fits. Bounded
    /// fields already make an oversized payload impossible by construction; this is
    /// the backstop that guarantees the stack is never handed something it would
    /// silently truncate (possibly mid-UTF-8) if a client negotiated an unexpectedly
    /// small MTU. Dropping one telemetry update is always better than corrupting the
    /// stream: another follows two seconds later.
    /// </summary>
    private void SendLiveState(byte[] data)
    {
        string? client = _activeClient;
        if (client == null) return;

        int limit = Math.Min(MaxNotificationBytes(client), WireLimits.MaxNotificationBytes);
        if (data.Length > limit)
        {
            if (Interlocked.Exchange(ref _loggedOversized, 1) == 0)
            {
                _log?.Invoke($"dropping {data.Length}B live-state notification — exceeds {limit}B limit");
            }
            return;
        }
        Enqueue(data);
    }

    private void OnArtworkChanged(string? artworkId)
    {
        if (_activeArtworkTransferId != artworkId) CancelArtworkTransfer();
    }

    /// <summary>Drops any queued artwork transfer without an <c>artwork_end</c>: the tablet independently discards its receive buffer on disconnect or on a different artwork id.</summary>
    private void CancelArtworkTransfer()
    {
        _queue.ClearLowPriority();
        _activeArtworkTransferId = null;
    }

    private void OnPumpDrained()
    {
        if (!_queue.IsLowPriorityActive) _activeArtworkTransferId = null;
    }

    private int MaxNotificationBytes(string clientId)
    {
        int max = _server.GetMaxNotificationBytes(clientId);
        // Unknown means "assume the ATT default" (23-byte MTU → 20-byte payload), never "unlimited".
        return max > 0 ? max : 20;
    }

    private async Task<NotifyStatus> SendOneAsync(byte[] data, CancellationToken cancellationToken)
    {
        string? client = _activeClient;
        if (client == null) return NotifyStatus.ClientGone;

        int max = MaxNotificationBytes(client);
        if (data.Length > max)
        {
            _log?.Invoke($"dropping {data.Length}B notification — exceeds the client's {max}B limit");
            return NotifyStatus.Dropped;
        }
        return await _server.NotifyAsync(client, data, cancellationToken).ConfigureAwait(false);
    }

    // -- status --

    private void SetStatus(Func<AgentStatus, AgentStatus> update)
    {
        AgentStatus next = update(_status);
        if (next == _status) return;
        Volatile.Write(ref _status, next);
        StatusChanged?.Invoke(next);
    }

    private void CancelTimers()
    {
        _retryTimer?.Dispose();
        _retryTimer = null;
        _checkTimer?.Dispose();
        _checkTimer = null;
    }

    private static BluetoothState BluetoothStateOf(BluetoothAvailability availability) => availability switch
    {
        BluetoothAvailability.On => BluetoothState.On,
        BluetoothAvailability.Off => BluetoothState.Off,
        BluetoothAvailability.NoAdapter => BluetoothState.NoAdapter,
        BluetoothAvailability.PeripheralUnsupported => BluetoothState.Unsupported,
        _ => BluetoothState.Unknown
    };
}
