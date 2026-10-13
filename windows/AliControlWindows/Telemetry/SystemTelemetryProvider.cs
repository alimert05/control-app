namespace AliControlWindows.Telemetry;

/// <summary>
/// The real Windows telemetry source: CPU from <c>GetSystemTimes</c>, memory
/// from <c>GlobalMemoryStatusEx</c>, GPU from the PDH <c>GPU Engine</c> counters,
/// and an honest <c>null</c> for everything this PC cannot measure responsibly.
///
/// <para><b>Why temperatures are not reported.</b> Windows has no public,
/// user-mode, vendor-neutral API for CPU or GPU temperature. What exists is
/// either (a) the ACPI "Thermal Zone Information" counters, which many machines
/// (including the development PC) do not populate at all and which are not CPU
/// package temperatures where they do exist, (b) WMI <c>MSAcpi_ThermalZoneTemperature</c>,
/// which needs administrator rights and has the same accuracy problem, (c)
/// kernel-driver based libraries (LibreHardwareMonitor / WinRing0 / PawnIO),
/// which require elevation and a signed driver, or (d) vendor SDKs (NVML,
/// ADL, igcl), which only cover one vendor each. All are excluded by the
/// project rules (no admin, no drivers, no fragile vendor hacks), so
/// <c>telemetry.temperature.*</c> capabilities are not advertised and the
/// tablet shows <c>--</c>. Vendor adapters belong to TASK-013.</para>
/// </summary>
public sealed class SystemTelemetryProvider : ITelemetryProvider
{
    private readonly CpuUsageSampler _cpu;
    private readonly MemoryUsageSampler _memory;
    private readonly GpuUsageSampler? _gpu;

    public SystemTelemetryProvider(CpuUsageSampler cpu, MemoryUsageSampler memory, GpuUsageSampler? gpu)
    {
        _cpu = cpu;
        _memory = memory;
        _gpu = gpu;
    }

    public static SystemTelemetryProvider CreateDefault(GpuUsageSampler? gpu) =>
        new(new CpuUsageSampler(), new MemoryUsageSampler(), gpu);

    public SystemMetrics Sample() => new(
        CpuPercent: _cpu.Sample(),
        MemoryPercent: _memory.Sample(),
        GpuPercent: _gpu?.Sample(),
        CpuTemperatureCelsius: null,
        GpuTemperatureCelsius: null);

    /// <summary>Drops baselines (and closes the GPU counter) so the next sample measures a fresh interval.</summary>
    public void Reset()
    {
        _cpu.Reset();
        _gpu?.Reset();
    }
}
