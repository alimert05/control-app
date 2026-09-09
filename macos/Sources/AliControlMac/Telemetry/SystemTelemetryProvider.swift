import Foundation

/// The real macOS telemetry source: CPU and memory from Mach kernel
/// statistics, and honest `nil` for everything this Mac cannot measure
/// responsibly.
///
/// ## Why GPU usage is unavailable
///
/// macOS exposes no public API for GPU utilization. The routes that exist are
/// all disqualified by TASK-009's own constraints:
///
/// - `powermetrics` requires root and spawns a process per sample;
/// - `IOAccelerator`/`AGXAccelerator` "Device Utilization %" comes from
///   undocumented IORegistry keys that Apple has already renamed across
///   Apple Silicon generations — a fragile private hack kept alive purely to
///   make a number appear;
/// - scraping Activity Monitor's UI is out of the question.
///
/// So GPU stays `nil` and the widget keeps showing `--`. `SystemMetrics`
/// still carries the field, so a Windows agent (where GPU counters *are*
/// publicly available) can fill it in with no protocol or UI change.
///
/// ## Why temperatures are unavailable
///
/// Same reasoning. Intel Macs exposed SMC keys like `TC0P`; Apple Silicon
/// moved thermal sensors behind `IOHIDEventSystemClient`, which is private
/// API, and the key layout differs per chip generation. There is no
/// maintainable public mechanism, so `cpuTemperatureCelsius`/
/// `gpuTemperatureCelsius` stay `nil` rather than being faked or scraped.
final class SystemTelemetryProvider: TelemetryProviding {

    private let cpu: CpuUsageSampler
    private let memory: MemoryUsageSampler

    init(cpu: CpuUsageSampler = CpuUsageSampler(), memory: MemoryUsageSampler = MemoryUsageSampler()) {
        self.cpu = cpu
        self.memory = memory
    }

    func sample() -> SystemMetrics {
        SystemMetrics(
            cpuPercent: cpu.sample(),
            memoryPercent: memory.sample(),
            gpuPercent: nil,
            cpuTemperatureCelsius: nil,
            gpuTemperatureCelsius: nil
        )
    }

    /// Drops the CPU baseline so the next sample measures a fresh interval
    /// rather than averaging across a long gap with no subscriber.
    func reset() {
        cpu.reset()
    }
}
