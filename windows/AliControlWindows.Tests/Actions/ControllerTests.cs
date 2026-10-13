using AliControlWindows.Actions;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.Actions;

/// <summary>
/// What each Windows action actually does, verified against recording fakes: the exact
/// key chords, the exact launch targets, the exact window commands. Nothing here
/// presses a key, starts a program or touches a window.
/// </summary>
public class ControllerTests
{
    // -- media --

    [Fact]
    public void MediaTransportUsesTheSystemMediaKeys()
    {
        var keyboard = new FakeKeyboard();
        var media = new MediaController(keyboard);

        Assert.True(media.PlayPause());
        Assert.True(media.NextTrack());
        Assert.True(media.PreviousTrack());
        Assert.True(media.MuteToggle());

        Assert.Equal(new[]
        {
            new[] { VirtualKey.MediaPlayPause },
            new[] { VirtualKey.MediaNext },
            new[] { VirtualKey.MediaPrevious },
            new[] { VirtualKey.VolumeMute }
        }, keyboard.Chords);
    }

    [Fact]
    public void VolumeStepIsFiveKeyPressesOfTheSystemVolumeKeys()
    {
        var keyboard = new FakeKeyboard();
        var media = new MediaController(keyboard);

        Assert.True(media.VolumeUp());
        Assert.True(media.VolumeDown());

        Assert.Equal(MediaController.VolumeKeyPressesPerStep * 2, keyboard.Chords.Count);
        Assert.All(keyboard.Chords.Take(5), c => Assert.Equal(new[] { VirtualKey.VolumeUp }, c));
        Assert.All(keyboard.Chords.Skip(5), c => Assert.Equal(new[] { VirtualKey.VolumeDown }, c));
    }

    [Fact]
    public void AMediaKeyThatWindowsRefusesReportsFailure()
    {
        var media = new MediaController(new FakeKeyboard { Result = false });
        Assert.False(media.PlayPause());
        Assert.False(media.VolumeUp());
    }

    // -- system --

    [Fact]
    public void LockSleepAndDisplayOffUseSystemCallsNotKeystrokes()
    {
        var keyboard = new FakeKeyboard();
        var power = new FakePower();
        var system = new SystemController(keyboard, power);

        Assert.True(system.LockScreen());
        Assert.True(system.Sleep());
        Assert.True(system.DisplaySleep());

        Assert.Equal(new[] { "lock", "sleep", "displayOff" }, power.Calls);
        Assert.Empty(keyboard.Chords);
    }

    [Fact]
    public void ShortcutActionsPressTheRealWindowsShortcuts()
    {
        var keyboard = new FakeKeyboard();
        var system = new SystemController(keyboard, new FakePower());

        Assert.True(system.Screenshot());
        Assert.True(system.ShowDesktop());
        Assert.True(system.WindowsSearch());
        Assert.True(system.TaskView());

        Assert.Equal(new[]
        {
            new[] { VirtualKey.LeftWindows, VirtualKey.Snapshot }, // Win+PrintScreen: whole screen to Pictures\Screenshots
            new[] { VirtualKey.LeftWindows, VirtualKey.D },
            new[] { VirtualKey.LeftWindows, VirtualKey.S },
            new[] { VirtualKey.LeftWindows, VirtualKey.Tab }
        }, keyboard.Chords);
    }

    [Fact]
    public void FailedPowerCallsReportFailure()
    {
        var system = new SystemController(new FakeKeyboard(), new FakePower { Result = false });
        Assert.False(system.LockScreen());
        Assert.False(system.Sleep());
        Assert.False(system.DisplaySleep());
    }

    // -- productivity --

    [Fact]
    public void EditShortcutsAreTheWindowsConventions()
    {
        var keyboard = new FakeKeyboard();
        var productivity = new ProductivityController(keyboard);

        Assert.True(productivity.Copy());
        Assert.True(productivity.Paste());
        Assert.True(productivity.Undo());
        Assert.True(productivity.Redo());

        Assert.Equal(new[]
        {
            new[] { VirtualKey.Control, VirtualKey.C },
            new[] { VirtualKey.Control, VirtualKey.V },
            new[] { VirtualKey.Control, VirtualKey.Z },
            new[] { VirtualKey.Control, VirtualKey.Y }
        }, keyboard.Chords);
    }

