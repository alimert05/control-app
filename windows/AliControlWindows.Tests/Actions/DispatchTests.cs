using AliControlWindows.Actions;
using AliControlWindows.Protocol;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.Actions;

public class DispatchTests
{
    private static (CommandDispatcher Dispatcher, FakeControllers Fakes, List<string> Log) Create()
    {
        var fakes = new FakeControllers();
        var log = new List<string>();
        return (new CommandDispatcher(fakes.MakeRegistry(), log.Add), fakes, log);
    }

    // -- registry completeness --

    [Fact]
    public void EveryDeclaredActionIsRegistered()
    {
        var registry = new FakeControllers().MakeRegistry();
        foreach (string id in AliControlActions.All) Assert.NotNull(registry.Find(id));
    }

    [Fact]
    public void RegistryContainsExactlyTheDeclaredActionsAndNothingElse()
    {
        var registry = new FakeControllers().MakeRegistry();
        Assert.Equal(AliControlActions.All.OrderBy(x => x), registry.RegisteredActionIds.OrderBy(x => x));
    }

    [Fact]
    public void ThereAreTwentySevenActionsPlusPing()
    {
        // 6 media + 7 system + 7 apps + 3 window + 4 productivity, plus the protocol-level ping.
        Assert.Equal(27 + 1, AliControlActions.All.Count);
        Assert.Equal(AliControlActions.All.Count, AliControlActions.All.Distinct().Count());
    }

