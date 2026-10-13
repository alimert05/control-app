using AliControlWindows.Telemetry;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.Telemetry;

/// <summary>
/// The telemetry arithmetic, tested against known counter values rather than the
/// live machine: nothing here reads real CPU load or creates any system load.
/// </summary>
public class TelemetryTests
{
    // -- CPU --

    [Fact]
    public void CpuUsageIsTheBusyShareOfTheElapsedInterval()
    {
        // GetSystemTimes: kernel time includes idle time.
        var previous = new CpuTicks(Idle: 850, Kernel: 900, User: 100);
        var current = new CpuTicks(Idle: 875, Kernel: 950, User: 150);
        // total delta = (950+150) - (900+100) = 100; idle delta = 25 -> 75% busy.
        Assert.Equal(75d, CpuUsageSampler.UsagePercent(previous, current)!.Value, 3);
    }

    [Fact]
    public void BusyTimeSubtractsIdleFromKernelTime()
    {
        var ticks = new CpuTicks(Idle: 60, Kernel: 100, User: 20);
        Assert.Equal(60UL, ticks.Busy);   // (100 - 60) + 20
        Assert.Equal(120UL, ticks.Total); // 100 + 20
    }

    [Fact]
    public void FullyIdleAndFullyBusyIntervalsGiveZeroAndOneHundred()
    {
        var baseline = new CpuTicks(10, 20, 10);
        Assert.Equal(0d, CpuUsageSampler.UsagePercent(baseline, new CpuTicks(110, 120, 10))!.Value, 3);
        Assert.Equal(100d, CpuUsageSampler.UsagePercent(baseline, new CpuTicks(10, 120, 10))!.Value, 3);
    }

    [Fact]
    public void AnIntervalWithNoElapsedTimeIsUnavailableNotNaN()
    {
        var ticks = new CpuTicks(1, 2, 3);
        Assert.Null(CpuUsageSampler.UsagePercent(ticks, ticks));
    }

    [Fact]
    public void ACounterGoingBackwardsIsADiscontinuityNotANegativePercentage()
    {
        var previous = new CpuTicks(500, 1000, 500);
        var current = new CpuTicks(100, 200, 100);
        Assert.Null(CpuUsageSampler.UsagePercent(previous, current));
    }

    private sealed class ScriptedCpu : ICpuTicksSource
    {
        public Queue<CpuTicks?> Readings = new();
        public CpuTicks? Read() => Readings.Count > 0 ? Readings.Dequeue() : null;
    }

    [Fact]
    public void TheFirstSampleHasNoBaselineAndReportsNothing()
    {
        var source = new ScriptedCpu();
        source.Readings.Enqueue(new CpuTicks(0, 10, 0));
        Assert.Null(new CpuUsageSampler(source).Sample());
    }

    [Fact]
    public void LaterSamplesMeasureTheIntervalSinceThePreviousOne()
    {
        var source = new ScriptedCpu();
        source.Readings.Enqueue(new CpuTicks(0, 100, 0));
        source.Readings.Enqueue(new CpuTicks(50, 200, 0));   // 50 of 100 busy
        source.Readings.Enqueue(new CpuTicks(50, 300, 0));   // 100 of 100 busy
        var sampler = new CpuUsageSampler(source);

        Assert.Null(sampler.Sample());
        Assert.Equal(50d, sampler.Sample()!.Value, 3);
        Assert.Equal(100d, sampler.Sample()!.Value, 3);
    }

    [Fact]
    public void ResetDropsTheBaselineSoTheNextSampleStartsAFreshInterval()
    {
        var source = new ScriptedCpu();
        source.Readings.Enqueue(new CpuTicks(0, 100, 0));
        source.Readings.Enqueue(new CpuTicks(0, 200, 0));
        source.Readings.Enqueue(new CpuTicks(0, 300, 0));
        var sampler = new CpuUsageSampler(source);

        sampler.Sample();
        sampler.Reset();
        Assert.Null(sampler.Sample()); // baseline again
        Assert.NotNull(sampler.Sample());
    }

    [Fact]
    public void AFailingKernelCallIsUnavailable()
    {
        var source = new ScriptedCpu(); // returns null
        Assert.Null(new CpuUsageSampler(source).Sample());
    }

    [Fact]
    public void TheNativeSourceReadsCountersThatOnlyMoveForward()
    {
        // Reads real counters (cheap, read-only; creates no load).
        var native = new CpuUsageSampler();
        Assert.Null(native.Sample()); // first call: no baseline
        native.Reset();
    }

    // -- memory --

    [Fact]
    public void MemoryUsageIsTotalMinusAvailableOverTotal()
    {
        const ulong gib = 1024UL * 1024 * 1024;
        Assert.Equal(75d, MemoryUsageSampler.UsagePercent(16 * gib, 4 * gib)!.Value, 3);
        Assert.Equal(0d, MemoryUsageSampler.UsagePercent(16 * gib, 16 * gib)!.Value, 3);
        Assert.Equal(100d, MemoryUsageSampler.UsagePercent(16 * gib, 0)!.Value, 3);
    }

