using System.Diagnostics;
using System.Text;
using AliControlWindows.BLE;
using AliControlWindows.Media;
using AliControlWindows.Telemetry;
using Windows.Devices.Bluetooth;
using Windows.Devices.Radios;

namespace AliControlWindows.App;

/// <summary>
/// <c>AliControl.exe --diagnose</c>: a one-shot, read-mostly health report for
/// first-time setup and troubleshooting, so a BLE problem can be understood without
/// a tablet in hand. It runs the same probes the agent runs at startup, briefly
/// starts (and cleanly stops) a real advertisement for each service candidate, and
/// writes <c>%LOCALAPPDATA%\AliControl\diagnostics.txt</c>.
///
/// It changes no system setting, installs nothing, and exits by itself. It must not
/// run alongside the normal agent (two advertisers would fight over the adapter), so
/// <see cref="Program"/> refuses to start it while the agent is running.
/// </summary>
internal static class Diagnostics
{
    public static string ReportPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AliControl", "diagnostics.txt");

    public static async Task<string> RunAsync()
    {
        var report = new StringBuilder();
        void Line(string text = "") => report.AppendLine(text);

        Line("Ali Control for Windows — diagnostics");
        Line($"Time:     {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
        Line($"OS:       {Environment.OSVersion.VersionString} ({(Environment.Is64BitOperatingSystem ? "x64" : "x86")})");
        Line($"Runtime:  {Environment.Version}");
        Line($"Elevated: {IsElevated()}  (the agent is designed to run as a standard user)");
        Line();

        Line("Bluetooth");
        try
        {
            BluetoothAdapter? adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null)
            {
                Line("  adapter:                 none found");
            }
            else
            {
                Line($"  low energy supported:    {adapter.IsLowEnergySupported}");
                Line($"  peripheral role:         {adapter.IsPeripheralRoleSupported}  (required)");
                Line($"  central role:            {adapter.IsCentralRoleSupported}");
                Line($"  extended advertising:    {adapter.IsExtendedAdvertisingSupported}");
            }
            foreach (Radio radio in (await Radio.GetRadiosAsync()).Where(r => r.Kind == RadioKind.Bluetooth))
            {
                Line($"  radio '{radio.Name}':    {radio.State}");
            }
        }
        catch (Exception e)
        {
            Line($"  failed to query Bluetooth: {e.GetType().Name}: {e.Message}");
        }
        Line();

        Line("Capabilities the agent would advertise to the tablet");
        Line($"  actions:                 yes");
        Line($"  telemetry.cpu:           {(new NativeCpuTicksSource().Read() != null ? "yes" : "no")}");
        Line($"  telemetry.memory:        {(new NativeMemoryStatusSource().Read() != null ? "yes" : "no")}");
        Line($"  telemetry.gpu:           {(await new GpuUsageSampler().ProbeAsync() ? "yes (PDH GPU Engine counters)" : "no (counters unavailable)")}");
        Line($"  telemetry.temperature.*: no  (no reliable user-mode API without admin rights or a driver)");
        WindowsMediaSessionSource media = await WindowsMediaSessionSource.CreateAsync();
        Line($"  media / media.artwork:   {(media.IsAvailable ? "yes" : "no (media session service unreachable)")}");
        if (media.IsAvailable)
        {
            IReadOnlyList<MediaState> sessions = await media.GetSessionsAsync();
            Line($"  media sessions right now: {sessions.Count}");
            foreach (MediaState s in sessions)
            {
                Line($"    - {s.Source}: {s.PlaybackState}{(s.HasTrack ? ", has a track" : "")}");
            }
        }
        Line();

        Line("Advertising test (starts a real BLE advertisement for each candidate, then stops it)");
        using var server = new WinRtGattServer();
        foreach (Guid uuid in AliControlBleProfile.AdvertisingCandidates)
        {
            var clock = Stopwatch.StartNew();
            GattStartResult result = await server.StartAsync(uuid, CancellationToken.None);
            clock.Stop();
            string label = uuid == AliControlBleProfile.ServiceUuid ? "full 128-bit service UUID" : "compact discovery UUID   ";
            Line($"  {label}  {(result.Started ? "OK    " : "FAILED")}  {clock.ElapsedMilliseconds,5} ms  {result.Detail}");
            server.Stop();
            await Task.Delay(500); // let the adapter release the advertising slot before the next attempt
        }
        Line();
        Line("If the full UUID FAILED but the compact one is OK, that is normal on some adapters: the agent");
        Line("uses the compact discovery service automatically and the tablet finds it the same way.");
        Line("If both FAILED, check that Bluetooth is on and that no other app is advertising.");

        string text = report.ToString();
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ReportPath)!);
            File.WriteAllText(ReportPath, text, Encoding.UTF8);
        }
        catch
        {
            // reporting must never fail the run
        }
        return text;
    }

    private static bool IsElevated()
    {
        using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
        return new System.Security.Principal.WindowsPrincipal(identity).IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
    }
}
