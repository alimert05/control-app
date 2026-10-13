using AliControlWindows.State;

namespace AliControlWindows.App;

public enum TrayIconKind
{
    /// <summary>Bluetooth off or unavailable.</summary>
    Off,

    /// <summary>Advertising, waiting for the tablet.</summary>
    Waiting,

    /// <summary>A tablet is connected.</summary>
    Connected,

    /// <summary>Something needs attention (advertising failed, Bluetooth unsupported).</summary>
    Problem
}

/// <summary>The tray menu's information lines and icon, derived purely from <see cref="AgentStatus"/> so every state is unit-tested.</summary>
public sealed record TrayStatusText(string Bluetooth, string Tablet, string Advertising, string Tooltip, TrayIconKind Icon)
{
    public static TrayStatusText From(AgentStatus status)
    {
        string bluetooth = status.Bluetooth switch
        {
            BluetoothState.On => "Bluetooth: On",
            BluetoothState.Off => "Bluetooth: Off",
            BluetoothState.NoAdapter => "Bluetooth: No adapter found",
            BluetoothState.Unsupported => "Bluetooth: Adapter cannot act as a peripheral",
            _ => "Bluetooth: Checking…"
        };

        string tablet = status.TabletLabel is { } label ? $"Tablet: Connected ({label})" : "Tablet: Not connected";

        string advertising = status.Advertising switch
        {
            AdvertisingState.Advertising => status.Mode == AdvertisingMode.CompactDiscoveryUuid
                ? "Advertising: Yes (compact discovery)"
                : "Advertising: Yes",
            AdvertisingState.Starting => "Advertising: Starting…",
            AdvertisingState.Failed => "Advertising: Failed — try Restart Advertising",
            _ => "Advertising: No"
        };

        TrayIconKind icon;
        string summary;
        if (status.TabletConnected)
        {
            icon = TrayIconKind.Connected;
            summary = "Tablet connected";
        }
        else if (status.Bluetooth is BluetoothState.NoAdapter or BluetoothState.Unsupported
                 || status.Advertising == AdvertisingState.Failed)
        {
            icon = TrayIconKind.Problem;
            summary = status.Bluetooth is BluetoothState.NoAdapter or BluetoothState.Unsupported
                ? "Bluetooth unavailable"
                : "Advertising failed";
        }
        else if (status.Bluetooth != BluetoothState.On)
        {
            icon = TrayIconKind.Off;
            summary = status.Bluetooth == BluetoothState.Off ? "Bluetooth is off" : "Starting";
        }
        else
        {
            icon = TrayIconKind.Waiting;
            summary = status.Advertising == AdvertisingState.Advertising ? "Waiting for the tablet" : "Starting";
        }

        return new TrayStatusText(bluetooth, tablet, advertising, $"Ali Control — {summary}", icon);
    }
}
