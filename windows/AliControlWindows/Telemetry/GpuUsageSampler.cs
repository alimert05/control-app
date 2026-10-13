using System.Runtime.InteropServices;

namespace AliControlWindows.Telemetry;

/// <summary>One <c>\GPU Engine(*)\Utilization Percentage</c> instance: a single process on a single engine of a single GPU.</summary>
public readonly record struct GpuEngineSample(string InstanceName, double Percent);

public interface IGpuEngineCounterSource : IDisposable
{
    /// <summary>Opens the counter. False when this machine/driver exposes no GPU engine counters.</summary>
    bool Open();

    /// <summary>Collects and returns every instance's current value, or null on failure / when not open.</summary>
    IReadOnlyList<GpuEngineSample>? Read();

    void Close();
}

/// <summary>
/// GPU utilisation from the Windows performance counters — the same
/// <c>GPU Engine</c> counter set Task Manager's GPU column is built from. It is
/// vendor-neutral (NVIDIA, AMD, Intel), needs no administrator rights and no
/// driver, and is read through PDH (<c>pdh.dll</c>), a documented user-mode API.
///
/// <para>How the single number is derived (<see cref="Aggregate"/>): the counter
/// reports one instance per <i>process × engine</i>. Instances are summed per
/// engine (all processes together), the busiest engine represents each GPU
/// (Task Manager does the same), and the busiest GPU represents the machine.
/// A laptop's idle integrated GPU therefore never hides a loaded discrete GPU.</para>
///
/// <para>The PDH query is opened only while a tablet is subscribed and closed
/// again by <see cref="Reset"/>, so an unattended PC does no GPU counter work.</para>
/// </summary>
public sealed class GpuUsageSampler
{
    private readonly IGpuEngineCounterSource _source;
    private bool _isOpen;

    public GpuUsageSampler() : this(new PdhGpuEngineCounterSource()) { }

    public GpuUsageSampler(IGpuEngineCounterSource source) => _source = source;

    /// <summary>
    /// Whether GPU utilisation can be read on this machine right now. Used once
    /// at startup to decide whether to advertise the <c>telemetry.gpu</c>
    /// capability; leaves the counter closed afterwards.
    /// </summary>
    public async Task<bool> ProbeAsync(TimeSpan? settle = null)
    {
        try
        {
            if (!_source.Open()) return false;
            // Rate counters need two collections some time apart before they
            // report valid data; Open() took the baseline.
            await Task.Delay(settle ?? TimeSpan.FromMilliseconds(400)).ConfigureAwait(false);
            var samples = _source.Read();
            return samples != null && Aggregate(samples) != null;
        }
        catch
        {
            return false;
        }
        finally
        {
            _source.Close();
            _isOpen = false;
        }
    }

    /// <summary>
    /// The current GPU utilisation, or null (first sample after a reset has no
    /// baseline; also when counters are unavailable).
    /// </summary>
    public double? Sample()
    {
        if (!_isOpen)
        {
            if (!_source.Open()) return null;
            _isOpen = true;
            // Rate counters need two collections: the first (inside Open) is the baseline.
            return null;
        }
        var samples = _source.Read();
        return samples == null ? null : Aggregate(samples);
    }

    public void Reset()
    {
        _source.Close();
        _isOpen = false;
    }

    /// <summary>
    /// Pure aggregation (see class remarks). Instances whose names are not in
    /// the documented <c>pid_…_luid_…_phys_…_eng_…_engtype_…</c> shape are
    /// ignored rather than guessed at. Returns null when nothing usable remains.
    /// </summary>
    public static double? Aggregate(IEnumerable<GpuEngineSample> samples)
    {
        var perEngine = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        var engineAdapter = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        foreach (GpuEngineSample sample in samples)
        {
            if (!double.IsFinite(sample.Percent) || sample.Percent < 0) continue;

            int luid = sample.InstanceName.IndexOf("_luid_", StringComparison.OrdinalIgnoreCase);
            int engine = sample.InstanceName.IndexOf("_eng_", StringComparison.OrdinalIgnoreCase);
            if (luid < 0 || engine < luid) continue;

            string engineKey = sample.InstanceName[luid..];
            string adapterKey = sample.InstanceName[luid..engine];
            perEngine[engineKey] = perEngine.GetValueOrDefault(engineKey) + sample.Percent;
            engineAdapter[engineKey] = adapterKey;
        }
        if (perEngine.Count == 0) return null;

        var perAdapter = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        foreach ((string engineKey, double total) in perEngine)
        {
            string adapterKey = engineAdapter[engineKey];
            perAdapter[adapterKey] = Math.Max(perAdapter.GetValueOrDefault(adapterKey), total);
        }
        return perAdapter.Values.Max().ClampedPercent();
    }
}