    [Fact]
    public void InjectionBlockedByTheOsReportsFailure()
    {
        // e.g. the foreground window is elevated and this process is not (UIPI).
        var productivity = new ProductivityController(new FakeKeyboard { Result = false });
        Assert.False(productivity.Copy());
        Assert.False(productivity.Redo());
    }

    // -- the injected key set is closed --

    [Fact]
    public void OnlyTheDocumentedKeysAreEverInjected()
    {
        var keyboard = new FakeKeyboard();
        var media = new MediaController(keyboard);
        var system = new SystemController(keyboard, new FakePower());
        var productivity = new ProductivityController(keyboard);

        media.PlayPause(); media.NextTrack(); media.PreviousTrack(); media.VolumeUp(); media.VolumeDown(); media.MuteToggle();
        system.Screenshot(); system.ShowDesktop(); system.WindowsSearch(); system.TaskView();
        productivity.Copy(); productivity.Paste(); productivity.Undo(); productivity.Redo();

        var used = keyboard.Chords.SelectMany(c => c).Distinct().ToHashSet();
        Assert.Subset(Enum.GetValues<VirtualKey>().ToHashSet(), used);
        Assert.DoesNotContain(keyboard.Chords, c => c.Length > 2);
    }

    // -- app launching --

    private static (AppLauncher Launcher, FakeShell Shell, FakeProbe Probe) CreateLauncher()
    {
        var shell = new FakeShell();
        var probe = new FakeProbe();
        return (new AppLauncher(shell, probe), shell, probe);
    }

    [Fact]
    public void WindowsTerminalIsLaunchedThroughItsExecutionAlias()
    {
        var (launcher, shell, probe) = CreateLauncher();
        string alias = @"C:\Users\test\AppData\Local\Microsoft\WindowsApps\wt.exe";
        probe.Files.Add(alias);

        Assert.True(launcher.OpenTerminal());
        Assert.Equal(new[] { alias }, shell.Opened);
    }

    [Fact]
    public void TerminalFallsBackToAFixedWindowsPowerShellWhenWindowsTerminalIsMissing()
    {
        var (launcher, shell, probe) = CreateLauncher();
        string powershell = @"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe";
        probe.Files.Add(powershell);

        Assert.True(launcher.OpenTerminal());
        Assert.Equal(new[] { powershell }, shell.Opened);
    }

    [Fact]
    public void TerminalFailsCleanlyWhenNothingIsAvailable()
    {
        var (launcher, shell, _) = CreateLauncher();
        Assert.False(launcher.OpenTerminal());
        Assert.Empty(shell.Opened);
    }

    [Fact]
    public void VsCodeIsFoundByAppPathsFirst()
    {
        var (launcher, shell, probe) = CreateLauncher();
        probe.AppPaths["Code.exe"] = @"D:\Tools\VSCode\Code.exe";
        probe.Files.Add(@"D:\Tools\VSCode\Code.exe");
        probe.Files.Add(@"C:\Users\test\AppData\Local\Programs\Microsoft VS Code\Code.exe");

        Assert.True(launcher.OpenVsCode());
        Assert.Equal(new[] { @"D:\Tools\VSCode\Code.exe" }, shell.Opened);
    }

    [Theory]
    [InlineData(@"C:\Users\test\AppData\Local\Programs\Microsoft VS Code\Code.exe")]
    [InlineData(@"C:\Program Files\Microsoft VS Code\Code.exe")]
    [InlineData(@"C:\Program Files (x86)\Microsoft VS Code\Code.exe")]
    public void VsCodeIsFoundInTheStandardInstallLocations(string installed)
    {
        var (launcher, shell, probe) = CreateLauncher();
        probe.Files.Add(installed);

        Assert.True(launcher.OpenVsCode());
        Assert.Equal(new[] { installed }, shell.Opened);
    }

    [Fact]
    public void VsCodeNotInstalledFailsSafely()
    {
        var (launcher, shell, _) = CreateLauncher();
        Assert.False(launcher.OpenVsCode());
        Assert.Empty(shell.Opened);
    }

    [Fact]
    public void ARegisteredAppPathThatNoLongerExistsIsNotLaunched()
    {
        var (launcher, shell, probe) = CreateLauncher();
        probe.AppPaths["Code.exe"] = @"D:\Gone\Code.exe"; // stale registry entry
        Assert.False(launcher.OpenVsCode());
        Assert.Empty(shell.Opened);
    }

