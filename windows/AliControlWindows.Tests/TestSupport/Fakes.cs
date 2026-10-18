using System.Collections.Concurrent;
using AliControlWindows.Actions;
using AliControlWindows.Artwork;
using AliControlWindows.BLE;
using AliControlWindows.Media;
using AliControlWindows.State;
using AliControlWindows.Telemetry;

namespace AliControlWindows.Tests.TestSupport;

// None of these touch the real machine: every call is recorded and nothing else
// happens. Running the suite never presses a key, locks or sleeps the PC,
// launches a program, changes a startup entry, or touches Bluetooth or media.

internal sealed class FakeKeyboard : IKeyboardInput
{
    public bool Result = true;
    public readonly List<VirtualKey[]> Chords = new();

    public bool SendChord(IReadOnlyList<VirtualKey> keys)
    {
        Chords.Add(keys.ToArray());
        return Result;
    }
}

internal sealed class FakeShell : IShell
{
    public bool Result = true;
    public readonly List<string> Opened = new();

    public bool Open(string target)
    {
        Opened.Add(target);
        return Result;
    }
}

internal sealed class FakeProbe : ISystemProbe
{
    public readonly HashSet<string> Files = new(StringComparer.OrdinalIgnoreCase);
    public readonly Dictionary<string, string> Environment = new(StringComparer.OrdinalIgnoreCase)
    {
        ["SystemRoot"] = @"C:\Windows",
        ["LOCALAPPDATA"] = @"C:\Users\test\AppData\Local",
        ["ProgramFiles"] = @"C:\Program Files",
        ["ProgramFiles(x86)"] = @"C:\Program Files (x86)"
    };
    public readonly Dictionary<string, string> AppPaths = new(StringComparer.OrdinalIgnoreCase);
    public readonly HashSet<string> Schemes = new(StringComparer.OrdinalIgnoreCase);
    public string? Browser;

    public bool FileExists(string path) => Files.Contains(path);
    public string? GetEnvironmentVariable(string name) => Environment.GetValueOrDefault(name);
    public string? ReadAppPath(string exeName) => AppPaths.GetValueOrDefault(exeName);
    public bool IsUriSchemeRegistered(string scheme) => Schemes.Contains(scheme);
    public string? DefaultBrowserExecutable() => Browser;
}

internal sealed class FakePower : IPowerControl
{
    public bool Result = true;
    public readonly List<string> Calls = new();

    public bool LockWorkStation() { Calls.Add("lock"); return Result; }
    public bool ScheduleSleep() { Calls.Add("sleep"); return Result; }
    public bool DisplayOff() { Calls.Add("displayOff"); return Result; }
}

internal sealed class FakeWindowApi : IWindowApi
{
    public WindowInfo? Foreground;
    public bool SendResult = true;
    public readonly List<(nint Handle, SysCommand Command)> Sent = new();

    public WindowInfo? GetForegroundWindow() => Foreground;

    public bool SendSysCommand(nint handle, SysCommand command)
    {
        Sent.Add((handle, command));
        return SendResult;
    }
}

internal sealed class FakeMedia : IMediaControl
{
    public bool Result = true;
    public readonly List<string> Calls = new();
    public bool PlayPause() => Record(nameof(PlayPause));
    public bool NextTrack() => Record(nameof(NextTrack));
    public bool PreviousTrack() => Record(nameof(PreviousTrack));
    public bool VolumeUp() => Record(nameof(VolumeUp));
    public bool VolumeDown() => Record(nameof(VolumeDown));
    public bool MuteToggle() => Record(nameof(MuteToggle));
    private bool Record(string name) { Calls.Add(name); return Result; }
}

internal sealed class FakeSystem : ISystemControl
{
    public bool Result = true;
    public bool Throws;
    public readonly List<string> Calls = new();
    public bool LockScreen() => Record(nameof(LockScreen));
    public bool Sleep() => Record(nameof(Sleep));
    public bool DisplaySleep() => Record(nameof(DisplaySleep));
    public bool Screenshot() => Record(nameof(Screenshot));
    public bool ShowDesktop() => Record(nameof(ShowDesktop));
    public bool WindowsSearch() => Record(nameof(WindowsSearch));
    public bool TaskView() => Record(nameof(TaskView));
    private bool Record(string name)
    {
        Calls.Add(name);
        if (Throws) throw new InvalidOperationException("simulated failure");
        return Result;
    }
}

