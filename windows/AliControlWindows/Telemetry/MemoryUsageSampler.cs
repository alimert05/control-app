using System.Runtime.InteropServices;

namespace AliControlWindows.Telemetry;

public interface IMemoryStatusSource
{
    /// <summary>(total physical bytes, available physical bytes), or null if the call fails.</summary>
    (ulong Total, ulong Available)? Read();
}

/// <summary><c>GlobalMemoryStatusEx</c> — one native call, no subprocess.</summary>
internal sealed class NativeMemoryStatusSource : IMemoryStatusSource
{
    public (ulong Total, ulong Available)? Read()
    {
        var status = new MemoryStatusEx { dwLength = (uint)Marshal.SizeOf<MemoryStatusEx>() };
        return GlobalMemoryStatusEx(ref status) ? (status.ullTotalPhys, status.ullAvailPhys) : null;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryStatusEx
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MemoryStatusEx buffer);
}

/// <summary>
/// Physical memory usage.
///
/// <para><b>What "used" means:</b> <c>used = total physical − available physical</c>,
/// the same figure Task Manager shows as "In use". <i>Available</i> is the
/// free, zeroed <i>and standby</i> (cached, instantly reclaimable) pages, so
/// file cache is deliberately not counted as used — otherwise a healthy
/// machine would sit near 100%.</para>
///
/// <para>This is a <b>usage</b> figure, not commit charge or memory pressure.</para>
/// </summary>
public sealed class MemoryUsageSampler
{
    private readonly IMemoryStatusSource _source;

    public MemoryUsageSampler() : this(new NativeMemoryStatusSource()) { }

    public MemoryUsageSampler(IMemoryStatusSource source) => _source = source;

    public double? Sample()
    {
        var status = _source.Read();
        return status == null ? null : UsagePercent(status.Value.Total, status.Value.Available);
    }

    /// <summary>Pure: the documented used/total calculation.</summary>
    public static double? UsagePercent(ulong totalBytes, ulong availableBytes)
    {
        if (totalBytes == 0) return null;
        // Two counters read at slightly different moments can disagree; never underflow.
        ulong used = totalBytes > availableBytes ? totalBytes - availableBytes : 0;
        return ((double)used / totalBytes * 100d).ClampedPercent();
    }
}