    [Fact]
    public void ExplorerAndTaskManagerAreFixedSystemFiles()
    {
        var (launcher, shell, probe) = CreateLauncher();
        probe.Files.Add(@"C:\Windows\explorer.exe");
        probe.Files.Add(@"C:\Windows\System32\Taskmgr.exe");

        Assert.True(launcher.OpenExplorer());
        Assert.True(launcher.OpenTaskManager());
        Assert.Equal(new[] { @"C:\Windows\explorer.exe", @"C:\Windows\System32\Taskmgr.exe" }, shell.Opened);
    }

    [Fact]
    public void SystemFilesMissingOrNoSystemRootFailSafely()
    {
        var (launcher, shell, probe) = CreateLauncher();
        Assert.False(launcher.OpenExplorer());

        probe.Environment.Remove("SystemRoot");
        probe.Files.Add(@"C:\Windows\explorer.exe");
        Assert.False(launcher.OpenExplorer());
        Assert.Empty(shell.Opened);
    }

    [Fact]
    public void TheDefaultBrowserIsResolvedNotHardcoded()
    {
        var (launcher, shell, probe) = CreateLauncher();
        probe.Browser = @"C:\Program Files\Mozilla Firefox\firefox.exe";
        probe.Files.Add(probe.Browser);

        Assert.True(launcher.OpenBrowser());
        Assert.Equal(new[] { @"C:\Program Files\Mozilla Firefox\firefox.exe" }, shell.Opened);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData(@"C:\Not\An\Exe.txt")]
    [InlineData(@"C:\Missing\browser.exe")]
    public void NoUsableDefaultBrowserFailsSafely(string? browser)
    {
        var (launcher, shell, probe) = CreateLauncher();
        probe.Browser = browser;
        Assert.False(launcher.OpenBrowser());
        Assert.Empty(shell.Opened);
    }

    [Fact]
    public void SpotifyIsOpenedThroughItsRegisteredUriScheme()
    {
        var (launcher, shell, probe) = CreateLauncher();
        probe.Schemes.Add("spotify");

        Assert.True(launcher.OpenSpotify());
        Assert.Equal(new[] { "spotify:" }, shell.Opened);
    }

    [Fact]
    public void SpotifyNotInstalledFailsWithoutRaisingTheShellsGetAnAppDialog()
    {
        var (launcher, shell, _) = CreateLauncher();
        Assert.False(launcher.OpenSpotify());
        Assert.Empty(shell.Opened); // the shell was never asked, so no dialog can appear
    }

    [Fact]
    public void SettingsOpensTheSettingsUri()
    {
        var (launcher, shell, _) = CreateLauncher();
        Assert.True(launcher.OpenSystemSettings());
        Assert.Equal(new[] { "ms-settings:" }, shell.Opened);
    }

    [Fact]
    public void AShellFailureIsReportedAsFailure()
    {
        var (launcher, shell, probe) = CreateLauncher();
        shell.Result = false;
        probe.Schemes.Add("spotify");
        Assert.False(launcher.OpenSystemSettings());
        Assert.False(launcher.OpenSpotify());
    }

    [Fact]
    public void LaunchTargetsAreAlwaysFixedStringsNeverTabletInput()
    {
        // Run every launcher with a fully populated machine and collect every target.
        var (launcher, shell, probe) = CreateLauncher();
        foreach (string file in new[]
                 {
                     @"C:\Users\test\AppData\Local\Microsoft\WindowsApps\wt.exe",
                     @"C:\Users\test\AppData\Local\Programs\Microsoft VS Code\Code.exe",
                     @"C:\Windows\explorer.exe", @"C:\Windows\System32\Taskmgr.exe", @"C:\Program Files\Browser\b.exe"
                 }) probe.Files.Add(file);
        probe.Schemes.Add("spotify");
        probe.Browser = @"C:\Program Files\Browser\b.exe";

        launcher.OpenTerminal(); launcher.OpenVsCode(); launcher.OpenExplorer(); launcher.OpenBrowser();
        launcher.OpenSpotify(); launcher.OpenSystemSettings(); launcher.OpenTaskManager();

        Assert.Equal(7, shell.Opened.Count);
        // No target contains a space-separated argument list, a redirect, a pipe or shell metacharacters.
        Assert.All(shell.Opened, t =>
        {
            Assert.DoesNotContain("&", t);
            Assert.DoesNotContain("|", t);
            Assert.DoesNotContain(">", t);
            Assert.DoesNotContain("\"", t);
            Assert.DoesNotContain(" -", t);
            Assert.DoesNotContain(" /", t);
        });
    }

