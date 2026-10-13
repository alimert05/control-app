using System.Globalization;
using System.Text.RegularExpressions;

namespace AliControlWindows.State;

public enum BluetoothState
{
    Unknown,
    NoAdapter,
    Unsupported,
    Off,
    On
}

public enum AdvertisingState
{
    Stopped,
    Starting,
    Advertising,
    Failed
}

/// <summary>Which service the agent ended up advertising (see <c>AliControlBleProfile.DiscoveryServiceUuid</c>).</summary>
public enum AdvertisingMode
{
    None,
    FullServiceUuid,
    CompactDiscoveryUuid
}

/// <summary>
/// Local, in-process status for the tray UI. Never sent over BLE — the wire-level
/// connection state the tablet cares about is derived from GATT subscribe /
/// unsubscribe, not from this.
/// </summary>
/// <param name="TabletLabel">Null when no tablet is connected; otherwise a short label (the peer's Bluetooth address).</param>
public sealed record AgentStatus(
    BluetoothState Bluetooth = BluetoothState.Unknown,
    AdvertisingState Advertising = AdvertisingState.Stopped,
    AdvertisingMode Mode = AdvertisingMode.None,
    string? TabletLabel = null)
{
    public bool TabletConnected => TabletLabel != null;
}

/// <summary>Turns a Windows BLE device id into a short human-readable label.</summary>
public static partial class ClientLabel
{
    [GeneratedRegex(@"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")]
    private static partial Regex TrailingAddress();

    /// <summary>
    /// A Windows device id looks like <c>BluetoothLE#BluetoothLE&lt;adapter&gt;-&lt;peer address&gt;</c>.
    /// The peer address is the closest thing to a name available to a peripheral
    /// (a connecting central's friendly name is not exposed), so that is what the
    /// tray shows.
    /// </summary>
    public static string FromDeviceId(string deviceId)
    {
        Match match = TrailingAddress().Match(deviceId);
        if (match.Success) return match.Value.ToUpper(CultureInfo.InvariantCulture);
        return deviceId.Length <= 12 ? deviceId : deviceId[^12..];
    }
}
