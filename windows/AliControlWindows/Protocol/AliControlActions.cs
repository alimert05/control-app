namespace AliControlWindows.Protocol;

/// <summary>
/// The explicit allowlist of action IDs this agent supports. The strings must
/// match the Android tablet's <c>AliControlCommands.kt</c> /
/// <c>ActionLibrary.kt</c> exactly — they are the platform-independent
/// protocol both sides agree on.
///
/// Stable IDs are kept wherever the Windows behaviour is the same product
/// feature. Four IDs that existed while macOS was the reference platform were
/// replaced by their Windows equivalents (the tablet migrates saved layouts
/// automatically — see docs/ANDROID_CLIENT.md): <c>windows_search</c>,
/// <c>task_view</c>, <c>open_explorer</c> and <c>open_task_manager</c>.
///
/// Nothing outside this list is ever executed: there is no generic command
/// path, no shell text, and no application path supplied by the tablet.
/// </summary>
public static class AliControlActions
{
    /// <summary>Protocol-level liveness check; acknowledged, executes nothing.</summary>
    public const string Ping = "ping";

    // Media
    public const string PlayPause = "play_pause";
    public const string NextTrack = "next_track";
    public const string PreviousTrack = "previous_track";
    public const string VolumeUp = "volume_up";
    public const string VolumeDown = "volume_down";
    public const string MuteToggle = "mute_toggle";

    // System
    public const string LockScreen = "lock_screen";
    public const string Sleep = "sleep";
    public const string DisplaySleep = "display_sleep";
    public const string Screenshot = "screenshot";
    public const string ShowDesktop = "show_desktop";
    public const string WindowsSearch = "windows_search";
    public const string TaskView = "task_view";

    // Apps
    public const string OpenTerminal = "open_terminal";
    public const string OpenVsCode = "open_vscode";
    public const string OpenExplorer = "open_explorer";
    public const string OpenBrowser = "open_browser";
    public const string OpenSpotify = "open_spotify";
    public const string OpenSystemSettings = "open_system_settings";
    public const string OpenTaskManager = "open_task_manager";

    // Window
    public const string MinimizeWindow = "minimize_window";
    public const string CloseWindow = "close_window";
    public const string FullscreenToggle = "fullscreen_toggle";

    // Productivity
    public const string Copy = "copy";
    public const string Paste = "paste";
    public const string Undo = "undo";
    public const string Redo = "redo";

    /// <summary>Every supported action ID, including <see cref="Ping"/>.</summary>
    public static readonly IReadOnlyList<string> All = new[]
    {
        Ping,
        PlayPause, NextTrack, PreviousTrack, VolumeUp, VolumeDown, MuteToggle,
        LockScreen, Sleep, DisplaySleep, Screenshot, ShowDesktop, WindowsSearch, TaskView,
        OpenTerminal, OpenVsCode, OpenExplorer, OpenBrowser, OpenSpotify, OpenSystemSettings, OpenTaskManager,
        MinimizeWindow, CloseWindow, FullscreenToggle,
        Copy, Paste, Undo, Redo
    };
}
