using Windows.Devices.Bluetooth;
using Windows.Devices.Radios;

namespace AliControlWindows.BLE;

/// <summary>
/// Bluetooth radio presence and power state from <c>Windows.Devices.Radios</c> and
/// <c>BluetoothAdapter</c>. Event-driven: <see cref="Radio.StateChanged"/> reports
/// the radio being switched off or on (Settings, Action Center, airplane mode, a
/// hardware switch), so nothing ever polls Bluetooth.
/// </summary>
public sealed class WinRtBluetoothRadio : IBluetoothRadio
{
    private readonly object _gate = new();
    private readonly List<Radio> _radios = new();
    private BluetoothAvailability _current = BluetoothAvailability.Unknown;
    private bool _peripheralSupported = true;

    public BluetoothAvailability Current
    {
        get
        {
            lock (_gate) return _current;
        }
    }

    public event Action<BluetoothAvailability>? Changed;

    public async Task InitializeAsync()
    {
        try
        {
            BluetoothAdapter? adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null)
            {
                Set(BluetoothAvailability.NoAdapter);
                return;
            }
            if (!adapter.IsLowEnergySupported || !adapter.IsPeripheralRoleSupported)
            {
                _peripheralSupported = false;
                Set(BluetoothAvailability.PeripheralUnsupported);
                return;
            }

            IReadOnlyList<Radio> radios = await Radio.GetRadiosAsync();
            lock (_gate)
            {
                foreach (Radio radio in radios.Where(r => r.Kind == RadioKind.Bluetooth))
                {
                    radio.StateChanged += OnRadioStateChanged;
                    _radios.Add(radio);
                }
            }
            Recompute();
        }
        catch
        {
            Set(BluetoothAvailability.Unknown);
        }
    }

    private void OnRadioStateChanged(Radio sender, object args) => Recompute();

    private void Recompute()
    {
        if (!_peripheralSupported) return;
        BluetoothAvailability next;
        lock (_gate)
        {
            next = _radios.Count == 0
                ? BluetoothAvailability.NoAdapter
                : _radios.Any(r => r.State == RadioState.On) ? BluetoothAvailability.On : BluetoothAvailability.Off;
        }
        Set(next);
    }

    private void Set(BluetoothAvailability value)
    {
        lock (_gate)
        {
            if (_current == value) return;
            _current = value;
        }
        Changed?.Invoke(value);
    }

    public void Dispose()
    {
        lock (_gate)
        {
            foreach (Radio radio in _radios) radio.StateChanged -= OnRadioStateChanged;
            _radios.Clear();
        }
    }
}