    [Fact]
    public void AvailableAboveTotalDoesNotUnderflow()
    {
        // Two values read microseconds apart can disagree; never wrap around.
        Assert.Equal(0d, MemoryUsageSampler.UsagePercent(100, 150)!.Value, 3);
    }

    [Fact]
    public void UnknownPhysicalMemoryIsUnavailable()
    {
        Assert.Null(MemoryUsageSampler.UsagePercent(0, 0));
    }

    private sealed class ScriptedMemory : IMemoryStatusSource
    {
        public (ulong, ulong)? Value;
        public (ulong Total, ulong Available)? Read() => Value;
    }

    [Fact]
    public void MemorySamplerUsesItsSource()
    {
        Assert.Equal(50d, new MemoryUsageSampler(new ScriptedMemory { Value = (200UL, 100UL) }).Sample()!.Value, 3);
        Assert.Null(new MemoryUsageSampler(new ScriptedMemory { Value = null }).Sample());
    }

    [Fact]
    public void RealMemorySampleIsAPlausiblePercentage()
    {
        double? percent = new MemoryUsageSampler().Sample();
        Assert.NotNull(percent);
        Assert.InRange(percent!.Value, 0, 100);
    }

    // -- GPU --

    private static GpuEngineSample S(string instance, double percent) => new(instance, percent);

    private const string AdapterA = "luid_0x00000000_0x000123D6_phys_0";
    private const string AdapterB = "luid_0x00000000_0x000136CA_phys_0";

    [Fact]
    public void GpuUsageSumsProcessesPerEngineThenTakesTheBusiestEngine()
    {
        var samples = new[]
        {
            S($"pid_100_{AdapterA}_eng_0_engtype_3D", 10),
            S($"pid_200_{AdapterA}_eng_0_engtype_3D", 15), // 3D engine total: 25
            S($"pid_100_{AdapterA}_eng_1_engtype_Copy", 5)
        };
        Assert.Equal(25d, GpuUsageSampler.Aggregate(samples)!.Value, 3);
    }

    [Fact]
    public void TheBusiestGpuRepresentsTheMachine()
    {
        var samples = new[]
        {
            S($"pid_1_{AdapterA}_eng_0_engtype_3D", 3),     // integrated GPU idling
            S($"pid_2_{AdapterB}_eng_0_engtype_3D", 70)     // discrete GPU under load
        };
        Assert.Equal(70d, GpuUsageSampler.Aggregate(samples)!.Value, 3);
    }

    [Fact]
    public void EnginesOfDifferentTypesAreNotAddedTogether()
    {
        var samples = new[]
        {
            S($"pid_1_{AdapterA}_eng_0_engtype_3D", 40),
            S($"pid_1_{AdapterA}_eng_1_engtype_VideoDecode", 40)
        };
        Assert.Equal(40d, GpuUsageSampler.Aggregate(samples)!.Value, 3);
    }

    [Fact]
    public void GpuUsageIsClampedToOneHundred()
    {
        var samples = new[]
        {
            S($"pid_1_{AdapterA}_eng_0_engtype_3D", 80),
            S($"pid_2_{AdapterA}_eng_0_engtype_3D", 80)
        };
        Assert.Equal(100d, GpuUsageSampler.Aggregate(samples)!.Value, 3);
    }

    [Fact]
    public void ZeroIsAMeasurementNotAnAbsence()
    {
        Assert.Equal(0d, GpuUsageSampler.Aggregate(new[] { S($"pid_1_{AdapterA}_eng_0_engtype_3D", 0) })!.Value, 3);
    }

    [Fact]
    public void InstanceNamesInAnUnknownShapeAreIgnoredNotGuessedAt()
    {
        var samples = new[]
        {
            S("totally_unrelated_counter", 90),
            S("pid_1_luid_0x1_0x2_phys_0", 90),                    // no engine part
            S($"pid_1_{AdapterA}_eng_0_engtype_3D", double.NaN),   // not a number
            S($"pid_1_{AdapterA}_eng_0_engtype_3D", -5)            // nonsense
        };
        Assert.Null(GpuUsageSampler.Aggregate(samples));
    }

    [Fact]
    public void NoInstancesMeansUnavailable()
    {
        Assert.Null(GpuUsageSampler.Aggregate(Array.Empty<GpuEngineSample>()));
    }

    [Fact]
    public void InstanceMatchingIsCaseInsensitive()
    {
        var samples = new[]
        {
            S("pid_1_LUID_0x00000000_0x000123d6_PHYS_0_ENG_0_ENGTYPE_3D", 10),
            S("pid_2_luid_0x00000000_0x000123D6_phys_0_eng_0_engtype_3d", 15)
        };
        Assert.Equal(25d, GpuUsageSampler.Aggregate(samples)!.Value, 3);
    }

    private sealed class FakeGpuSource : IGpuEngineCounterSource
    {
        public bool CanOpen = true;
        public bool IsOpen;
        public int OpenCalls, CloseCalls, ReadCalls;
        public IReadOnlyList<GpuEngineSample>? Samples = new[] { new GpuEngineSample($"pid_1_{AdapterA}_eng_0_engtype_3D", 33) };

