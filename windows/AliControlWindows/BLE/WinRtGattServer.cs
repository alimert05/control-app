using System.Collections.Concurrent;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace AliControlWindows.BLE;

/// <summary>
/// The Windows BLE GATT server (peripheral role), built on the WinRT
/// <see cref="GattServiceProvider"/> API — the supported way for a desktop app to
/// act as a Bluetooth LE peripheral. It needs no administrator rights, no driver
/// and no package identity, and it never opens a network port.
///
/// <para>What it hosts: one service with the <b>command</b> characteristic
/// (<c>Write</c> | <c>WriteWithoutResponse</c>, written by the tablet) and the
/// <b>state</b> characteristic (<c>Notify</c>). Windows adds the Client
/// Characteristic Configuration descriptor to a notify characteristic itself, which
/// is what the tablet writes to subscribe. Both use <c>GattProtectionLevel.Plain</c>,
/// so — exactly as before — no pairing dialog is involved.</para>
///
/// <para>Flow control: <c>NotifyValueAsync</c> completes when the stack has handed
/// the notification to the radio, and the caller (<see cref="NotificationPump"/>)
/// awaits each one, so a slow link naturally paces the sender.</para>
///
/// <para>Advertising settle: on some adapters Windows reports a transient
/// <c>Aborted</c> followed by <c>Started</c> a few milliseconds later, and on others
/// it stays <c>Aborted</c> for good (a connectable advertisement carrying a 128-bit
/// service UUID plus the device name, rejected by the driver). <see cref="StartAsync"/>
/// therefore treats the first <c>Aborted</c> as provisional and only reports failure
/// if <c>Started</c> does not follow within a short settle window.</para>
/// </summary>
public sealed class WinRtGattServer : IBleGattServer
{
    private static readonly TimeSpan SettleAfterAbort = TimeSpan.FromMilliseconds(1500);
    private static readonly TimeSpan StartTimeout = TimeSpan.FromSeconds(5);
    private const int MaxInboundWriteBytes = 4096;

    private readonly Action<string>? _log;
    private readonly object _gate = new();
    private readonly ConcurrentDictionary<string, GattSubscribedClient> _subscribed = new();

    private GattServiceProvider? _provider;
    private GattLocalCharacteristic? _command;
    private GattLocalCharacteristic? _state;
    private int _generation;
    private bool _disposed;

    public WinRtGattServer(Action<string>? log = null) => _log = log;

    public event Action<string>? ClientSubscribed;
    public event Action<string>? ClientUnsubscribed;
    public event Action<string, byte[]>? WriteReceived;
    public event Action<AdvertisingEvent>? AdvertisingChanged;

    public bool IsAdvertising
    {
        get
        {
            GattServiceProvider? provider;
            lock (_gate) provider = _provider;
            return provider?.AdvertisementStatus is GattServiceProviderAdvertisementStatus.Started
                or GattServiceProviderAdvertisementStatus.StartedWithoutAllAdvertisementData;
        }
    }