internal sealed class FakeApps : IAppLauncher
{
    public bool Result = true;
    public readonly List<string> Calls = new();
    public bool OpenTerminal() => Record(nameof(OpenTerminal));
    public bool OpenVsCode() => Record(nameof(OpenVsCode));
    public bool OpenExplorer() => Record(nameof(OpenExplorer));
    public bool OpenBrowser() => Record(nameof(OpenBrowser));
    public bool OpenSpotify() => Record(nameof(OpenSpotify));
    public bool OpenSystemSettings() => Record(nameof(OpenSystemSettings));
    public bool OpenTaskManager() => Record(nameof(OpenTaskManager));
    private bool Record(string name) { Calls.Add(name); return Result; }
}

internal sealed class FakeWindow : IWindowControl
{
    public bool Result = true;
    public readonly List<string> Calls = new();
    public bool MinimizeWindow() => Record(nameof(MinimizeWindow));
    public bool CloseWindow() => Record(nameof(CloseWindow));
    public bool FullscreenToggle() => Record(nameof(FullscreenToggle));
    private bool Record(string name) { Calls.Add(name); return Result; }
}

internal sealed class FakeProductivity : IProductivityControl
{
    public bool Result = true;
    public readonly List<string> Calls = new();
    public bool Copy() => Record(nameof(Copy));
    public bool Paste() => Record(nameof(Paste));
    public bool Undo() => Record(nameof(Undo));
    public bool Redo() => Record(nameof(Redo));
    private bool Record(string name) { Calls.Add(name); return Result; }
}

/// <summary>One bundle of fakes behind a real <see cref="ActionRegistry"/>.</summary>
internal sealed class FakeControllers
{
    public readonly FakeMedia Media = new();
    public readonly FakeSystem System = new();
    public readonly FakeApps Apps = new();
    public readonly FakeWindow Window = new();
    public readonly FakeProductivity Productivity = new();

    public ActionRegistry MakeRegistry() => new(Media, System, Apps, Window, Productivity);

    public List<string> AllCalls =>
        Media.Calls.Concat(System.Calls).Concat(Apps.Calls).Concat(Window.Calls).Concat(Productivity.Calls).ToList();
}

internal sealed class FakeTelemetry : ITelemetryProvider
{
    public SystemMetrics Next = new();
    public int SampleCount;
    public int ResetCount;

    public SystemMetrics Sample()
    {
        SampleCount++;
        return Next;
    }

    public void Reset() => ResetCount++;
}

internal sealed class FakeMediaSource : IMediaSessionSource
{
    public bool IsAvailable { get; set; } = true;
    public List<MediaState> Sessions = new();
    public byte[]? Artwork;
    public Action? OnArtworkFetch;
    public int GetSessionsCount;
    public int ArtworkFetchCount;

    public Task<IReadOnlyList<MediaState>> GetSessionsAsync(CancellationToken cancellationToken = default)
    {
        GetSessionsCount++;
        return Task.FromResult<IReadOnlyList<MediaState>>(Sessions.ToList());
    }

    public Task<byte[]?> GetArtworkAsync(string sessionId, CancellationToken cancellationToken = default)
    {
        ArtworkFetchCount++;
        OnArtworkFetch?.Invoke();
        return Task.FromResult(Artwork);
    }
}

/// <summary>An image codec whose "images" are just byte arrays: output size is a function of quality, so the compression ladder is exact.</summary>
internal sealed class FakeImageCodec : IImageCodec
{
    public bool Decodable = true;
    public Func<double, int> SizeForQuality = _ => 1000;
    public readonly List<double> EncodedQualities = new();
    public int DecodeCount;

    public Task<PixelBuffer?> DecodeSquareAsync(byte[] encoded, int size)
    {
        DecodeCount++;
        if (!Decodable) return Task.FromResult<PixelBuffer?>(null);
        // Carry the input through so the encoded bytes depend on the "image".
        return Task.FromResult<PixelBuffer?>(new PixelBuffer(size, size, encoded));
    }

    public Task<byte[]?> EncodeJpegAsync(PixelBuffer pixels, double quality)
    {
        EncodedQualities.Add(quality);
        var bytes = new byte[SizeForQuality(quality)];
        for (int i = 0; i < bytes.Length; i++)
        {
            bytes[i] = pixels.Bgra.Length == 0 ? (byte)0 : (byte)(pixels.Bgra[i % pixels.Bgra.Length] + i);
        }
        return Task.FromResult<byte[]?>(bytes);
    }
}

internal sealed class FakeStartupStore : AliControlWindows.App.IStartupStore
{
    public string? RunValue;
    public byte[]? Approved;
    public bool ThrowOnWrite;

    public string? ReadRunValue(string name) => RunValue;
    public void WriteRunValue(string name, string value)
    {
        if (ThrowOnWrite) throw new UnauthorizedAccessException();
        RunValue = value;
    }
    public void DeleteRunValue(string name) => RunValue = null;
    public byte[]? ReadStartupApproved(string name) => Approved;
    public void DeleteStartupApproved(string name) => Approved = null;
}

