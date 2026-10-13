using AliControlWindows.App;

namespace AliControlWindows;

internal static class Program
{
    /// <summary>One agent per user session: two advertisers would fight over the Bluetooth adapter.</summary>
    private const string SingleInstanceMutexName = @"Local\AliControl.Windows.Agent";

    /// <summary>Signalled by <c>AliControl.exe --quit</c> to ask the running agent to stop cleanly (same as the tray's Quit).</summary>
    private const string QuitEventName = @"Local\AliControl.Windows.Agent.Quit";

    [STAThread]
    private static int Main(string[] args)
    {
        using var mutex = new Mutex(initiallyOwned: true, SingleInstanceMutexName, out bool isFirstInstance);

        if (args.Contains("--quit", StringComparer.OrdinalIgnoreCase))
        {
            return SignalQuit();
        }
        if (args.Contains("--diagnose", StringComparer.OrdinalIgnoreCase))
        {
            return RunDiagnostics(isFirstInstance, showDialog: !args.Contains("--no-dialog", StringComparer.OrdinalIgnoreCase));
        }
        if (!isFirstInstance) return 0;

        ApplicationConfiguration.Initialize();

        var log = new AgentLog();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => log.Info($"unhandled UI exception: {e.Exception.GetType().Name}: {e.Exception.Message}");
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            log.Info($"unhandled exception: {(e.ExceptionObject as Exception)?.GetType().Name ?? "?"}");
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            log.Info($"unobserved task exception: {e.Exception.GetBaseException().GetType().Name}");
            e.SetObserved();
        };

        var host = new AgentHost(log);
        using var tray = new TrayApplicationContext(host, log);

        using var quitEvent = new EventWaitHandle(false, EventResetMode.AutoReset, QuitEventName);
        RegisteredWaitHandle registration = ThreadPool.RegisterWaitForSingleObject(
            quitEvent, (_, _) => tray.RequestQuit(), null, Timeout.Infinite, executeOnlyOnce: true);

        // Start the agent once the message loop is pumping, so the tray icon appears immediately.
        _ = Task.Run(tray.StartAgentAsync);
        Application.Run(tray);
        registration.Unregister(null);
        return 0;
    }

    private static int SignalQuit()
    {
        if (!EventWaitHandle.TryOpenExisting(QuitEventName, out EventWaitHandle? quitEvent)) return 1; // not running
        using (quitEvent) quitEvent.Set();
        return 0;
    }

    private static int RunDiagnostics(bool isFirstInstance, bool showDialog)
    {
        ApplicationConfiguration.Initialize();
        if (!isFirstInstance)
        {
            MessageBox.Show(
                "Ali Control is already running. Quit it from the tray icon first, then run the diagnostics again.\n\n" +
                "(The diagnostics start a test advertisement, which would conflict with the running agent.)",
                "Ali Control diagnostics", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 1;
        }

        string report = Diagnostics.RunAsync().GetAwaiter().GetResult();
        Console.Out.Write(report); // visible when launched from a terminal that AttachConsole'd; harmless otherwise
        if (showDialog)
        {
            MessageBox.Show(
                report + $"\nSaved to: {Diagnostics.ReportPath}",
                "Ali Control diagnostics", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        return 0;
    }
}