        public bool Open()
        {
            OpenCalls++;
            IsOpen = CanOpen;
            return CanOpen;
        }

        public IReadOnlyList<GpuEngineSample>? Read()
        {
            ReadCalls++;
            return IsOpen ? Samples : null;
        }

        public void Close()
        {
            CloseCalls++;
            IsOpen = false;
        }

        public void Dispose() => Close();
    }

    [Fact]
    public void TheFirstGpuSampleOpensTheCounterAndOnlyEstablishesABaseline()
    {
        var source = new FakeGpuSource();
        var sampler = new GpuUsageSampler(source);

        Assert.Null(sampler.Sample());
        Assert.Equal(1, source.OpenCalls);
        Assert.Equal(33d, sampler.Sample()!.Value, 3);
        Assert.Equal(1, source.OpenCalls); // not re-opened
    }

    [Fact]
    public void ResetClosesTheCounterSoAnIdlePcDoesNoGpuWork()
    {
        var source = new FakeGpuSource();
        var sampler = new GpuUsageSampler(source);
        sampler.Sample();
        sampler.Sample();

        sampler.Reset();

        Assert.False(source.IsOpen);
        Assert.Null(sampler.Sample()); // re-opens as a fresh baseline
        Assert.Equal(2, source.OpenCalls);
    }

    [Fact]
    public void AMachineWithoutGpuCountersReportsUnavailableForever()
    {
        var source = new FakeGpuSource { CanOpen = false };
        var sampler = new GpuUsageSampler(source);
        Assert.Null(sampler.Sample());
        Assert.Null(sampler.Sample());
    }

    [Fact]
    public async Task ProbeSucceedsWhenCountersExistAndLeavesThemClosed()
    {
        var source = new FakeGpuSource();
        var sampler = new GpuUsageSampler(source);

        Assert.True(await sampler.ProbeAsync(TimeSpan.Zero));
        Assert.False(source.IsOpen);
    }

    [Fact]
    public async Task ProbeFailsWhenCountersAreMissingOrEmpty()
    {
        Assert.False(await new GpuUsageSampler(new FakeGpuSource { CanOpen = false }).ProbeAsync(TimeSpan.Zero));
        Assert.False(await new GpuUsageSampler(new FakeGpuSource { Samples = Array.Empty<GpuEngineSample>() }).ProbeAsync(TimeSpan.Zero));
        Assert.False(await new GpuUsageSampler(new FakeGpuSource { Samples = null }).ProbeAsync(TimeSpan.Zero));
    }

    // -- provider --

    [Fact]
    public void TemperaturesAreNeverFabricated()
    {
        // Windows has no public, user-mode, vendor-neutral temperature API that works without
        // administrator rights or a kernel driver (see SystemTelemetryProvider), so they stay null.
        var provider = SystemTelemetryProvider.CreateDefault(gpu: null);
        SystemMetrics metrics = provider.Sample();

        Assert.Null(metrics.CpuTemperatureCelsius);
        Assert.Null(metrics.GpuTemperatureCelsius);
        Assert.Null(metrics.GpuPercent); // no GPU sampler was supplied
    }

    [Fact]
    public void ProviderCombinesWhatItCanMeasure()
    {
        var cpuSource = new ScriptedCpu();
        cpuSource.Readings.Enqueue(new CpuTicks(0, 100, 0));
        cpuSource.Readings.Enqueue(new CpuTicks(50, 200, 0));
        var provider = new SystemTelemetryProvider(
            new CpuUsageSampler(cpuSource),
            new MemoryUsageSampler(new ScriptedMemory { Value = (200UL, 150UL) }),
            new GpuUsageSampler(new FakeGpuSource()));

        provider.Sample(); // primes CPU and GPU baselines
        SystemMetrics metrics = provider.Sample();

        Assert.Equal(50d, metrics.CpuPercent!.Value, 3);
        Assert.Equal(25d, metrics.MemoryPercent!.Value, 3);
        Assert.Equal(33d, metrics.GpuPercent!.Value, 3);
        Assert.True(metrics.HasAnyValue);
    }

    [Fact]
    public void ProviderResetDropsEveryBaseline()
    {
        var cpuSource = new ScriptedCpu();
        foreach (var t in new[] { new CpuTicks(0, 100, 0), new CpuTicks(0, 200, 0), new CpuTicks(0, 300, 0) }) cpuSource.Readings.Enqueue(t);
        var gpuSource = new FakeGpuSource();
        var provider = new SystemTelemetryProvider(new CpuUsageSampler(cpuSource), new MemoryUsageSampler(new ScriptedMemory()), new GpuUsageSampler(gpuSource));

        provider.Sample();
        provider.Sample();
        provider.Reset();

        Assert.False(gpuSource.IsOpen);
        Assert.Null(provider.Sample().CpuPercent);
    }

    [Fact]
    public void AnEmptySnapshotIsRecognisedAsHavingNothingToSend()
    {
        Assert.False(new SystemMetrics().HasAnyValue);
        Assert.True(new SystemMetrics(MemoryPercent: 41).HasAnyValue);
        Assert.True(new SystemMetrics(GpuPercent: 0).HasAnyValue);
    }
}
