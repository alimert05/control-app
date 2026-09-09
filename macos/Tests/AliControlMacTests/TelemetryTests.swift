import XCTest
@testable import AliControlMac

/// The telemetry arithmetic, tested against known counter values rather than
/// against the live machine — nothing here reads real CPU load, allocates
/// memory to move a number, or creates any system load.
final class TelemetryTests: XCTestCase {

    // MARK: - CPU

    func testCpuUsageIsTheBusyShareOfTheElapsedInterval() {
        let previous = CpuTicks(user: 100, system: 50, idle: 850, nice: 0)
        // 75 busy ticks and 25 idle ticks elapsed -> 75%.
        let current = CpuTicks(user: 150, system: 75, idle: 875, nice: 0)

        XCTAssertEqual(CpuUsageSampler.usagePercent(previous: previous, current: current)!, 75, accuracy: 0.001)
    }

    func testNiceTicksCountAsBusy() {
        let previous = CpuTicks(user: 0, system: 0, idle: 0, nice: 0)
        let current = CpuTicks(user: 0, system: 0, idle: 50, nice: 50)

        // Low-priority work is still work: ignoring `nice` would report 0%.
        XCTAssertEqual(CpuUsageSampler.usagePercent(previous: previous, current: current)!, 50, accuracy: 0.001)
    }

    func testFullyIdleAndFullyBusyIntervalsProduceZeroAndOneHundred() {
        let base = CpuTicks(user: 10, system: 10, idle: 10, nice: 10)

        let idleOnly = CpuTicks(user: 10, system: 10, idle: 110, nice: 10)
        let busyOnly = CpuTicks(user: 110, system: 10, idle: 10, nice: 10)

        XCTAssertEqual(CpuUsageSampler.usagePercent(previous: base, current: idleOnly)!, 0, accuracy: 0.001)
        XCTAssertEqual(CpuUsageSampler.usagePercent(previous: base, current: busyOnly)!, 100, accuracy: 0.001)
    }

    func testAnIntervalWithNoElapsedTicksIsUnavailableRatherThanNaN() {
        let ticks = CpuTicks(user: 1, system: 2, idle: 3, nice: 4)

        // Two identical readings would divide by zero — that must surface as
        // "no value yet", never as NaN reaching a widget.
        XCTAssertNil(CpuUsageSampler.usagePercent(previous: ticks, current: ticks))
    }

    func testCounterDiscontinuityIsRejectedInsteadOfProducingANegativePercentage() {
        let previous = CpuTicks(user: 500, system: 500, idle: 500, nice: 0)
        let wentBackwards = CpuTicks(user: 100, system: 100, idle: 100, nice: 0)

        XCTAssertNil(CpuUsageSampler.usagePercent(previous: previous, current: wentBackwards))
    }

    func testFirstSampleHasNoBaselineAndReportsNothing() {
        // A real sampler against the real host: the first call must be nil
        // because usage is a delta. This reads counters but creates no load.
        let sampler = CpuUsageSampler()

        XCTAssertNil(sampler.sample())
    }

    func testResetDropsTheBaselineSoTheNextSampleStartsAFreshInterval() {
        let sampler = CpuUsageSampler()
        _ = sampler.sample()

        sampler.reset()

        XCTAssertNil(sampler.sample(), "after reset the next sample is a baseline again, not a delta")
    }

    // MARK: - Memory

    func testMemoryUsageIsAppPlusWiredPlusCompressedOverPhysical() {
        // 4 GiB machine, 4 KiB pages -> 1,048,576 pages total.
        // app (500k internal - 100k purgeable) + 100k wired + 24,288 compressed
        // = 524,288 pages = exactly half of physical memory.
        let percent = MemoryUsageSampler.usagePercent(
            internalPages: 500_000,
            purgeablePages: 100_000,
            wiredPages: 100_000,
            compressedPages: 24_288,
            pageSizeBytes: 4096,
            physicalMemoryBytes: 4 * 1024 * 1024 * 1024
        )

        XCTAssertEqual(percent!, 50, accuracy: 0.001)
    }

    func testPurgeablePagesAreExcludedFromUsedMemory() {
        let withPurgeable = MemoryUsageSampler.usagePercent(
            internalPages: 200_000, purgeablePages: 100_000, wiredPages: 0, compressedPages: 0,
            pageSizeBytes: 4096, physicalMemoryBytes: 4 * 1024 * 1024 * 1024
        )
        let withoutPurgeable = MemoryUsageSampler.usagePercent(
            internalPages: 100_000, purgeablePages: 0, wiredPages: 0, compressedPages: 0,
            pageSizeBytes: 4096, physicalMemoryBytes: 4 * 1024 * 1024 * 1024
        )

        // Reclaimable pages must not inflate the figure.
        XCTAssertEqual(withPurgeable!, withoutPurgeable!, accuracy: 0.0001)
    }

    func testMorePurgeableThanInternalPagesDoesNotUnderflow() {
        // Two counters sampled microseconds apart can disagree; the
        // subtraction must not wrap around into a colossal page count.
        let percent = MemoryUsageSampler.usagePercent(
            internalPages: 10, purgeablePages: 1_000, wiredPages: 0, compressedPages: 0,
            pageSizeBytes: 4096, physicalMemoryBytes: 4 * 1024 * 1024 * 1024
        )

        XCTAssertEqual(percent!, 0, accuracy: 0.0001)
    }

    func testMemoryUsageIsClampedIntoZeroToOneHundred() {
        let percent = MemoryUsageSampler.usagePercent(
            internalPages: 100_000_000, purgeablePages: 0, wiredPages: 0, compressedPages: 0,
            pageSizeBytes: 4096, physicalMemoryBytes: 1024
        )

        XCTAssertEqual(percent!, 100, accuracy: 0.0001)
    }

    func testMemoryUsageIsUnavailableWhenPhysicalMemoryIsUnknown() {
        XCTAssertNil(
            MemoryUsageSampler.usagePercent(
                internalPages: 1, purgeablePages: 0, wiredPages: 0, compressedPages: 0,
                pageSizeBytes: 4096, physicalMemoryBytes: 0
            )
        )
    }

    func testRealMemorySampleProducesAPlausiblePercentage() {
        // Reads real kernel statistics (cheap, read-only) and asserts only
        // that the result is in range — never a specific value.
        let percent = MemoryUsageSampler().sample()

        let value = try? XCTUnwrap(percent)
        XCTAssertNotNil(value)
        XCTAssertTrue((0...100).contains(value!), "memory usage \(value!) is outside 0-100")
    }

    // MARK: - Provider

    func testGpuAndTemperaturesAreReportedUnavailableRatherThanFabricated() {
        // macOS has no public GPU-utilization or thermal API that doesn't
        // need root or private symbols, so these must stay nil — the tablet
        // shows `--`. See SystemTelemetryProvider's documentation.
        let metrics = SystemTelemetryProvider().sample()

        XCTAssertNil(metrics.gpuPercent)
        XCTAssertNil(metrics.cpuTemperatureCelsius)
        XCTAssertNil(metrics.gpuTemperatureCelsius)
    }

    func testAnEmptySnapshotIsRecognizedAsHavingNothingToSend() {
        XCTAssertFalse(SystemMetrics().hasAnyValue)
        XCTAssertTrue(SystemMetrics(memoryPercent: 41).hasAnyValue)
    }
}
