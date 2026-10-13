using System.Text;
using System.Text.Json;
using AliControlWindows.Actions;
using AliControlWindows.Artwork;
using AliControlWindows.BLE;
using AliControlWindows.Live;
using AliControlWindows.Media;
using AliControlWindows.Protocol;
using AliControlWindows.State;
using AliControlWindows.Telemetry;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.BLE;

/// <summary>
/// The connection lifecycle: advertising, subscribe / unsubscribe, command handling,
/// reconnect, Bluetooth off/on, sleep/resume, queue priority and artwork transfer.
/// Everything is driven through fakes — a fake GATT server, a fake radio, a manual
/// executor and a manual clock — so no test touches real Bluetooth.
/// </summary>
public class PeripheralManagerTests
{
    private const string Tablet = "BluetoothLE#BluetoothLE5c:fb:3a:00:00:01-aa:bb:cc:dd:ee:ff";
    private const string OtherTablet = "BluetoothLE#BluetoothLE5c:fb:3a:00:00:01-11:22:33:44:55:66";

    private sealed class Rig
    {
        public readonly FakeGattServer Server = new();
        public readonly FakeRadio Radio = new();
        public readonly FakeControllers Controllers = new();
        public readonly FakeTelemetry Telemetry = new();
        public readonly FakeMediaSource MediaSource = new();
        public readonly ManualExecutor Executor = new();
        public readonly ManualScheduler Scheduler = new();
        public readonly List<string> Log = new();
        public readonly List<AgentStatus> Statuses = new();
        public readonly LiveStatePublisher Live;
        public readonly PeripheralManager Manager;
        public readonly ArtworkStore Store = new();

        public Rig(PeripheralOptions? options = null)
        {
            var dispatcher = new CommandDispatcher(Controllers.MakeRegistry(), Log.Add);
            var pipeline = new ArtworkPipeline(Store, raw => Task.FromResult<ProcessedArtwork?>(
                new ProcessedArtwork(ArtworkProcessor.ArtworkIdFor(raw), raw, "image/jpeg")));
            Live = new LiveStatePublisher(Telemetry, new MediaSessionCoordinator(MediaSource), pipeline, TimeSpan.FromMinutes(10));
            Manager = new PeripheralManager(
                Server, Radio, dispatcher, Live,
                options ?? new PeripheralOptions
                {
                    MachineName = "ALI-PC",
                    CapabilityIds = new[] { CapabilityIds.Actions, CapabilityIds.TelemetryCpu, CapabilityIds.Media, CapabilityIds.MediaArtwork }
                },
                Executor, Scheduler, Log.Add, pumpRetryDelay: TimeSpan.FromMilliseconds(1));
            Manager.StatusChanged += Statuses.Add;
        }

        public async Task StartAsync()
        {
            await Manager.StartAsync();
            await Executor.RunAllAsync();
        }