/// <summary>PDH wildcard query over <c>\GPU Engine(*)\Utilization Percentage</c>.</summary>
internal sealed class PdhGpuEngineCounterSource : IGpuEngineCounterSource
{
    private const string CounterPath = @"\GPU Engine(*)\Utilization Percentage";
    private const uint PdhFmtDouble = 0x00000200;
    private const uint PdhMoreData = 0x800007D2;
    private const uint CStatusValidData = 0x0;
    private const uint CStatusNewData = 0x1;

    private IntPtr _query;
    private IntPtr _counter;

    public bool Open()
    {
        if (_query != IntPtr.Zero) return true;
        if (PdhOpenQueryW(null, IntPtr.Zero, out IntPtr query) != 0) return false;
        if (PdhAddEnglishCounterW(query, CounterPath, IntPtr.Zero, out IntPtr counter) != 0
            || PdhCollectQueryData(query) != 0)
        {
            PdhCloseQuery(query);
            return false;
        }
        _query = query;
        _counter = counter;
        return true;
    }

    public IReadOnlyList<GpuEngineSample>? Read()
    {
        if (_query == IntPtr.Zero) return null;
        if (PdhCollectQueryData(_query) != 0) return null;

        for (int attempt = 0; attempt < 3; attempt++)
        {
            uint size = 0, count = 0;
            if (PdhGetFormattedCounterArrayW(_counter, PdhFmtDouble, ref size, ref count, IntPtr.Zero) != PdhMoreData) return null;

            IntPtr buffer = Marshal.AllocHGlobal((int)size);
            try
            {
                uint status = PdhGetFormattedCounterArrayW(_counter, PdhFmtDouble, ref size, ref count, buffer);
                if (status == PdhMoreData) continue; // instance list grew between the two calls
                if (status != 0) return null;

                var result = new List<GpuEngineSample>((int)count);
                int stride = Marshal.SizeOf<Item>();
                for (int i = 0; i < count; i++)
                {
                    Item item = Marshal.PtrToStructure<Item>(buffer + i * stride);
                    if (item.Status != CStatusValidData && item.Status != CStatusNewData) continue;
                    string? name = Marshal.PtrToStringUni(item.Name);
                    if (name != null) result.Add(new GpuEngineSample(name, item.Value));
                }
                return result;
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }
        return null;
    }

    public void Close()
    {
        if (_query == IntPtr.Zero) return;
        PdhCloseQuery(_query);
        _query = IntPtr.Zero;
        _counter = IntPtr.Zero;
    }

    public void Dispose() => Close();

    [StructLayout(LayoutKind.Sequential)]
    private struct Item
    {
        public IntPtr Name;
        public uint Status;
        public double Value;
    }

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhOpenQueryW(string? dataSource, IntPtr userData, out IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhAddEnglishCounterW(IntPtr query, string fullCounterPath, IntPtr userData, out IntPtr counter);

    [DllImport("pdh.dll")]
    private static extern uint PdhCollectQueryData(IntPtr query);

    [DllImport("pdh.dll")]
    private static extern uint PdhGetFormattedCounterArrayW(IntPtr counter, uint format, ref uint bufferSize, ref uint itemCount, IntPtr buffer);

    [DllImport("pdh.dll")]
    private static extern uint PdhCloseQuery(IntPtr query);
}