    // -- window actions --

    private static WindowInfo App(bool maximized = false, int pid = 4242, string cls = "Chrome_WidgetWin_1", bool visible = true) =>
        new(0x1234, cls, pid, visible, maximized);

    [Fact]
    public void MinimizeAndCloseSendTheWindowsOwnSystemCommands()
    {
        var api = new FakeWindowApi { Foreground = App() };
        var windows = new WindowController(api, ownProcessId: 1);

        Assert.True(windows.MinimizeWindow());
        Assert.True(windows.CloseWindow());

        Assert.Equal(new[] { (nint)0x1234, 0x1234 }, api.Sent.Select(s => s.Handle));
        Assert.Equal(new[] { SysCommand.Minimize, SysCommand.Close }, api.Sent.Select(s => s.Command));
    }

    [Fact]
    public void FullscreenToggleMaximisesThenRestores()
    {
        var api = new FakeWindowApi { Foreground = App(maximized: false) };
        var windows = new WindowController(api, 1);
        Assert.True(windows.FullscreenToggle());

        api.Foreground = App(maximized: true);
        Assert.True(windows.FullscreenToggle());

        Assert.Equal(new[] { SysCommand.Maximize, SysCommand.Restore }, api.Sent.Select(s => s.Command));
    }

    [Theory]
    [InlineData("Progman")]
    [InlineData("WorkerW")]
    [InlineData("Shell_TrayWnd")]
    [InlineData("Shell_SecondaryTrayWnd")]
    [InlineData("progman")] // class names compare case-insensitively
    public void TheDesktopAndTaskbarAreNeverMinimisedOrClosed(string shellClass)
    {
        // Closing the desktop shell window raises the "Shut Down Windows" dialog.
        var api = new FakeWindowApi { Foreground = App(cls: shellClass) };
        var windows = new WindowController(api, 1);

        Assert.False(windows.CloseWindow());
        Assert.False(windows.MinimizeWindow());
        Assert.False(windows.FullscreenToggle());
        Assert.Empty(api.Sent);
    }

    [Fact]
    public void TheAgentsOwnWindowsAreNeverTouched()
    {
        var api = new FakeWindowApi { Foreground = App(pid: 777) };
        var windows = new WindowController(api, ownProcessId: 777);
        Assert.False(windows.CloseWindow());
        Assert.Empty(api.Sent);
    }

    [Fact]
    public void NoForegroundOrInvisibleWindowMeansFailure()
    {
        var api = new FakeWindowApi { Foreground = null };
        var windows = new WindowController(api, 1);
        Assert.False(windows.CloseWindow());

        api.Foreground = App(visible: false);
        Assert.False(windows.CloseWindow());

        api.Foreground = new WindowInfo(0, "Chrome_WidgetWin_1", 5, true, false);
        Assert.False(windows.CloseWindow());
        Assert.Empty(api.Sent);
    }

    [Fact]
    public void ABlockedMessageToAnElevatedWindowReportsFailure()
    {
        var api = new FakeWindowApi { Foreground = App(), SendResult = false };
        Assert.False(new WindowController(api, 1).CloseWindow());
    }

    [Fact]
    public void WindowActionsNeverTerminateProcesses()
    {
        // The only operations on IWindowApi are "foreground window" and "post a
        // system command" — there is no process-kill primitive to call.
        var methods = typeof(IWindowApi).GetMethods().Select(m => m.Name).ToHashSet();
        Assert.Equal(new[] { "GetForegroundWindow", "SendSysCommand" }.ToHashSet(), methods);
        Assert.Equal(new[] { SysCommand.Minimize, SysCommand.Maximize, SysCommand.Restore, SysCommand.Close }.ToHashSet(),
            Enum.GetValues<SysCommand>().ToHashSet());
    }
}
