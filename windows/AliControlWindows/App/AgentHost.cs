using AliControlWindows.Actions;
using AliControlWindows.Artwork;
using AliControlWindows.BLE;
using AliControlWindows.Live;
using AliControlWindows.Media;
using AliControlWindows.Protocol;
using AliControlWindows.State;
using AliControlWindows.Telemetry;
using Microsoft.Win32;

namespace AliControlWindows.App;

/// <summary>
/// The composition root: builds the real object graph once, decides which
/// capabilities this particular PC can honestly advertise, and owns the lifetime
/// of everything that touches Bluetooth.
///
/// Capabilities are probed, not assumed: CPU and memory counters must actually
/// answer, GPU engine counters must actually exist on this machine/driver, and the
/// Windows media session service must actually be reachable. Only what passes is
/// announced to the tablet.
/// </summary>
internal sealed class AgentHost : IAsyncDisposable
{
    private readonly AgentLog _log;
    private SerialAgentExecutor? _executor;
    private WinRtGattServer? _server;
    private WinRtBluetoothRadio? _radio;
    private LiveStatePublisher? _live;
    private PeripheralManager? _peripheral;
    private bool _disposed;

    public AgentHost(AgentLog log)
    {
        _log = log;
        Startup = StartupRegistration.CreateDefault();
    }

    public StartupRegistration Startup { get; }

    public PeripheralManager Peripheral =>
        _peripheral ?? throw new InvalidOperationException("The agent has not been started");

    public bool IsStarted => _peripheral != null;

    public async Task StartAsync()
    {
        _log.Info("starting");

        // Probe what this machine can really do. Done before Bluetooth starts so the
        // (non-thread-safe) GPU counter is never touched by a tablet connecting
        // during the probe.
        var cpuAvailable = new NativeCpuTicksSource().Read() != null;
        var memoryAvailable = new NativeMemoryStatusSource().Read() != null;
        var gpu = new GpuUsageSampler();
        bool gpuAvailable = await gpu.ProbeAsync().ConfigureAwait(false);
        WindowsMediaSessionSource mediaSource = await WindowsMediaSessionSource.CreateAsync().ConfigureAwait(false);

        var capabilities = new List<string> { CapabilityIds.Actions };
        if (cpuAvailable) capabilities.Add(CapabilityIds.TelemetryCpu);
        if (memoryAvailable) capabilities.Add(CapabilityIds.TelemetryMemory);
        if (gpuAvailable) capabilities.Add(CapabilityIds.TelemetryGpu);
        if (mediaSource.IsAvailable)
        {
            capabilities.Add(CapabilityIds.Media);
            capabilities.Add(CapabilityIds.MediaArtwork);
        }
        _log.Info($"capabilities: {string.Join(", ", capabilities)}");

        var keyboard = new SendInputKeyboard();
        var registry = new ActionRegistry(
            new MediaController(keyboard),
            new SystemController(keyboard, new WindowsPowerControl()),
            new AppLauncher(new WindowsShell(), new SystemProbe()),
            new WindowController(new Win32WindowApi(), Environment.ProcessId),
            new ProductivityController(keyboard));
        var dispatcher = new CommandDispatcher(registry, _log.Info);

        var telemetry = SystemTelemetryProvider.CreateDefault(gpuAvailable ? gpu : null);
        var coordinator = new MediaSessionCoordinator(mediaSource);
        var artwork = new ArtworkPipeline(new ArtworkStore(), new ArtworkProcessor(new WinRtImageCodec()));
        _live = new LiveStatePublisher(telemetry, coordinator, artwork, log: _log.Info);

        _executor = new SerialAgentExecutor(_log.Info);
        _server = new WinRtGattServer(_log.Info);
        _radio = new WinRtBluetoothRadio();
        _peripheral = new PeripheralManager(
            _server, _radio, dispatcher, _live,
            new PeripheralOptions { MachineName = Environment.MachineName, CapabilityIds = capabilities },
            _executor, new TimerScheduler(), _log.Info);

        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        SystemEvents.SessionEnding += OnSessionEnding;

        await _peripheral.StartAsync().ConfigureAwait(false);
    }

    private void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Resume) _peripheral?.OnSystemResumed();
    }

    private void OnSessionEnding(object? sender, SessionEndingEventArgs e)
    {
        // Leave no advertisement behind when Windows is signing out or shutting down.
        try
        {
            _peripheral?.StopAsync().Wait(TimeSpan.FromSeconds(3));
        }
        catch
        {
            // best effort
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;

        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        SystemEvents.SessionEnding -= OnSessionEnding;

        try
        {
            if (_peripheral != null) await _peripheral.StopAsync().WaitAsync(TimeSpan.FromSeconds(4)).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            _log.Info("timed out stopping the peripheral");
        }
        if (_live != null) await _live.StopAndWaitAsync().WaitAsync(TimeSpan.FromSeconds(4)).ConfigureAwait(false);
        _server?.Dispose();
        _radio?.Dispose();
        if (_executor != null) await _executor.DisposeAsync().ConfigureAwait(false);
        _log.Info("stopped");
    }
}
