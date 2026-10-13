namespace AliControlWindows.Actions;

public interface IMediaControl
{
    bool PlayPause();
    bool NextTrack();
    bool PreviousTrack();
    bool VolumeUp();
    bool VolumeDown();
    bool MuteToggle();
}

public interface ISystemControl
{
    bool LockScreen();
    bool Sleep();
    bool DisplaySleep();
    bool Screenshot();
    bool ShowDesktop();
    bool WindowsSearch();
    bool TaskView();
}

public interface IAppLauncher
{
    bool OpenTerminal();
    bool OpenVsCode();
    bool OpenExplorer();
    bool OpenBrowser();
    bool OpenSpotify();
    bool OpenSystemSettings();
    bool OpenTaskManager();
}

public interface IWindowControl
{
    bool MinimizeWindow();
    bool CloseWindow();
    bool FullscreenToggle();
}

public interface IProductivityControl
{
    bool Copy();
    bool Paste();
    bool Undo();
    bool Redo();
}

/// <summary>
/// Media transport and volume through the system media keys (<c>VK_MEDIA_*</c>,
/// <c>VK_VOLUME_*</c>): global keys that work regardless of which window has focus,
/// show Windows' own volume flyout, and are routed by Windows to whichever player
/// currently owns the media session. No application scripting, no foreground
/// window involved.
/// </summary>
public sealed class MediaController : IMediaControl
{
    /// <summary>Windows moves the master volume 2% per key press; five presses ≈ the 10% step the tablet's buttons always meant.</summary>
    public const int VolumeKeyPressesPerStep = 5;

    private readonly IKeyboardInput _keyboard;

    public MediaController(IKeyboardInput keyboard) => _keyboard = keyboard;

    public bool PlayPause() => _keyboard.SendChord(new[] { VirtualKey.MediaPlayPause });
    public bool NextTrack() => _keyboard.SendChord(new[] { VirtualKey.MediaNext });
    public bool PreviousTrack() => _keyboard.SendChord(new[] { VirtualKey.MediaPrevious });
    public bool VolumeUp() => Repeat(VirtualKey.VolumeUp);
    public bool VolumeDown() => Repeat(VirtualKey.VolumeDown);
    public bool MuteToggle() => _keyboard.SendChord(new[] { VirtualKey.VolumeMute });

    private bool Repeat(VirtualKey key)
    {
        bool ok = true;
        for (int i = 0; i < VolumeKeyPressesPerStep; i++) ok &= _keyboard.SendChord(new[] { key });
        return ok;
    }
}

/// <summary>
/// System actions. Lock, sleep and display-off use dedicated system calls (so they
/// need no keyboard injection at all); screenshot, show desktop, Windows Search and
/// Task View press the real Windows shortcuts.
///
/// <list type="bullet">
/// <item><c>screenshot</c> = Win+PrintScreen: captures the whole screen to
/// <c>Pictures\Screenshots</c> without needing any interaction at the PC (unlike
/// Win+Shift+S, which opens a region-selection overlay).</item>
/// <item><c>lock_screen</c> locks the session; it never signs the user out.</item>
/// </list>
/// </summary>
public sealed class SystemController : ISystemControl
{
    private readonly IKeyboardInput _keyboard;
    private readonly IPowerControl _power;

    public SystemController(IKeyboardInput keyboard, IPowerControl power)
    {
        _keyboard = keyboard;
        _power = power;
    }

    public bool LockScreen() => _power.LockWorkStation();
    public bool Sleep() => _power.ScheduleSleep();
    public bool DisplaySleep() => _power.DisplayOff();

    public bool Screenshot() => _keyboard.SendChord(new[] { VirtualKey.LeftWindows, VirtualKey.Snapshot });
    public bool ShowDesktop() => _keyboard.SendChord(new[] { VirtualKey.LeftWindows, VirtualKey.D });
    public bool WindowsSearch() => _keyboard.SendChord(new[] { VirtualKey.LeftWindows, VirtualKey.S });
    public bool TaskView() => _keyboard.SendChord(new[] { VirtualKey.LeftWindows, VirtualKey.Tab });
}

/// <summary>Standard edit shortcuts on whatever currently has focus. Blocked by Windows (UIPI) toward elevated windows — reported as an error acknowledgement.</summary>
public sealed class ProductivityController : IProductivityControl
{
    private readonly IKeyboardInput _keyboard;

    public ProductivityController(IKeyboardInput keyboard) => _keyboard = keyboard;

    public bool Copy() => _keyboard.SendChord(new[] { VirtualKey.Control, VirtualKey.C });
    public bool Paste() => _keyboard.SendChord(new[] { VirtualKey.Control, VirtualKey.V });
    public bool Undo() => _keyboard.SendChord(new[] { VirtualKey.Control, VirtualKey.Z });

    /// <summary>Ctrl+Y is the Windows convention for redo.</summary>
    public bool Redo() => _keyboard.SendChord(new[] { VirtualKey.Control, VirtualKey.Y });
}

/// <summary>
/// Launches a *fixed* set of applications. Every target is chosen here — an
/// executable path derived from well-known install locations or the registry's
/// App Paths, or a URI scheme — and is never influenced by the tablet. Nothing
/// takes arguments. An app that is not installed makes the call return false
/// (clean error acknowledgement): no crash, no fallback to anything else.
/// </summary>
public sealed class AppLauncher : IAppLauncher
{
    public const string SettingsUri = "ms-settings:";
    public const string SpotifyUri = "spotify:";

    private readonly IShell _shell;
    private readonly ISystemProbe _probe;

    public AppLauncher(IShell shell, ISystemProbe probe)
    {
        _shell = shell;
        _probe = probe;
    }