    [Theory]
    [InlineData("shutdown")]
    [InlineData("restart")]
    [InlineData("logout")]
    [InlineData("run_shell_command")]
    [InlineData("powershell")]
    [InlineData("cmd /c calc")]
    [InlineData("C:\\Windows\\System32\\calc.exe")]
    [InlineData("..\\..\\evil.exe")]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData("PLAY_PAUSE")]       // IDs are exact, case-sensitive
    [InlineData("play_pause ")]
    public void UnknownActionIdsAreNotRegistered(string id)
    {
        Assert.Null(new FakeControllers().MakeRegistry().Find(id));
    }

    [Theory]
    [InlineData("spotlight")]
    [InlineData("mission_control")]
    [InlineData("open_finder")]
    [InlineData("open_activity_monitor")]
    public void TheRetiredReferencePlatformIdsAreRejectedByTheAgent(string legacyId)
    {
        // The tablet migrates saved layouts to the new ids; the agent never accepts the old ones.
        var (dispatcher, fakes, _) = Create();
        ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command(legacyId));
        Assert.Equal("error", reply.Status);
        Assert.Empty(fakes.AllCalls);
    }

    // -- every action routes to its controller method --

    [Fact]
    public void EachActionDispatchesToItsControllerMethod()
    {
        var fakes = new FakeControllers();
        ActionRegistry registry = fakes.MakeRegistry();

        foreach (string id in AliControlActions.All.Where(a => a != AliControlActions.Ping))
        {
            Assert.True(registry.Find(id)!.Execute(), id);
        }

        Assert.Equal(new[] { "PlayPause", "NextTrack", "PreviousTrack", "VolumeUp", "VolumeDown", "MuteToggle" }, fakes.Media.Calls);
        Assert.Equal(new[] { "LockScreen", "Sleep", "DisplaySleep", "Screenshot", "ShowDesktop", "WindowsSearch", "TaskView" }, fakes.System.Calls);
        Assert.Equal(new[] { "OpenTerminal", "OpenVsCode", "OpenExplorer", "OpenBrowser", "OpenSpotify", "OpenSystemSettings", "OpenTaskManager" }, fakes.Apps.Calls);
        Assert.Equal(new[] { "MinimizeWindow", "CloseWindow", "FullscreenToggle" }, fakes.Window.Calls);
        Assert.Equal(new[] { "Copy", "Paste", "Undo", "Redo" }, fakes.Productivity.Calls);
    }

    [Theory]
    [InlineData("play_pause", "PlayPause")]
    [InlineData("windows_search", "WindowsSearch")]
    [InlineData("task_view", "TaskView")]
    [InlineData("open_explorer", "OpenExplorer")]
    [InlineData("open_task_manager", "OpenTaskManager")]
    [InlineData("fullscreen_toggle", "FullscreenToggle")]
    [InlineData("redo", "Redo")]
    public void SpecificActionsReachTheRightMethod(string id, string method)
    {
        var (dispatcher, fakes, _) = Create();
        ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command(id));
        Assert.Equal("ok", reply.Status);
        Assert.Equal(new[] { method }, fakes.AllCalls);
    }

    [Fact]
    public void InputSimulationMetadataMatchesTheTablet()
    {
        // Must mirror ActionLibrary.kt's PermissionRequirement.INPUT_SIMULATION set exactly.
        var expected = new HashSet<string>
        {
            "screenshot", "show_desktop", "windows_search", "task_view",
            "copy", "paste", "undo", "redo"
        };
        ActionRegistry registry = new FakeControllers().MakeRegistry();
        foreach (ActionHandler handler in registry.Handlers)
        {
            Assert.Equal(expected.Contains(handler.Id), handler.UsesInputSimulation);
        }
    }

    // -- acknowledgements --

    [Fact]
    public void PingIsAcknowledgedWithoutCallingAnyController()
    {
        var (dispatcher, fakes, _) = Create();
        ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command("ping"));
        Assert.Equal(ProtocolMessage.State("ok", "ping"), reply);
        Assert.Empty(fakes.AllCalls);
    }

    [Fact]
    public void SuccessfulDispatchEchoesTheActionInAStateMessage()
    {
        var (dispatcher, _, _) = Create();
        ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command("volume_up"));
        Assert.Equal(ProtocolMessage.TypeState, reply.Type);
        Assert.Equal("volume_up", reply.Action);
        Assert.Equal("ok", reply.Status);
    }

    [Fact]
    public void ControllerFailureProducesAnErrorAckForThatCommand()
    {
        var (dispatcher, fakes, log) = Create();
        fakes.Apps.Result = false;
        ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command("open_terminal"));
        Assert.Equal(ProtocolMessage.State("error", "open_terminal"), reply);
        Assert.Empty(log); // a plain failure is not an exception
    }

    [Fact]
    public void UnsupportedActionIsRejectedWithoutExecutingAnything()
    {
        var (dispatcher, fakes, _) = Create();
        ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command("shutdown"));
        Assert.Equal("error", reply.Status);
        Assert.Equal("shutdown", reply.Action);
        Assert.Empty(fakes.AllCalls);
    }

    [Fact]
    public void MissingActionIsRejected()
    {
        var (dispatcher, fakes, _) = Create();
        ProtocolMessage reply = dispatcher.Dispatch(new ProtocolMessage(ProtocolMessage.TypeCommand));
        Assert.Equal("error", reply.Status);
        Assert.Null(reply.Action);
        Assert.Empty(fakes.AllCalls);
    }

    [Fact]
    public void NonCommandMessagesAreRejected()
    {
        var (dispatcher, fakes, _) = Create();
        Assert.Equal("error", dispatcher.Dispatch(ProtocolMessage.State("connected")).Status);
        Assert.Equal("error", dispatcher.Dispatch(new ProtocolMessage("metrics", "volume_up")).Status);
        Assert.Empty(fakes.AllCalls);
    }

    [Fact]
    public void AnOversizedUnknownActionIsEchoedBoundedSoTheAckStillFits()
    {
        var (dispatcher, _, _) = Create();
        ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command(new string('a', 400)));
        Assert.Equal("error", reply.Status);
        Assert.True(reply.Encode().Length <= WireLimits.MaxNotificationBytes);
    }

    // -- failures never poison later commands --

    [Fact]
    public void ACommandThatThrowsBecomesAnErrorAckAndLaterCommandsStillWork()
    {
        var (dispatcher, fakes, log) = Create();
        fakes.System.Throws = true;

        ProtocolMessage failed = dispatcher.Dispatch(ProtocolMessage.Command("lock_screen"));
        ProtocolMessage next = dispatcher.Dispatch(ProtocolMessage.Command("volume_up"));

        Assert.Equal("error", failed.Status);
        Assert.Equal("ok", next.Status);
        Assert.Contains(fakes.Media.Calls, c => c == "VolumeUp");
        Assert.Single(log);
        Assert.DoesNotContain("simulated", log[0]); // exception text is never logged, only its type
    }

    [Fact]
    public void AFailedActionDoesNotAffectTheNextOne()
    {
        var (dispatcher, fakes, _) = Create();
        fakes.System.Result = false;

        Assert.Equal("error", dispatcher.Dispatch(ProtocolMessage.Command("lock_screen")).Status);
        Assert.Equal("ok", dispatcher.Dispatch(ProtocolMessage.Command("volume_up")).Status);
        Assert.Equal("error", dispatcher.Dispatch(ProtocolMessage.Command("lock_screen")).Status);
    }

    [Fact]
    public void RepeatedFailuresNeverEscalateOrCallAnythingElse()
    {
        var (dispatcher, fakes, _) = Create();
        fakes.Productivity.Result = false;
        for (int i = 0; i < 5; i++)
        {
            Assert.Equal("error", dispatcher.Dispatch(ProtocolMessage.Command("undo")).Status);
        }
        Assert.Equal(Enumerable.Repeat("Undo", 5), fakes.Productivity.Calls);
        Assert.Empty(fakes.Media.Calls);
        Assert.Empty(fakes.System.Calls);
        Assert.Empty(fakes.Apps.Calls);
    }

    // -- no arbitrary execution path --

    [Fact]
    public void ArbitraryStringsNeverExecuteAnything()
    {
        var (dispatcher, fakes, _) = Create();
        var random = new Random(99);
        for (int i = 0; i < 2000; i++)
        {
            var chars = new char[random.Next(0, 40)];
            for (int c = 0; c < chars.Length; c++) chars[c] = (char)random.Next(32, 127);
            string candidate = new(chars);
            if (AliControlActions.All.Contains(candidate)) continue;

            ProtocolMessage reply = dispatcher.Dispatch(ProtocolMessage.Command(candidate));
            Assert.Equal("error", reply.Status);
        }
        Assert.Empty(fakes.AllCalls);
    }

    [Fact]
    public void ActionHandlersTakeNoParametersSoNothingFromTheTabletCanReachThem()
    {
        // Func<bool>: a handler cannot receive an argument, a path or a command string.
        foreach (ActionHandler handler in new FakeControllers().MakeRegistry().Handlers)
        {
            Assert.Equal(typeof(Func<bool>), handler.Execute.GetType());
        }
    }
}
