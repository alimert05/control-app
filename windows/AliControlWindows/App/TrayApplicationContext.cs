using AliControlWindows.State;

namespace AliControlWindows.App;

/// <summary>
/// The tray (notification area) UI: an icon reflecting connection state and one
/// context menu — no windows, no persistent UI.
///
/// <code>
/// Ali Control
/// Bluetooth: On
/// Tablet: Not connected | Connected (AA:BB:CC:DD:EE:FF)
/// Advertising: Yes
/// ────────────────
/// ✓ Open at Startup
///   Restart Advertising
/// ────────────────
///   Quit
/// </code>
///
/// Updates are event-driven: the status lines are rewritten when the peripheral
/// reports a change, and the "Open at Startup" check mark is re-read each time the
/// menu opens. Nothing here polls.
/// </summary>
internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly AgentHost _host;
    private readonly AgentLog _log;
    private readonly Control _ui;
    private readonly NotifyIcon _notifyIcon;
    private readonly ContextMenuStrip _menu;
    private readonly TrayIcons _icons;

    private readonly ToolStripMenuItem _bluetoothItem;
    private readonly ToolStripMenuItem _tabletItem;
    private readonly ToolStripMenuItem _advertisingItem;
    private readonly ToolStripMenuItem _startupItem;
    private readonly ToolStripMenuItem _restartItem;
    private bool _exiting;

    public TrayApplicationContext(AgentHost host, AgentLog log)
    {
        _host = host;
        _log = log;

        // A hidden control gives us a UI thread to marshal status changes onto.
        _ui = new Control();
        _ = _ui.Handle;

        _icons = new TrayIcons(SystemInformation.SmallIconSize.Width);

        _menu = new ContextMenuStrip();
        var title = new ToolStripMenuItem("Ali Control") { Enabled = false };
        title.Font = new Font(title.Font, FontStyle.Bold);
        _bluetoothItem = Info();
        _tabletItem = Info();
        _advertisingItem = Info();

        _startupItem = new ToolStripMenuItem("Open at Startup");
        _startupItem.Click += (_, _) => ToggleStartup();
        _restartItem = new ToolStripMenuItem("Restart Advertising");
        _restartItem.Click += (_, _) => _host.Peripheral.RestartAdvertising();
        var quit = new ToolStripMenuItem("Quit");
        quit.Click += (_, _) => Quit();

        _menu.Items.AddRange(new ToolStripItem[]
        {
            title, _bluetoothItem, _tabletItem, _advertisingItem,
            new ToolStripSeparator(),
            _startupItem, _restartItem,
            new ToolStripSeparator(),
            quit
        });
        _menu.Opening += (_, _) => RefreshStartupItem();

        _notifyIcon = new NotifyIcon
        {
            ContextMenuStrip = _menu,
            Icon = _icons.Get(TrayIconKind.Off),
            Text = "Ali Control — starting",
            Visible = true
        };

        Render(new AgentStatus());
        RefreshStartupItem();
    }

    /// <summary>Starts the agent in the background and wires its status into the tray. Called once the message loop is running.</summary>
    public async Task StartAgentAsync()
    {
        try
        {
            await _host.StartAsync();
            _host.Peripheral.StatusChanged += status => PostToUi(() => Render(status));
            PostToUi(() => Render(_host.Peripheral.Status));
        }
        catch (Exception e)
        {
            _log.Info($"agent failed to start: {e.GetType().Name}: {e.Message}");
            PostToUi(() =>
            {
                _notifyIcon.Icon = _icons.Get(TrayIconKind.Problem);
                _notifyIcon.Text = "Ali Control — failed to start (see agent.log)";
                _advertisingItem.Text = "Advertising: failed to start";
                _restartItem.Enabled = false;
            });
        }
    }

    private static ToolStripMenuItem Info() => new() { Enabled = false };

    private void Render(AgentStatus status)
    {
        TrayStatusText text = TrayStatusText.From(status);
        _bluetoothItem.Text = text.Bluetooth;
        _tabletItem.Text = text.Tablet;
        _advertisingItem.Text = text.Advertising;
        _notifyIcon.Icon = _icons.Get(text.Icon);
        // NotifyIcon.Text is limited to 127 characters.
        _notifyIcon.Text = text.Tooltip.Length <= 127 ? text.Tooltip : text.Tooltip[..127];
    }

    private void PostToUi(Action action)
    {
        if (_exiting || _ui.IsDisposed || !_ui.IsHandleCreated) return;
        try
        {
            _ui.BeginInvoke(action);
        }
        catch (InvalidOperationException)
        {
            // The message loop is already shutting down.
        }
    }

    private void RefreshStartupItem()
    {
        _startupItem.Enabled = _host.Startup.CanRegister;
        _startupItem.ToolTipText = _host.Startup.CanRegister
            ? "Start Ali Control when you sign in to Windows"
            : "Available when running AliControl.exe";
        _startupItem.Checked = _host.Startup.IsEnabled;
    }

    private void ToggleStartup()
    {
        bool target = !_host.Startup.IsEnabled;
        bool ok = _host.Startup.SetEnabled(target);
        _log.Info($"open at startup {(target ? "enabled" : "disabled")}: {(ok ? "ok" : "failed")}");
        RefreshStartupItem();
    }

    /// <summary>Asks the running agent to quit cleanly from any thread (used by <c>AliControl.exe --quit</c>).</summary>
    public void RequestQuit() => PostToUi(Quit);

    private void Quit()
    {
        if (_exiting) return;
        _exiting = true;
        _notifyIcon.Visible = false;

        // Stop advertising before the process goes away: an advertisement left behind
        // can block the adapter for the next launch.
        try
        {
            _host.DisposeAsync().AsTask().Wait(TimeSpan.FromSeconds(8));
        }
        catch (Exception e)
        {
            _log.Info($"error while stopping: {e.GetType().Name}");
        }
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
            _menu.Dispose();
            _icons.Dispose();
            _ui.Dispose();
        }
        base.Dispose(disposing);
    }
}