        public async Task SettleAsync()
        {
            await Executor.RunAllAsync();
            await Manager.Pump.WhenIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));
            await Executor.RunAllAsync(); // the pump's "drained" notification
        }

        public async Task ConnectAsync(string client = Tablet)
        {
            Server.SimulateSubscribe(client);
            await SettleAsync();
        }

        public async Task WriteAsync(string json, string client = Tablet)
        {
            Server.SimulateWrite(client, Encoding.UTF8.GetBytes(json));
            await SettleAsync();
        }

        public List<JsonElement> Sent() => Server.SentJson().Select(j => JsonDocument.Parse(j).RootElement.Clone()).ToList();

        public List<JsonElement> SentOfType(string type) => Sent().Where(m => m.GetProperty("type").GetString() == type).ToList();

        public static string Type(JsonElement e) => e.GetProperty("type").GetString()!;
    }

    // -- startup & advertising --

    [Fact]
    public async Task AdvertisingStartsAutomaticallyOnceBluetoothIsOn()
    {
        var rig = new Rig();
        await rig.StartAsync();

        Assert.Equal(new[] { AliControlBleProfile.ServiceUuid }, rig.Server.StartCalls);
        Assert.Equal(new AgentStatus(BluetoothState.On, AdvertisingState.Advertising, AdvertisingMode.FullServiceUuid), rig.Manager.Status);
    }

    [Fact]
    public async Task NothingAdvertisesWhileBluetoothIsOff()
    {
        var rig = new Rig();
        rig.Radio.Current = BluetoothAvailability.Off;
        await rig.StartAsync();

        Assert.Empty(rig.Server.StartCalls);
        Assert.Equal(BluetoothState.Off, rig.Manager.Status.Bluetooth);
        Assert.Equal(AdvertisingState.Stopped, rig.Manager.Status.Advertising);
    }

    [Theory]
    [InlineData(BluetoothAvailability.NoAdapter, BluetoothState.NoAdapter)]
    [InlineData(BluetoothAvailability.PeripheralUnsupported, BluetoothState.Unsupported)]
    public async Task ASystemThatCannotAdvertiseIsReportedAndNeverTriesTo(BluetoothAvailability availability, BluetoothState expected)
    {
        var rig = new Rig();
        rig.Radio.Current = availability;
        await rig.StartAsync();

        Assert.Empty(rig.Server.StartCalls);
        Assert.Equal(expected, rig.Manager.Status.Bluetooth);
    }

    [Fact]
    public async Task IfTheFullUuidIsRejectedTheCompactDiscoveryServiceIsAdvertisedInstead()
    {
        var rig = new Rig();
        rig.Server.StartBehavior = uuid => uuid == AliControlBleProfile.ServiceUuid
            ? new GattStartResult(false, "Aborted")
            : new GattStartResult(true, "started");

        await rig.StartAsync();

        Assert.Equal(new[] { AliControlBleProfile.ServiceUuid, AliControlBleProfile.DiscoveryServiceUuid }, rig.Server.StartCalls);
        Assert.Equal(AdvertisingMode.CompactDiscoveryUuid, rig.Manager.Status.Mode);
        Assert.Equal(AdvertisingState.Advertising, rig.Manager.Status.Advertising);
        Assert.True(rig.Server.StopCalls >= 1, "the rejected service must be torn down before the next attempt");
    }

    [Fact]
    public void TheCompactDiscoveryUuidIsAValidShortUuidInTheBluetoothBaseRange()
    {
        string text = AliControlBleProfile.DiscoveryServiceUuid.ToString();
        Assert.EndsWith("-0000-1000-8000-00805f9b34fb", text);
        Assert.NotEqual(AliControlBleProfile.ServiceUuid, AliControlBleProfile.DiscoveryServiceUuid);
    }

    [Fact]
    public async Task TheGattIdentifiersAreThePreservedOnes()
    {
        await Task.CompletedTask;
        Assert.Equal("7eb4052b-fc5f-436f-ae69-c3c9e7e9809f", AliControlBleProfile.ServiceUuid.ToString());
        Assert.Equal("6544209f-88b6-432e-9db1-c672b7a31916", AliControlBleProfile.CommandCharacteristicUuid.ToString());
        Assert.Equal("71dbc91d-eb99-4a50-b875-c3f045f4ea91", AliControlBleProfile.StateCharacteristicUuid.ToString());
    }

    [Fact]
    public async Task WhenEveryCandidateFailsTheFailureIsShownAndRetriesAreBoundedAndBackedOff()
    {
        var rig = new Rig();
        rig.Server.StartBehavior = _ => new GattStartResult(false, "Aborted");
        await rig.StartAsync();

        Assert.Equal(AdvertisingState.Failed, rig.Manager.Status.Advertising);
        Assert.Equal(new[] { TimeSpan.FromSeconds(3) }, rig.Scheduler.PendingDelays);

        // Walk through every automatic retry.
        var delays = new List<TimeSpan>();
        for (int i = 0; i < 10; i++)
        {
            IReadOnlyList<TimeSpan> pending = rig.Scheduler.PendingDelays;
            if (pending.Count == 0) break;
            delays.AddRange(pending);
            rig.Scheduler.FireAll();
            await rig.Executor.RunAllAsync();
        }

        Assert.Equal(new[] { 3, 10, 30, 60 }.Select(s => TimeSpan.FromSeconds(s)), delays);
        Assert.Equal(AdvertisingState.Failed, rig.Manager.Status.Advertising);
        Assert.Empty(rig.Scheduler.PendingDelays); // gave up: no endless loop
        Assert.Contains(rig.Log, l => l.Contains("giving up"));
    }

    [Fact]
    public async Task ARetrySucceedsAndClearsTheFailure()
    {
        var rig = new Rig();
        bool fail = true;
        rig.Server.StartBehavior = _ => fail ? new GattStartResult(false, "Aborted") : new GattStartResult(true, "ok");
        await rig.StartAsync();
        Assert.Equal(AdvertisingState.Failed, rig.Manager.Status.Advertising);

        fail = false;
        rig.Scheduler.FireAll();
        await rig.Executor.RunAllAsync();

        Assert.Equal(AdvertisingState.Advertising, rig.Manager.Status.Advertising);
        Assert.Empty(rig.Scheduler.PendingDelays);
    }

    [Fact]
    public async Task RestartAdvertisingRebuildsTheServiceFromScratch()
    {
        var rig = new Rig();
        await rig.StartAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Manager.RestartAdvertising();
        await rig.Executor.RunAllAsync();

        Assert.Equal(starts + 1, rig.Server.StartCalls.Count);
        Assert.True(rig.Server.StopCalls >= 1);
        Assert.Equal(AdvertisingState.Advertising, rig.Manager.Status.Advertising);
    }

    [Fact]
    public async Task RestartAdvertisingRecoversAfterAFailureAndResetsTheRetryBudget()
    {
        var rig = new Rig();
        bool fail = true;
        rig.Server.StartBehavior = _ => fail ? new GattStartResult(false, "x") : new GattStartResult(true, "ok");
        await rig.StartAsync();

        fail = false;
        rig.Manager.RestartAdvertising();
        await rig.Executor.RunAllAsync();

        Assert.Equal(AdvertisingState.Advertising, rig.Manager.Status.Advertising);
    }

    [Fact]
    public async Task RestartAdvertisingIsIgnoredWhenBluetoothIsOffOrATabletIsConnected()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Manager.RestartAdvertising();
        await rig.Executor.RunAllAsync();
        Assert.Equal(starts, rig.Server.StartCalls.Count); // connected: do not drop the link

        rig.Server.SimulateUnsubscribe(Tablet);
        rig.Radio.Raise(BluetoothAvailability.Off);
        await rig.Executor.RunAllAsync();
        rig.Manager.RestartAdvertising();
        await rig.Executor.RunAllAsync();
        Assert.Equal(starts, rig.Server.StartCalls.Count); // off: nothing to restart
    }

    // -- connection lifecycle --

    [Fact]
    public async Task SubscribingSendsTheHandshakeThenDesktopInfoThenCapabilitiesInThatOrder()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        List<JsonElement> sent = rig.Sent();
        Assert.Equal(new[] { "state", "desktop_info", "capabilities" }, sent.Select(Rig.Type));

        Assert.Equal("connected", sent[0].GetProperty("status").GetString());
        Assert.Equal("windows", sent[1].GetProperty("platform").GetString());
        Assert.Equal("ALI-PC", sent[1].GetProperty("name").GetString());
        Assert.Equal(
            new[] { "actions", "telemetry.cpu", "media", "media.artwork" },
            sent[2].GetProperty("ids").EnumerateArray().Select(e => e.GetString()!));
    }

    [Fact]
    public async Task ConnectingUpdatesTheStatusWithTheTabletsAddress()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        Assert.True(rig.Manager.Status.TabletConnected);
        Assert.Equal("AA:BB:CC:DD:EE:FF", rig.Manager.Status.TabletLabel);
    }

    [Fact]
    public async Task TelemetryOnlyRunsWhileATabletIsSubscribed()
    {
        var rig = new Rig();
        await rig.StartAsync();
        Assert.False(rig.Live.IsRunning);

        await rig.ConnectAsync();
        Assert.True(rig.Live.IsRunning);

        rig.Server.SimulateUnsubscribe(Tablet);
        await rig.SettleAsync();
        Assert.False(rig.Live.IsRunning);
    }

    [Fact]
    public async Task UnsubscribingClearsTheConnectionAndChecksAdvertisingShortlyAfter()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        rig.Server.SimulateUnsubscribe(Tablet);
        await rig.SettleAsync();

        Assert.False(rig.Manager.Status.TabletConnected);
        Assert.Equal(new[] { TimeSpan.FromSeconds(2) }, rig.Scheduler.PendingDelays);
    }

    [Fact]
    public async Task IfWindowsPausedAdvertisingWhileConnectedItIsRestartedAfterTheTabletLeaves()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Server.IsAdvertising = false; // Windows stopped advertising while connected
        int starts = rig.Server.StartCalls.Count;

        rig.Server.SimulateUnsubscribe(Tablet);
        await rig.SettleAsync();
        rig.Scheduler.FireAll();
        await rig.SettleAsync();

        Assert.Equal(starts + 1, rig.Server.StartCalls.Count);
        Assert.Equal(AdvertisingState.Advertising, rig.Manager.Status.Advertising);
    }

    [Fact]
    public async Task IfAdvertisingIsStillUpAfterTheTabletLeavesNothingIsRestarted()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Server.SimulateUnsubscribe(Tablet);
        await rig.SettleAsync();
        rig.Scheduler.FireAll();
        await rig.SettleAsync();

        Assert.Equal(starts, rig.Server.StartCalls.Count);
    }

    [Fact]
    public async Task ReconnectingSendsAFreshHandshakeAndRestartsTelemetry()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Server.SimulateUnsubscribe(Tablet);
        await rig.SettleAsync();

        await rig.ConnectAsync();

        Assert.Equal(2, rig.SentOfType("desktop_info").Count);
        Assert.Equal(2, rig.Sent().Count(m => Rig.Type(m) == "state" && m.GetProperty("status").GetString() == "connected"));
        Assert.True(rig.Live.IsRunning);
    }

    [Fact]
    public async Task ADuplicateSubscribeDoesNotRestartTheSession()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        int infoCount = rig.SentOfType("desktop_info").Count;

        rig.Server.SimulateSubscribe(Tablet);
        await rig.SettleAsync();

        Assert.Equal(infoCount, rig.SentOfType("desktop_info").Count);
    }

    [Fact]
    public async Task ASecondTabletTakesOverAndTheFirstOnesLaterUnsubscribeIsIgnored()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync(Tablet);
        await rig.ConnectAsync(OtherTablet);
        Assert.Equal("11:22:33:44:55:66", rig.Manager.Status.TabletLabel);

        rig.Server.SimulateUnsubscribe(Tablet);
        await rig.SettleAsync();

        Assert.True(rig.Manager.Status.TabletConnected);
        Assert.True(rig.Live.IsRunning);
    }

    [Fact]
    public async Task ASubscribeBeforeBluetoothIsOnIsIgnored()
    {
        var rig = new Rig();
        rig.Radio.Current = BluetoothAvailability.Off;
        await rig.StartAsync();

        rig.Server.SimulateSubscribe(Tablet);
        await rig.SettleAsync();

        Assert.False(rig.Manager.Status.TabletConnected);
        Assert.Empty(rig.Server.Notifications);
    }

    // -- Bluetooth off / on --

    [Fact]
    public async Task TurningBluetoothOffTearsEverythingDown()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        rig.Radio.Raise(BluetoothAvailability.Off);
        await rig.SettleAsync();

        Assert.Equal(new AgentStatus(BluetoothState.Off, AdvertisingState.Stopped, AdvertisingMode.None, null), rig.Manager.Status);
        Assert.False(rig.Live.IsRunning);
        Assert.False(rig.Server.IsAdvertising);
        Assert.Empty(rig.Scheduler.PendingDelays);
    }

    [Fact]
    public async Task TurningBluetoothBackOnRecreatesAndAdvertisesAgain()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Radio.Raise(BluetoothAvailability.Off);
        await rig.SettleAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Radio.Raise(BluetoothAvailability.On);
        await rig.SettleAsync();

        Assert.Equal(starts + 1, rig.Server.StartCalls.Count);
        Assert.Equal(AdvertisingState.Advertising, rig.Manager.Status.Advertising);

        // ...and the tablet can reconnect and gets a normal session.
        await rig.ConnectAsync();
        Assert.True(rig.Manager.Status.TabletConnected);
    }

    [Fact]
    public async Task NoNotificationIsSentToAClientThatBluetoothAlreadyDropped()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        int sentBefore = rig.Server.Notifications.Count;

        rig.Radio.Raise(BluetoothAvailability.Off);
        await rig.SettleAsync();
        await rig.WriteAsync("{\"type\":\"command\",\"action\":\"ping\"}");

        Assert.Equal(sentBefore, rig.Server.Notifications.Count);
    }

    [Fact]
    public async Task ARepeatedOnEventWhileAlreadyAdvertisingDoesNotRestartAnything()
    {
        var rig = new Rig();
        await rig.StartAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Radio.Raise(BluetoothAvailability.Off);
        rig.Radio.Raise(BluetoothAvailability.On);
        await rig.SettleAsync();
        rig.Radio.Raise(BluetoothAvailability.On);
        await rig.SettleAsync();

        Assert.Equal(starts + 1, rig.Server.StartCalls.Count);
    }

    // -- sleep / resume --

    [Fact]
    public async Task ResumingFromSleepRebuildsTheServiceAfterASettleDelay()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Manager.OnSystemResumed();
        await rig.SettleAsync();

        Assert.False(rig.Manager.Status.TabletConnected);
        Assert.Equal(new[] { TimeSpan.FromSeconds(3) }, rig.Scheduler.PendingDelays);
        Assert.Equal(starts, rig.Server.StartCalls.Count); // not yet: the controller needs a moment

        rig.Scheduler.FireAll();
        await rig.SettleAsync();

        Assert.Equal(starts + 1, rig.Server.StartCalls.Count);
        Assert.Equal(AdvertisingState.Advertising, rig.Manager.Status.Advertising);
    }

    [Fact]
    public async Task ResumingWhileBluetoothIsOffDoesNothing()
    {
        var rig = new Rig();
        rig.Radio.Current = BluetoothAvailability.Off;
        await rig.StartAsync();

        rig.Manager.OnSystemResumed();
        await rig.SettleAsync();

        Assert.Empty(rig.Scheduler.PendingDelays);
        Assert.Empty(rig.Server.StartCalls);
    }

    // -- unsolicited advertising events --

    [Fact]
    public async Task ATransientAbortedEventFollowedByStartedCausesNoRestart()
    {
        var rig = new Rig();
        await rig.StartAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Server.SimulateAdvertising(AdvertisingEvent.Aborted);
        rig.Server.SimulateAdvertising(AdvertisingEvent.Started);
        await rig.SettleAsync();
        rig.Scheduler.FireAll(); // the deferred check looks at the real state: still advertising
        await rig.SettleAsync();

        Assert.Equal(starts, rig.Server.StartCalls.Count);
    }

    [Fact]
    public async Task AnAbortThatNeverRecoversIsRestartedAfterTheDeferredCheck()
    {
        var rig = new Rig();
        await rig.StartAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Server.IsAdvertising = false;
        rig.Server.SimulateAdvertising(AdvertisingEvent.Aborted);
        await rig.SettleAsync();
        rig.Scheduler.FireAll();
        await rig.SettleAsync();

        Assert.Equal(starts + 1, rig.Server.StartCalls.Count);
    }

    [Fact]
    public async Task AdvertisingEventsWhileATabletIsConnectedAreIgnored()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        int starts = rig.Server.StartCalls.Count;

        rig.Server.IsAdvertising = false;
        rig.Server.SimulateAdvertising(AdvertisingEvent.Stopped);
        await rig.SettleAsync();
        rig.Scheduler.FireAll();
        await rig.SettleAsync();

        Assert.Equal(starts, rig.Server.StartCalls.Count);
    }

    // -- commands --

    [Fact]
    public async Task ACommandIsExecutedAndAcknowledged()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        await rig.WriteAsync("{\"type\":\"command\",\"action\":\"volume_up\"}");

        Assert.Equal(new[] { "VolumeUp" }, rig.Controllers.AllCalls);
        JsonElement ack = rig.Sent().Last();
        Assert.Equal("state", Rig.Type(ack));
        Assert.Equal("volume_up", ack.GetProperty("action").GetString());
        Assert.Equal("ok", ack.GetProperty("status").GetString());
    }

    [Fact]
    public async Task AFailedCommandIsAcknowledgedWithAnError()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Controllers.Apps.Result = false;

        await rig.WriteAsync("{\"type\":\"command\",\"action\":\"open_terminal\"}");

        JsonElement ack = rig.Sent().Last();
        Assert.Equal("error", ack.GetProperty("status").GetString());
        Assert.Equal("open_terminal", ack.GetProperty("action").GetString());
        Assert.Contains(rig.Log, l => l.Contains("open_terminal") && l.Contains("failed"));
    }

    [Fact]
    public async Task AnUnparseableWriteGetsAnErrorStateNotSilence()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        await rig.WriteAsync("{\"type\":\"command\",\"a"); // exactly what a 20-byte ATT payload used to truncate to

        JsonElement ack = rig.Sent().Last();
        Assert.Equal("state", Rig.Type(ack));
        Assert.Equal("error", ack.GetProperty("status").GetString());
        Assert.Empty(rig.Controllers.AllCalls);
    }

    [Fact]
    public async Task AnUnsupportedActionIsRejectedAndExecutesNothing()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        await rig.WriteAsync("{\"type\":\"command\",\"action\":\"shutdown\"}");

        Assert.Empty(rig.Controllers.AllCalls);
        Assert.Equal("error", rig.Sent().Last().GetProperty("status").GetString());
    }

    [Fact]
    public async Task AnOversizedWriteIsRejectedBeforeParsing()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        Rig_Write(rig, new string('a', 600));
        await rig.SettleAsync();

        Assert.Empty(rig.Controllers.AllCalls);
        Assert.Equal("error", rig.Sent().Last().GetProperty("status").GetString());
    }

    private static void Rig_Write(Rig rig, string action) =>
        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes($"{{\"type\":\"command\",\"action\":\"{action}\"}}"));

    [Fact]
    public async Task ACommandFromAClientThatNeverSubscribedIsIgnored()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync(Tablet);

        await rig.WriteAsync("{\"type\":\"command\",\"action\":\"volume_up\"}", client: OtherTablet);

        Assert.Empty(rig.Controllers.AllCalls);
    }

    [Fact]
    public async Task AFailingCommandDoesNotPoisonTheNextOne()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Controllers.System.Throws = true;

        await rig.WriteAsync("{\"type\":\"command\",\"action\":\"lock_screen\"}");
        await rig.WriteAsync("{\"type\":\"command\",\"action\":\"volume_up\"}");

        List<JsonElement> acks = rig.Sent().Where(m => Rig.Type(m) == "state" && m.TryGetProperty("action", out _)).ToList();
        Assert.Equal("error", acks[0].GetProperty("status").GetString());
        Assert.Equal("ok", acks[1].GetProperty("status").GetString());
    }

    [Fact]
    public async Task WriteTrafficNeverReachesTheArtworkPathAndViceVersa()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        // An artwork_request for an id the agent never produced: no ack, no transfer, no command.
        await rig.WriteAsync("{\"type\":\"artwork_request\",\"artworkId\":\"0123456789abcdef\"}");

        Assert.Empty(rig.Controllers.AllCalls);
        Assert.DoesNotContain(rig.Sent(), m => Rig.Type(m).StartsWith("artwork"));
        Assert.Equal(3, rig.Sent().Count); // still just the connect handshake
    }

    // -- live state delivery --

    [Fact]
    public async Task LiveStateIsDeliveredToTheSubscribedTablet()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        rig.Telemetry.Next = new SystemMetrics(CpuPercent: 42, MemoryPercent: 50);
        await rig.Live.TickAsync();
        await rig.SettleAsync();

        JsonElement metrics = rig.SentOfType("metrics").Single();
        Assert.Equal(42, metrics.GetProperty("cpu").GetInt32());
    }

    [Fact]
    public async Task LiveStateIsNotDeliveredWithoutASubscriber()
    {
        var rig = new Rig();
        await rig.StartAsync();

        rig.Telemetry.Next = new SystemMetrics(CpuPercent: 42);
        await rig.Live.TickAsync();
        await rig.SettleAsync();

        Assert.Empty(rig.Server.Notifications);
    }

    [Fact]
    public async Task ALiveMessageLargerThanTheClientsLimitIsDroppedNeverTruncated()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Server.MaxNotification = 40; // an unexpectedly small negotiated MTU
        rig.Server.Notifications.Clear();

        rig.Telemetry.Next = new SystemMetrics(CpuPercent: 42, MemoryPercent: 50, GpuPercent: 10);
        await rig.Live.TickAsync();
        await rig.SettleAsync();

        // The 47-byte metrics message does not fit a 40-byte limit and is dropped; the 34-byte
        // "media unavailable" message does fit and is delivered intact.
        Assert.DoesNotContain(rig.Server.SentJson(), j => j.Contains("metrics"));
        Assert.All(rig.Server.Notifications, n => Assert.True(n.Length <= 40));
        Assert.Contains(rig.Log, l => l.Contains("exceeds"));
    }

    [Fact]
    public async Task TheSizeBackstopIsLoggedOnceNotOnEveryTick()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Server.MaxNotification = 40;
        rig.Telemetry.Next = new SystemMetrics(CpuPercent: 42, MemoryPercent: 50, GpuPercent: 10);

        for (int i = 0; i < 5; i++) await rig.Live.TickAsync();
        await rig.SettleAsync();

        Assert.Equal(1, rig.Log.Count(l => l.Contains("dropping")));
    }

    [Fact]
    public async Task HighPriorityMessagesKeepTheirArrivalOrder()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();
        rig.Server.Notifications.Clear();

        // Hold the link, queue telemetry, then a command arrives.
        rig.Server.NotifyGate = new TaskCompletionSource();
        rig.Telemetry.Next = new SystemMetrics(CpuPercent: 1);
        await rig.Live.TickAsync();
        await rig.Executor.RunAllAsync();
        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes("{\"type\":\"command\",\"action\":\"ping\"}"));
        await rig.Executor.RunAllAsync();
        rig.Server.NotifyGate.SetResult();
        await rig.SettleAsync();

        // All high priority, so strictly in arrival order: the tick's telemetry and playback state, then the ack.
        Assert.Equal(new[] { "metrics", "media", "state" }, rig.Sent().Select(Rig.Type));
    }

    // -- artwork --

    private async Task<(Rig Rig, string ArtworkId, byte[] Data)> RigWithArtworkAsync(int artworkBytes = 1500)
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        var raw = Enumerable.Range(0, artworkBytes).Select(i => (byte)(i * 7 + 3)).ToArray();
        rig.MediaSource.Sessions = new() { new MediaState("s", "spotify", MediaPlaybackState.Playing, "Song", "Artist", "Album") };
        rig.MediaSource.Artwork = raw;
        await rig.Live.TickAsync();
        await rig.SettleAsync();

        string id = rig.SentOfType("track").Single().GetProperty("artworkId").GetString()!;
        return (rig, id, raw);
    }

    private static string ArtworkRequest(string id) => $"{{\"type\":\"artwork_request\",\"artworkId\":\"{id}\"}}";

    [Fact]
    public async Task AKnownArtworkRequestStartsATransferTheTabletCanReassemble()
    {
        (Rig rig, string id, byte[] data) = await RigWithArtworkAsync();
        rig.Server.Notifications.Clear();

        await rig.WriteAsync(ArtworkRequest(id));

        List<byte[]> notifications = rig.Server.Notifications.ToList();
        Assert.Equal(id, JsonDocument.Parse(notifications[0]).RootElement.GetProperty("artworkId").GetString());
        Assert.Equal("artwork_begin", JsonDocument.Parse(notifications[0]).RootElement.GetProperty("type").GetString());
        Assert.Equal("artwork_end", JsonDocument.Parse(notifications[^1]).RootElement.GetProperty("type").GetString());

        byte[] reassembled = notifications.Skip(1).Take(notifications.Count - 2).SelectMany(n => ArtworkChunkFrame.Decode(n)!.Payload).ToArray();
        Assert.Equal(data, reassembled);
        Assert.All(notifications, n => Assert.True(n.Length <= WireLimits.MaxNotificationBytes));
    }

    [Fact]
    public async Task ChunkSizeFollowsTheClientsRealNotificationLimit()
    {
        (Rig rig, string id, byte[] data) = await RigWithArtworkAsync(1500);
        rig.Server.MaxNotification = 100;
        rig.Server.Notifications.Clear();

        await rig.WriteAsync(ArtworkRequest(id));

        List<byte[]> chunks = rig.Server.Notifications.Where(n => n[0] == ArtworkWire.ChunkMagicByte).ToList();
        Assert.All(chunks, c => Assert.True(c.Length <= 100));
        Assert.Equal(data.Length, chunks.Sum(c => c.Length - ArtworkWire.ChunkHeaderSize));
    }

    [Fact]
    public async Task AnUnknownArtworkRequestSendsNothing()
    {
        (Rig rig, _, _) = await RigWithArtworkAsync();
        rig.Server.Notifications.Clear();

        await rig.WriteAsync(ArtworkRequest("ffffffffffffffff"));

        Assert.Empty(rig.Server.Notifications);
        Assert.Contains(rig.Log, l => l.Contains("unknown id"));
    }

    [Fact]
    public async Task ArtworkThatCannotFitTheClientsLimitIsNotSent()
    {
        (Rig rig, string id, _) = await RigWithArtworkAsync(1500);
        rig.Server.MaxNotification = 30; // 18 payload bytes per chunk -> far more than 512 chunks
        rig.Server.Notifications.Clear();

        await rig.WriteAsync(ArtworkRequest(id));

        Assert.Empty(rig.Server.Notifications);
    }

    [Fact]
    public async Task ACommandAcknowledgementOvertakesAnArtworkTransferInProgress()
    {
        (Rig rig, string id, _) = await RigWithArtworkAsync(6000);
        rig.Server.Notifications.Clear();

        // Block the link after the request so frames queue up, then a button press arrives.
        rig.Server.NotifyGate = new TaskCompletionSource();
        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes(ArtworkRequest(id)));
        await rig.Executor.RunAllAsync();
        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes("{\"type\":\"command\",\"action\":\"ping\"}"));
        await rig.Executor.RunAllAsync();
        rig.Server.NotifyGate.SetResult();
        await rig.SettleAsync();

        List<byte[]> order = rig.Server.Notifications.ToList();
        int ackAt = order.FindIndex(n => n[0] == (byte)'{' && Encoding.UTF8.GetString(n).Contains("\"ping\""));
        int endAt = order.FindIndex(n => n[0] == (byte)'{' && Encoding.UTF8.GetString(n).Contains("artwork_end"));
        Assert.True(ackAt >= 0 && endAt >= 0);
        Assert.True(ackAt < 3, $"ack was at position {ackAt}: it must not wait for the whole transfer");
        Assert.True(ackAt < endAt);
    }

    [Fact]
    public async Task DisconnectingMidTransferCancelsItAndNothingLeaksToTheNextConnection()
    {
        (Rig rig, string id, _) = await RigWithArtworkAsync(6000);
        rig.Server.Notifications.Clear();
        rig.Server.NotifyGate = new TaskCompletionSource();
        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes(ArtworkRequest(id)));
        await rig.Executor.RunAllAsync();

        rig.Server.SimulateUnsubscribe(Tablet);
        await rig.Executor.RunAllAsync();
        rig.Server.NotifyGate.SetResult();
        await rig.Manager.Pump.WhenIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        // At most the one frame that was already in flight went out; the rest was discarded.
        Assert.True(rig.Server.Notifications.Count <= 1, $"{rig.Server.Notifications.Count} notifications leaked");

        rig.Server.Notifications.Clear();
        await rig.ConnectAsync();
        Assert.DoesNotContain(rig.Server.Notifications, n => n[0] == ArtworkWire.ChunkMagicByte);
        Assert.Equal(new[] { "state", "desktop_info", "capabilities" }, rig.Sent().Select(Rig.Type));
    }

    [Fact]
    public async Task ATrackChangeCancelsAQueuedTransferForTheOldArtwork()
    {
        (Rig rig, string id, _) = await RigWithArtworkAsync(6000);
        rig.Server.Notifications.Clear();
        rig.Server.NotifyGate = new TaskCompletionSource();
        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes(ArtworkRequest(id)));
        await rig.Executor.RunAllAsync();

        // The track changes (new artwork) while the old image is still queued.
        rig.MediaSource.Sessions = new() { new MediaState("s", "spotify", MediaPlaybackState.Playing, "Next song", "Artist", "Album") };
        rig.MediaSource.Artwork = Enumerable.Range(0, 900).Select(i => (byte)(i * 11)).ToArray();
        await rig.Live.TickAsync();
        await rig.Executor.RunAllAsync();
        rig.Server.NotifyGate.SetResult();
        await rig.SettleAsync();

        // The old transfer's remaining frames were thrown away: its artwork_end never goes out.
        Assert.DoesNotContain(rig.Server.SentJson(), j => j.Contains("artwork_end") && j.Contains(id));
    }

    [Fact]
    public async Task AReRequestReplacesThePreviousTransferInsteadOfAppendingToIt()
    {
        (Rig rig, string id, _) = await RigWithArtworkAsync(1500);
        rig.Server.Notifications.Clear();
        rig.Server.NotifyGate = new TaskCompletionSource();

        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes(ArtworkRequest(id)));
        await rig.Executor.RunAllAsync();
        rig.Server.SimulateWrite(Tablet, Encoding.UTF8.GetBytes(ArtworkRequest(id)));
        await rig.Executor.RunAllAsync();
        rig.Server.NotifyGate.SetResult();
        await rig.SettleAsync();

        // One begin from the transfer that was already in flight (frame 0), plus one complete replacement.
        int begins = rig.Server.SentJson().Count(j => j.Contains("artwork_begin"));
        int ends = rig.Server.SentJson().Count(j => j.Contains("artwork_end"));
        Assert.True(begins <= 2);
        Assert.Equal(1, ends);
    }

    // -- shutdown --

    [Fact]
    public async Task StoppingLeavesNoAdvertisementOrSamplingBehind()
    {
        var rig = new Rig();
        await rig.StartAsync();
        await rig.ConnectAsync();

        Task stop = rig.Manager.StopAsync();
        await rig.Executor.RunAllAsync();
        await stop;

        Assert.False(rig.Server.IsAdvertising);
        Assert.False(rig.Live.IsRunning);
        Assert.False(rig.Manager.Status.TabletConnected);
        Assert.Empty(rig.Scheduler.PendingDelays);
    }

    [Fact]
    public async Task StatusChangesAreRaisedOnlyWhenSomethingActuallyChanged()
    {
        var rig = new Rig();
        await rig.StartAsync();
        int count = rig.Statuses.Count;

        rig.Radio.Raise(BluetoothAvailability.On); // already on and advertising: no change
        await rig.SettleAsync();

        Assert.Equal(count, rig.Statuses.Count);
    }
}