    /// <summary>
    /// Windows Terminal through its per-user execution alias; if Windows Terminal
    /// is not installed, a fixed Windows PowerShell console instead, so the button
    /// still produces a terminal. (An interactive shell the user types into — the
    /// tablet cannot supply any text to it.)
    /// </summary>
    public bool OpenTerminal()
    {
        string? localAppData = _probe.GetEnvironmentVariable("LOCALAPPDATA");
        if (!string.IsNullOrEmpty(localAppData))
        {
            string alias = Path.Combine(localAppData, "Microsoft", "WindowsApps", "wt.exe");
            if (_probe.FileExists(alias) && _shell.Open(alias)) return true;
        }
        return OpenSystemFile(@"System32\WindowsPowerShell\v1.0\powershell.exe");
    }

    public bool OpenVsCode()
    {
        foreach (string candidate in VsCodeCandidates())
        {
            if (_probe.FileExists(candidate)) return _shell.Open(candidate);
        }
        return false;
    }

    public bool OpenExplorer() => OpenSystemFile("explorer.exe");

    /// <summary>The user's actual default browser, resolved through the shell's own association lookup — never hardcoded to Edge or Chrome.</summary>
    public bool OpenBrowser()
    {
        string? browser = _probe.DefaultBrowserExecutable();
        if (string.IsNullOrWhiteSpace(browser)
            || !browser.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
            || !_probe.FileExists(browser))
        {
            return false;
        }
        return _shell.Open(browser);
    }

    /// <summary>
    /// Spotify through its registered <c>spotify:</c> URI scheme (the desktop and
    /// the Microsoft Store editions both register it). Probing the registration
    /// first means a PC without Spotify gets a clean error instead of the shell's
    /// "Get an app to open this link" dialog.
    /// </summary>
    public bool OpenSpotify() => _probe.IsUriSchemeRegistered("spotify") && _shell.Open(SpotifyUri);

    public bool OpenSystemSettings() => _shell.Open(SettingsUri);

    public bool OpenTaskManager() => OpenSystemFile(@"System32\Taskmgr.exe");

    private IEnumerable<string> VsCodeCandidates()
    {
        string? appPath = _probe.ReadAppPath("Code.exe");
        if (!string.IsNullOrWhiteSpace(appPath)) yield return appPath;

        foreach ((string variable, string relative) in new[]
                 {
                     ("LOCALAPPDATA", @"Programs\Microsoft VS Code\Code.exe"),
                     ("ProgramFiles", @"Microsoft VS Code\Code.exe"),
                     ("ProgramFiles(x86)", @"Microsoft VS Code\Code.exe")
                 })
        {
            string? root = _probe.GetEnvironmentVariable(variable);
            if (!string.IsNullOrEmpty(root)) yield return Path.Combine(root, relative);
        }
    }

    private bool OpenSystemFile(string relativeToWindowsDirectory)
    {
        string? windows = _probe.GetEnvironmentVariable("SystemRoot");
        if (string.IsNullOrEmpty(windows)) return false;
        string path = Path.Combine(windows, relativeToWindowsDirectory);
        return _probe.FileExists(path) && _shell.Open(path);
    }
}

/// <summary>
/// Decides whether a foreground window is a legitimate target for a window action.
/// The desktop and taskbar are *windows* too — and closing the desktop shell
/// window raises the Windows "Shut Down" dialog — so shell windows, invisible
/// windows and Ali Control's own windows are never touched.
/// </summary>
public static class WindowActionGuard
{
    private static readonly HashSet<string> ShellClasses = new(StringComparer.OrdinalIgnoreCase)
    {
        "Progman",
        "WorkerW",
        "Shell_TrayWnd",
        "Shell_SecondaryTrayWnd",
        "NotifyIconOverflowWindow",
        "TopLevelWindowForOverflowXamlIsland",
        "#32768" // a popup menu
    };

    public static bool IsActionable(WindowInfo? window, int ownProcessId) =>
        window is { Handle: not 0, IsVisible: true }
        && window.ProcessId != ownProcessId
        && !ShellClasses.Contains(window.ClassName);
}

/// <summary>
/// Window actions on the foreground window, performed by posting the window's own
/// system command (<c>WM_SYSCOMMAND</c>: the same message its title-bar buttons
/// produce). Close therefore closes the *window* and lets the application run its
/// normal "save changes?" logic — it never force-quits a process. Windows blocks
/// these messages toward elevated windows (UIPI); that surfaces as an error
/// acknowledgement.
///
/// There is no OS-level "fullscreen" on Windows, so <c>fullscreen_toggle</c> is
/// maximize/restore of the foreground window.
/// </summary>
public sealed class WindowController : IWindowControl
{
    private readonly IWindowApi _api;
    private readonly int _ownProcessId;

    public WindowController(IWindowApi api, int ownProcessId)
    {
        _api = api;
        _ownProcessId = ownProcessId;
    }

    public bool MinimizeWindow() => WithForeground((handle, _) => _api.SendSysCommand(handle, SysCommand.Minimize));

    public bool CloseWindow() => WithForeground((handle, _) => _api.SendSysCommand(handle, SysCommand.Close));

    public bool FullscreenToggle() => WithForeground((handle, window) =>
        _api.SendSysCommand(handle, window.IsMaximized ? SysCommand.Restore : SysCommand.Maximize));

    private bool WithForeground(Func<nint, WindowInfo, bool> action)
    {
        WindowInfo? window = _api.GetForegroundWindow();
        return WindowActionGuard.IsActionable(window, _ownProcessId) && action(window!.Handle, window);
    }
}