    public async Task<GattStartResult> StartAsync(Guid serviceUuid, CancellationToken cancellationToken)
    {
        Stop();

        int generation;
        lock (_gate)
        {
            if (_disposed) return new GattStartResult(false, "disposed");
            generation = ++_generation;
        }

        try
        {
            GattServiceProviderResult created = await GattServiceProvider.CreateAsync(serviceUuid);
            if (created.Error != BluetoothError.Success) return new GattStartResult(false, $"CreateAsync: {created.Error}");
            GattServiceProvider provider = created.ServiceProvider;

            GattLocalCharacteristicResult command = await provider.Service.CreateCharacteristicAsync(
                AliControlBleProfile.CommandCharacteristicUuid,
                new GattLocalCharacteristicParameters
                {
                    CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
                    WriteProtectionLevel = GattProtectionLevel.Plain,
                    UserDescription = "Ali Control command"
                });
            if (command.Error != BluetoothError.Success) return new GattStartResult(false, $"command characteristic: {command.Error}");

            GattLocalCharacteristicResult state = await provider.Service.CreateCharacteristicAsync(
                AliControlBleProfile.StateCharacteristicUuid,
                new GattLocalCharacteristicParameters
                {
                    CharacteristicProperties = GattCharacteristicProperties.Notify,
                    ReadProtectionLevel = GattProtectionLevel.Plain,
                    UserDescription = "Ali Control state"
                });
            if (state.Error != BluetoothError.Success) return new GattStartResult(false, $"state characteristic: {state.Error}");

            var settled = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            System.Threading.Timer? abortTimer = null;

            lock (_gate)
            {
                if (_disposed || generation != _generation) return new GattStartResult(false, "superseded");
                _provider = provider;
                _command = command.Characteristic;
                _state = state.Characteristic;
            }
            command.Characteristic.WriteRequested += OnWriteRequested;
            state.Characteristic.SubscribedClientsChanged += OnSubscribedClientsChanged;
            provider.AdvertisementStatusChanged += (_, args) =>
            {
                lock (_gate)
                {
                    if (generation != _generation) return;
                }
                switch (args.Status)
                {
                    case GattServiceProviderAdvertisementStatus.Started:
                    case GattServiceProviderAdvertisementStatus.StartedWithoutAllAdvertisementData:
                        abortTimer?.Dispose();
                        settled.TrySetResult(true);
                        AdvertisingChanged?.Invoke(AdvertisingEvent.Started);
                        break;
                    case GattServiceProviderAdvertisementStatus.Aborted:
                        // Provisional: Windows may follow up with Started within milliseconds.
                        abortTimer ??= new System.Threading.Timer(_ => settled.TrySetResult(false), null, SettleAfterAbort, Timeout.InfiniteTimeSpan);
                        AdvertisingChanged?.Invoke(AdvertisingEvent.Aborted);
                        break;
                    case GattServiceProviderAdvertisementStatus.Stopped:
                        AdvertisingChanged?.Invoke(AdvertisingEvent.Stopped);
                        break;
                }
            };

            provider.StartAdvertising(new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true
            });

            Task winner = await Task.WhenAny(settled.Task, Task.Delay(StartTimeout, cancellationToken)).ConfigureAwait(false);
            abortTimer?.Dispose();
            if (winner == settled.Task) return new GattStartResult(settled.Task.Result, $"status {provider.AdvertisementStatus}");
            return new GattStartResult(IsAdvertising, $"timed out, status {provider.AdvertisementStatus}");
        }
        catch (OperationCanceledException)
        {
            Stop();
            return new GattStartResult(false, "cancelled");
        }
        catch (Exception e)
        {
            _log?.Invoke($"GATT start failed: {e.GetType().Name}: {e.Message}");
            return new GattStartResult(false, $"{e.GetType().Name}: {e.Message}");
        }
    }

    public void Stop()
    {
        GattServiceProvider? provider;
        GattLocalCharacteristic? command, state;
        lock (_gate)
        {
            _generation++;
            provider = _provider;
            command = _command;
            state = _state;
            _provider = null;
            _command = null;
            _state = null;
        }

        if (command != null) command.WriteRequested -= OnWriteRequested;
        if (state != null) state.SubscribedClientsChanged -= OnSubscribedClientsChanged;
        try
        {
            // Always stop advertising explicitly before letting go: an advertisement
            // left behind by a process that exits without stopping it can block the
            // adapter's advertising slot for the next start.
            provider?.StopAdvertising();
        }
        catch (Exception e)
        {
            _log?.Invoke($"StopAdvertising failed: {e.GetType().Name}");
        }

        var gone = _subscribed.Keys.ToArray();
        _subscribed.Clear();
        foreach (string id in gone) ClientUnsubscribed?.Invoke(id);
    }

    public int GetMaxNotificationBytes(string clientId) =>
        _subscribed.TryGetValue(clientId, out GattSubscribedClient? client) ? (int)client.MaxNotificationSize : 0;

    public async Task<NotifyStatus> NotifyAsync(string clientId, byte[] payload, CancellationToken cancellationToken)
    {
        GattLocalCharacteristic? state;
        lock (_gate) state = _state;
        if (state == null || !_subscribed.TryGetValue(clientId, out GattSubscribedClient? client)) return NotifyStatus.ClientGone;

        try
        {
            var writer = new DataWriter();
            writer.WriteBytes(payload);
            IBuffer buffer = writer.DetachBuffer();

            var operation = state.NotifyValueAsync(buffer, client);
            using CancellationTokenRegistration registration = cancellationToken.Register(operation.Cancel);
            GattClientNotificationResult result = await operation;
            cancellationToken.ThrowIfCancellationRequested();

            return result.Status switch
            {
                GattCommunicationStatus.Success => NotifyStatus.Sent,
                GattCommunicationStatus.Unreachable => NotifyStatus.ClientGone,
                GattCommunicationStatus.ProtocolError => NotifyStatus.Retry,
                _ => NotifyStatus.Dropped
            };
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception e)
        {
            // The radio went away, or the client disconnected mid-send.
            _log?.Invoke($"notify failed: {e.GetType().Name}");
            return NotifyStatus.ClientGone;
        }
    }

    public void Dispose()
    {
        lock (_gate) _disposed = true;
        Stop();
    }

    // -- GATT callbacks (arbitrary thread-pool threads) --

    private async void OnWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        // The deferral keeps the ATT response pending while we read the request;
        // the response itself is sent right here, before the command is looked at,
        // so the tablet's write completes promptly whatever the payload is. The
        // application-level acknowledgement is a separate state notification.
        var deferral = args.GetDeferral();
        try
        {
            GattWriteRequest request = await args.GetRequestAsync();
            if (request == null) return;

            byte[]? bytes = null;
            if (request.Value != null && request.Value.Length is > 0 and <= MaxInboundWriteBytes)
            {
                bytes = new byte[request.Value.Length];
                DataReader.FromBuffer(request.Value).ReadBytes(bytes);
            }
            if (request.Option == GattWriteOption.WriteWithResponse) request.Respond();

            if (bytes != null) WriteReceived?.Invoke(args.Session.DeviceId.Id, bytes);
        }
        catch (Exception e)
        {
            _log?.Invoke($"write handling failed: {e.GetType().Name}");
        }
        finally
        {
            deferral.Complete();
        }
    }

    private void OnSubscribedClientsChanged(GattLocalCharacteristic sender, object args)
    {
        try
        {
            var current = new Dictionary<string, GattSubscribedClient>();
            foreach (GattSubscribedClient client in sender.SubscribedClients)
            {
                current[client.Session.DeviceId.Id] = client;
            }

            foreach ((string id, GattSubscribedClient client) in current)
            {
                if (_subscribed.TryAdd(id, client))
                {
                    client.Session.SessionStatusChanged += OnSessionStatusChanged;
                    ClientSubscribed?.Invoke(id);
                }
                else
                {
                    _subscribed[id] = client; // refresh (the MTU may have changed)
                }
            }
            foreach (string id in _subscribed.Keys.ToArray())
            {
                if (!current.ContainsKey(id) && _subscribed.TryRemove(id, out _)) ClientUnsubscribed?.Invoke(id);
            }
        }
        catch (Exception e)
        {
            _log?.Invoke($"subscription change failed: {e.GetType().Name}");
        }
    }

    private void OnSessionStatusChanged(GattSession session, GattSessionStatusChangedEventArgs args)
    {
        if (args.Status != GattSessionStatus.Closed) return;
        string id = session.DeviceId.Id;
        session.SessionStatusChanged -= OnSessionStatusChanged;
        if (_subscribed.TryRemove(id, out _)) ClientUnsubscribed?.Invoke(id);
    }
}
