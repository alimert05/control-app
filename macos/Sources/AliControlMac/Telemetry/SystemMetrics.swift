import Foundation

/// A single snapshot of desktop system telemetry.
///
/// Every field except `timestamp` is optional on purpose: "unavailable" is a
/// first-class, honest value here. TASK-009's rule is that a metric this Mac
/// cannot measure reliably — without root, private APIs, or repeatedly
/// spawning CLI tools — stays `nil` all the way to the tablet, where it
/// renders as `--`. Nothing in this pipeline ever invents a number.
///
/// The optional GPU/temperature fields exist even though macOS leaves them
/// `nil` today (see `SystemTelemetryProvider`) so a future Windows agent, or
/// a Mac with a maintainable sensor source, can populate the same model and
/// the same wire message without either side changing shape.
struct SystemMetrics: Equatable {

    /// 0-100, normalized across all cores. Nil until the sampler has two
    /// samples to diff (CPU usage is inherently a delta).
    let cpuPercent: Double?

    /// 0-100 of physical RAM — see `MemoryUsageSampler` for the exact
    /// definition of "used".
    let memoryPercent: Double?

    let gpuPercent: Double?
    let cpuTemperatureCelsius: Double?
    let gpuTemperatureCelsius: Double?

    let timestamp: Date

    init(
        cpuPercent: Double? = nil,
        memoryPercent: Double? = nil,
        gpuPercent: Double? = nil,
        cpuTemperatureCelsius: Double? = nil,
        gpuTemperatureCelsius: Double? = nil,
        timestamp: Date = Date()
    ) {
        self.cpuPercent = cpuPercent
        self.memoryPercent = memoryPercent
        self.gpuPercent = gpuPercent
        self.cpuTemperatureCelsius = cpuTemperatureCelsius
        self.gpuTemperatureCelsius = gpuTemperatureCelsius
        self.timestamp = timestamp
    }

    /// Whether this snapshot carries anything worth sending at all.
    var hasAnyValue: Bool {
        cpuPercent != nil || memoryPercent != nil || gpuPercent != nil
            || cpuTemperatureCelsius != nil || gpuTemperatureCelsius != nil
    }
}

/// Produces `SystemMetrics`. Abstracted so the publisher and its tests never
/// touch real Mach kernel statistics.
protocol TelemetryProviding {
    func sample() -> SystemMetrics
}
