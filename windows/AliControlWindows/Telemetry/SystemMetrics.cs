namespace AliControlWindows.Telemetry;

/// <summary>
/// A single snapshot of desktop system telemetry.
///
/// Every field is optional on purpose: "unavailable" is a first-class, honest
/// value. A metric this machine cannot measure reliably — without a kernel
/// driver, administrator rights or vendor-specific hacks — stays <c>null</c>
/// all the way to the tablet, where it renders as <c>--</c>. Nothing in this
/// pipeline ever invents a number.
///
/// Temperatures exist in the model (and on the wire) but are never populated by
/// this agent: see docs/WINDOWS_MIGRATION.md for why.
/// </summary>
public sealed record SystemMetrics(
    double? CpuPercent = null,
    double? MemoryPercent = null,
    double? GpuPercent = null,
    double? CpuTemperatureCelsius = null,
    double? GpuTemperatureCelsius = null)
{
    /// <summary>Whether this snapshot carries anything worth sending at all.</summary>
    public bool HasAnyValue =>
        CpuPercent != null || MemoryPercent != null || GpuPercent != null
        || CpuTemperatureCelsius != null || GpuTemperatureCelsius != null;
}

/// <summary>Produces <see cref="SystemMetrics"/>. Abstracted so the publisher and its tests never touch real counters.</summary>
public interface ITelemetryProvider
{
    SystemMetrics Sample();

    /// <summary>Drops sampling baselines so the next sample starts a fresh interval.</summary>
    void Reset();
}

internal static class PercentExtensions
{
    /// <summary>Keeps a computed percentage inside 0-100 and never lets NaN/infinity escape.</summary>
    public static double? ClampedPercent(this double value) =>
        double.IsFinite(value) ? Math.Min(100d, Math.Max(0d, value)) : null;
}
