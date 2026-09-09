import Darwin
import Foundation

/// Cumulative CPU tick counters, as reported by the kernel since boot.
///
/// Kept as a plain value type separate from the Mach call so the actual
/// arithmetic — which is the only part that can be wrong in an interesting
/// way — is unit-testable without a real host.
struct CpuTicks: Equatable {
    let user: UInt64
    let system: UInt64
    let idle: UInt64
    let nice: UInt64

    /// Everything that wasn't idle. `nice` is separate from `user` in Mach's
    /// accounting, so it has to be added explicitly or low-priority work
    /// would be invisible.
    var busy: UInt64 { user &+ system &+ nice }
    var total: UInt64 { busy &+ idle }
}

/// Reads whole-machine CPU load through `host_statistics(HOST_CPU_LOAD_INFO)`
/// — a native Mach call, not a subprocess. Nothing here spawns `top`, `ps`
/// or `powermetrics`; the whole sample is one syscall against counters the
/// kernel already maintains.
///
/// CPU utilization is inherently a *delta* between two readings, so the very
/// first `sample()` after startup returns nil rather than guessing. Every
/// later sample is the busy-tick share of the interval since the previous one.
final class CpuUsageSampler {

    private var previous: CpuTicks?

    /// Nil on the first call (no baseline yet) and if the kernel call fails.
    func sample() -> Double? {
        guard let current = Self.readTicks() else { return nil }
        defer { previous = current }
        guard let previous else { return nil }
        return Self.usagePercent(previous: previous, current: current)
    }

    /// Forgets the baseline, so the next sample starts a fresh interval —
    /// used when telemetry stops and restarts (tablet disconnect/reconnect)
    /// so the first value after reconnecting isn't an average over the whole
    /// idle gap.
    func reset() {
        previous = nil
    }

    /// Pure: the busy share of the ticks that elapsed between two readings.
    ///
    /// Returns nil when no ticks elapsed at all (two samples taken too close
    /// together, or counters that didn't move) — dividing by that would be
    /// NaN, and NaN must never reach a widget. Counters are monotonic in
    /// practice; a decrease is treated as a discontinuity rather than a
    /// negative percentage.
    static func usagePercent(previous: CpuTicks, current: CpuTicks) -> Double? {
        guard current.total >= previous.total, current.busy >= previous.busy else { return nil }
        let totalDelta = current.total - previous.total
        guard totalDelta > 0 else { return nil }
        let busyDelta = current.busy - previous.busy
        let percent = (Double(busyDelta) / Double(totalDelta)) * 100
        return percent.clampedPercent()
    }

    private static func readTicks() -> CpuTicks? {
        var info = host_cpu_load_info()
        var count = mach_msg_type_number_t(MemoryLayout<host_cpu_load_info>.stride / MemoryLayout<integer_t>.stride)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, rebound, &count)
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return CpuTicks(
            user: UInt64(info.cpu_ticks.0),
            system: UInt64(info.cpu_ticks.1),
            idle: UInt64(info.cpu_ticks.2),
            nice: UInt64(info.cpu_ticks.3)
        )
    }
}

extension Double {
    /// Keeps a computed percentage inside 0-100 and never lets NaN/infinity
    /// escape toward the wire or a widget.
    func clampedPercent() -> Double? {
        guard isFinite else { return nil }
        return Swift.min(100, Swift.max(0, self))
    }
}
