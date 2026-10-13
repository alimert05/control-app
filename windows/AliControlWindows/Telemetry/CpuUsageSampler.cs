using System.Runtime.InteropServices;

namespace AliControlWindows.Telemetry;

/// <summary>
/// Cumulative CPU time counters (100 ns units) as reported by the kernel.
/// <c>GetSystemTimes</c> reports kernel time *including* idle time, so busy
/// time is <c>kernel - idle + user</c>.
/// </summary>
public readonly record struct CpuTicks(ulong Idle, ulong Kernel, ulong User)
{
    public ulong Busy => (Kernel >= Idle ? Kernel - Idle : 0) + User;
    public ulong Total => Kernel + User;
}

public interface ICpuTicksSource
{
    /// <summary>Null if the call fails.</summary>
    CpuTicks? Read();
}

/// <summary>One native call (<c>GetSystemTimes</c>) — no subprocess, no performance-counter machinery.</summary>
internal sealed class NativeCpuTicksSource : ICpuTicksSource
{
    public CpuTicks? Read() =>
        GetSystemTimes(out long idle, out long kernel, out long user)
            ? new CpuTicks((ulong)idle, (ulong)kernel, (ulong)user)
            : null;

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemTimes(out long idleTime, out long kernelTime, out long userTime);
}

/// <summary>
/// Whole-machine CPU load: the busy share of CPU time elapsed between two
/// readings, normalised across all logical processors. CPU usage is inherently
/// a delta, so the very first sample after (re)starting has no baseline and
/// reports nothing.
/// </summary>
public sealed class CpuUsageSampler
{
    private readonly ICpuTicksSource _source;
    private CpuTicks? _previous;

    public CpuUsageSampler() : this(new NativeCpuTicksSource()) { }

    public CpuUsageSampler(ICpuTicksSource source) => _source = source;

    /// <summary>Null on the first call (no baseline yet) and if the kernel call fails.</summary>
    public double? Sample()
    {
        CpuTicks? current = _source.Read();
        if (current == null) return null;
        CpuTicks? previous = _previous;
        _previous = current;
        return previous == null ? null : UsagePercent(previous.Value, current.Value);
    }

    /// <summary>Forgets the baseline so the next sample starts a fresh interval.</summary>
    public void Reset() => _previous = null;

    /// <summary>
    /// Pure: busy share of the interval between two readings. Null when no time
    /// elapsed (dividing would give NaN) or when a counter went backwards
    /// (treated as a discontinuity, never a negative percentage).
    /// </summary>
    public static double? UsagePercent(CpuTicks previous, CpuTicks current)
    {
        if (current.Total < previous.Total || current.Busy < previous.Busy) return null;
        ulong totalDelta = current.Total - previous.Total;
        if (totalDelta == 0) return null;
        ulong busyDelta = current.Busy - previous.Busy;
        return ((double)busyDelta / totalDelta * 100d).ClampedPercent();
    }
}