/// <summary>
/// Collects posted work and runs it on demand, in order, on the calling thread.
/// <para>
/// <see cref="Post(Action)"/> can be called from any thread, exactly like the production
/// <see cref="SerialAgentExecutor"/> (see <see cref="IAgentExecutor"/>): the notification pump
/// raises <c>Drained</c> from a thread-pool thread, so it enqueues while the test thread is
/// dequeuing in <see cref="RunAllAsync"/>. The queue therefore has to be thread-safe.
/// </para>
/// </summary>
internal sealed class ManualExecutor : IAgentExecutor
{
    private readonly ConcurrentQueue<Func<Task>> _queue = new();

    public void Post(Action work) => _queue.Enqueue(() =>
    {
        work();
        return Task.CompletedTask;
    });

    public void Post(Func<Task> work) => _queue.Enqueue(work);

    public async Task RunAllAsync()
    {
        while (_queue.TryDequeue(out Func<Task>? work)) await work();
    }

    public int Pending => _queue.Count;
}

internal sealed class ManualScheduler : IScheduler
{
    private sealed class Entry : IDisposable
    {
        public TimeSpan Delay;
        public Action Callback = () => { };
        public bool Cancelled;
        public bool Fired;
        public void Dispose() => Cancelled = true;
    }

    private readonly List<Entry> _entries = new();

    public IDisposable Schedule(TimeSpan delay, Action callback)
    {
        var entry = new Entry { Delay = delay, Callback = callback };
        _entries.Add(entry);
        return entry;
    }

    public IReadOnlyList<TimeSpan> PendingDelays => _entries.Where(e => !e.Cancelled && !e.Fired).Select(e => e.Delay).ToList();

    /// <summary>Fires every callback that is still pending (in scheduling order).</summary>
    public void FireAll()
    {
        foreach (Entry entry in _entries.Where(e => !e.Cancelled && !e.Fired).ToList())
        {
            entry.Fired = true;
            entry.Callback();
        }
    }
}

internal sealed class FakeRadio : IBluetoothRadio
{
    public BluetoothAvailability Current { get; set; } = BluetoothAvailability.On;
    public event Action<BluetoothAvailability>? Changed;
    public Task InitializeAsync() => Task.CompletedTask;
    public void Raise(BluetoothAvailability value)
    {
        Current = value;
        Changed?.Invoke(value);
    }
    public void Dispose() { }
}

internal sealed class FakeGattServer : IBleGattServer
{
    public event Action<string>? ClientSubscribed;
    public event Action<string>? ClientUnsubscribed;
    public event Action<string, byte[]>? WriteReceived;
    public event Action<AdvertisingEvent>? AdvertisingChanged;

    public bool IsAdvertising { get; set; }
    public readonly List<Guid> StartCalls = new();
    public int StopCalls;
    public Func<Guid, GattStartResult> StartBehavior = _ => new GattStartResult(true, "started");
    public int MaxNotification = 182;
    public Func<byte[], NotifyStatus> NotifyBehavior = _ => NotifyStatus.Sent;
    public readonly List<byte[]> Notifications = new();
    public TaskCompletionSource? NotifyGate;

    public Task<GattStartResult> StartAsync(Guid serviceUuid, CancellationToken cancellationToken)
    {
        StartCalls.Add(serviceUuid);
        GattStartResult result = StartBehavior(serviceUuid);
        IsAdvertising = result.Started;
        return Task.FromResult(result);
    }

    public void Stop()
    {
        StopCalls++;
        IsAdvertising = false;
    }

    public int GetMaxNotificationBytes(string clientId) => MaxNotification;

    public async Task<NotifyStatus> NotifyAsync(string clientId, byte[] payload, CancellationToken cancellationToken)
    {
        if (NotifyGate != null) await NotifyGate.Task.WaitAsync(cancellationToken);
        NotifyStatus status = NotifyBehavior(payload);
        if (status == NotifyStatus.Sent)
        {
            lock (Notifications) Notifications.Add(payload);
        }
        return status;
    }

    public void SimulateSubscribe(string id) => ClientSubscribed?.Invoke(id);
    public void SimulateUnsubscribe(string id) => ClientUnsubscribed?.Invoke(id);
    public void SimulateWrite(string id, byte[] data) => WriteReceived?.Invoke(id, data);
    public void SimulateAdvertising(AdvertisingEvent e) => AdvertisingChanged?.Invoke(e);

    public List<string> SentJson()
    {
        lock (Notifications)
        {
            return Notifications.Where(n => n.Length > 0 && n[0] == (byte)'{').Select(n => System.Text.Encoding.UTF8.GetString(n)).ToList();
        }
    }

    public void Dispose() { }
}
