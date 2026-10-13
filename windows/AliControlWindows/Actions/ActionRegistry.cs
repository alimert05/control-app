using AliControlWindows.Protocol;

namespace AliControlWindows.Actions;

/// <summary>
/// One entry in the action allowlist: an action ID mapped to how it is executed.
/// <paramref name="UsesInputSimulation"/> is metadata mirroring the tablet's
/// <c>PermissionRequirement.INPUT_SIMULATION</c>: the action works by injecting
/// keyboard input, which Windows refuses toward elevated windows.
/// </summary>
public sealed record ActionHandler(string Id, bool UsesInputSimulation, Func<bool> Execute);

/// <summary>
/// Builds and owns the fixed set of supported action handlers.
///
/// This is a strict allowlist: nothing outside <see cref="Handlers"/> is ever
/// reachable. <see cref="Find"/> returns null for any ID not explicitly registered
/// here and there is no fallback or default path that executes anything — an
/// unrecognised action ID can never run, and no part of an action (path, argument,
/// shell text) ever comes from the tablet. The desktop agent owns all behaviour.
/// </summary>
public sealed class ActionRegistry
{
    private readonly IReadOnlyDictionary<string, ActionHandler> _handlers;

    public ActionRegistry(
        IMediaControl media,
        ISystemControl system,
        IAppLauncher apps,
        IWindowControl window,
        IProductivityControl productivity)
    {
        _handlers = Build(media, system, apps, window, productivity);
    }

    /// <summary>All registered action IDs — used by tests to cross-check completeness against <see cref="AliControlActions.All"/>.</summary>
    public IReadOnlyCollection<string> RegisteredActionIds => _handlers.Keys.ToArray();

    public IReadOnlyCollection<ActionHandler> Handlers => _handlers.Values.ToArray();

    public ActionHandler? Find(string actionId) => _handlers.GetValueOrDefault(actionId);

    private static IReadOnlyDictionary<string, ActionHandler> Build(
        IMediaControl media,
        ISystemControl system,
        IAppLauncher apps,
        IWindowControl window,
        IProductivityControl productivity)
    {
        var entries = new List<ActionHandler>();
        void Add(string id, Func<bool> execute, bool inputSimulation = false) =>
            entries.Add(new ActionHandler(id, inputSimulation, execute));

        Add(AliControlActions.Ping, () => true);

        // Media — system media keys, not focus-dependent
        Add(AliControlActions.PlayPause, media.PlayPause);
        Add(AliControlActions.NextTrack, media.NextTrack);
        Add(AliControlActions.PreviousTrack, media.PreviousTrack);
        Add(AliControlActions.VolumeUp, media.VolumeUp);
        Add(AliControlActions.VolumeDown, media.VolumeDown);
        Add(AliControlActions.MuteToggle, media.MuteToggle);

        // System
        Add(AliControlActions.LockScreen, system.LockScreen);
        Add(AliControlActions.Sleep, system.Sleep);
        Add(AliControlActions.DisplaySleep, system.DisplaySleep);
        Add(AliControlActions.Screenshot, system.Screenshot, inputSimulation: true);
        Add(AliControlActions.ShowDesktop, system.ShowDesktop, inputSimulation: true);
        Add(AliControlActions.WindowsSearch, system.WindowsSearch, inputSimulation: true);
        Add(AliControlActions.TaskView, system.TaskView, inputSimulation: true);

        // Apps
        Add(AliControlActions.OpenTerminal, apps.OpenTerminal);
        Add(AliControlActions.OpenVsCode, apps.OpenVsCode);
        Add(AliControlActions.OpenExplorer, apps.OpenExplorer);
        Add(AliControlActions.OpenBrowser, apps.OpenBrowser);
        Add(AliControlActions.OpenSpotify, apps.OpenSpotify);
        Add(AliControlActions.OpenSystemSettings, apps.OpenSystemSettings);
        Add(AliControlActions.OpenTaskManager, apps.OpenTaskManager);

        // Window — the foreground window's own system commands
        Add(AliControlActions.MinimizeWindow, window.MinimizeWindow);
        Add(AliControlActions.CloseWindow, window.CloseWindow);
        Add(AliControlActions.FullscreenToggle, window.FullscreenToggle);

        // Productivity — edit shortcuts on the focused window
        Add(AliControlActions.Copy, productivity.Copy, inputSimulation: true);
        Add(AliControlActions.Paste, productivity.Paste, inputSimulation: true);
        Add(AliControlActions.Undo, productivity.Undo, inputSimulation: true);
        Add(AliControlActions.Redo, productivity.Redo, inputSimulation: true);

        // ToDictionary throws at construction (effectively at launch) if two
        // entries ever share an ID — a deliberate, loud safety net against a
        // registration mistake. The tests assert completeness explicitly too.
        return entries.ToDictionary(handler => handler.Id, StringComparer.Ordinal);
    }
}
