namespace AliControlWindows.BLE;

public enum AdvertisingEvent
{
    Started,
    Aborted,
    Stopped
}

public enum NotifyStatus
{
    /// <summary>Delivered to the client.</summary>
    Sent,

    /// <summary>A transient protocol-level failure; the notification should be retried (bounded).</summary>
    Retry,

    /// <summary>This notification cannot be delivered (for example access denied); discard it and continue.</summary>
    Dropped,

    /// <summary>The client is gone; stop sending.</summary>
    ClientGone
}

public sealed record GattStartResult(bool Started, string Detail);

/// <summary>
/// The BLE GATT *server* (peripheral) side, abstracted from the operating system
/// so that every connection-lifecycle rule in <see cref="PeripheralManager"/> is
/// unit-tested against a fake. The real implementation is <see cref="WinRtGattServer"/>.
///
/// Clients are identified by an opaque id (stable for the lifetime of one
/// connection). The tablet is "subscribed" once it has enabled notifications on
/// the state characteristic — the same point at which the Android side reports
/// itself fully connected.
/// </summary>
public interface IBleGattServer : IDisposable
{
    /// <summary>A client enabled notifications on the state characteristic. Raised on an arbitrary thread.</summary>
    event Action<string>? ClientSubscribed;

    /// <summary>A client disabled notifications or disconnected. Raised on an arbitrary thread.</summary>
    event Action<string>? ClientUnsubscribed;

    /// <summary>A write arrived on the command characteristic (already acknowledged at the ATT level). Raised on an arbitrary thread.</summary>
    event Action<string, byte[]>? WriteReceived;

    /// <summary>The advertisement status changed. Raised on an arbitrary thread.</summary>
    event Action<AdvertisingEvent>? AdvertisingChanged;

    /// <summary>Whether the service is currently being advertised (right now, not as of the last event).</summary>
    bool IsAdvertising { get; }

    /// <summary>
    /// Creates the service (command + state characteristics) under
    /// <paramref name="serviceUuid"/> and starts advertising it. Completes when
    /// advertising has settled: <c>Started</c> = true; rejected or timed out = false.
    /// </summary>
    Task<GattStartResult> StartAsync(Guid serviceUuid, CancellationToken cancellationToken);

    /// <summary>Stops advertising and releases the service. Safe to call at any time, repeatedly.</summary>
    void Stop();

    /// <summary>The largest notification payload the client can currently receive, or 0 if unknown. Read at send time: it changes when the MTU is negotiated.</summary>
    int GetMaxNotificationBytes(string clientId);

    Task<NotifyStatus> NotifyAsync(string clientId, byte[] payload, CancellationToken cancellationToken);
}

public enum BluetoothAvailability
{
    Unknown,
    NoAdapter,
    PeripheralUnsupported,
    Off,
    On
}

/// <summary>Bluetooth radio presence and power state, event-driven (no polling).</summary>
public interface IBluetoothRadio : IDisposable
{
    BluetoothAvailability Current { get; }

    /// <summary>Raised on an arbitrary thread whenever <see cref="Current"/> changes.</summary>
    event Action<BluetoothAvailability>? Changed;

    Task InitializeAsync();
}
