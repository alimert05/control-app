using System.Text.RegularExpressions;

namespace AliControlWindows.Tests.Security;

/// <summary>
/// A structural audit of the agent's own source, enforcing the project's hard rules
/// mechanically so a future change cannot quietly break them:
///
/// <list type="bullet">
/// <item>nothing except one small wrapper ever starts a process, and never with arguments;</item>
/// <item>no shell interpreter is invoked with a command string;</item>
/// <item>no network listener, client or cloud dependency exists;</item>
/// <item>no elevation, service installation or scheduled task is ever requested.</item>
/// </list>
///
/// Comments are ignored, so documentation can still explain what is *not* done.
/// </summary>
public class SourceAuditTests
{
    private static readonly Lazy<string?> Root = new(FindSourceRoot);

    private static string? FindSourceRoot()
    {
        string? directory = AppContext.BaseDirectory;
        while (directory != null)
        {
            string candidate = Path.Combine(directory, "AliControlWindows");
            if (File.Exists(Path.Combine(candidate, "AliControlWindows.csproj"))) return candidate;
            directory = Path.GetDirectoryName(directory);
        }
        return null;
    }

    private static IEnumerable<(string Path, string Code)> Sources()
    {
        string root = Root.Value ?? throw new InvalidOperationException("agent source tree not found next to the test binaries");
        foreach (string file in Directory.EnumerateFiles(root, "*.cs", SearchOption.AllDirectories))
        {
            if (file.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}") ||
                file.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}")) continue;

            string code = string.Join('\n', File.ReadAllLines(file).Where(line => !line.TrimStart().StartsWith("//")));
            yield return (Path.GetRelativePath(root, file), code);
        }
    }

    private static List<string> Offenders(string pattern) =>
        Sources().Where(s => Regex.IsMatch(s.Code, pattern)).Select(s => s.Path).ToList();

    [Fact]
    public void TheSourceTreeIsFound()
    {
        Assert.NotNull(Root.Value);
        Assert.True(Sources().Count() > 30);
    }

    [Fact]
    public void OnlyTheShellWrapperStartsProcesses()
    {
        Assert.Equal(new[] { Path.Combine("Actions", "SystemApis.cs") }, Offenders(@"Process\.Start\("));
    }

    [Fact]
    public void ProcessesAreNeverStartedWithArgumentsOrAShellInterpreter()
    {
        Assert.Empty(Offenders(@"\.Arguments\b"));
        Assert.Empty(Offenders(@"ArgumentList"));
        Assert.Empty(Offenders(@"(?i)cmd\.exe"));
        Assert.Empty(Offenders(@"(?i)\bcmd /c"));
        Assert.Empty(Offenders(@"(?i)-EncodedCommand|-Command\b"));
        Assert.Empty(Offenders(@"(?i)Invoke-Expression|\biex\b"));
    }

    [Fact]
    public void PowerShellIsOnlyReferencedAsTheFixedTerminalFallback()
    {
        Assert.Equal(new[] { Path.Combine("Actions", "Controllers.cs") }, Offenders(@"(?i)powershell\.exe"));
    }

    [Fact]
    public void LaunchedProcessesAreNeverGivenAnElevationVerb()
    {
        Assert.Empty(Offenders(@"(?i)\.Verb\s*="));
        Assert.Empty(Offenders(@"(?i)""runas"""));
    }

    [Fact]
    public void NoNetworkListenerClientOrCloudDependencyExists()
    {
        Assert.Empty(Offenders(@"System\.Net\.Sockets|\bTcpListener\b|\bTcpClient\b|\bUdpClient\b|\bSocket\b"));
        Assert.Empty(Offenders(@"HttpListener|HttpClient|WebSocket|WebRequest|WebClient|System\.Net\.Http"));
        Assert.Empty(Offenders(@"(?i)https?://"));
    }

    [Fact]
    public void NoServiceInstallationOrScheduledTasks()
    {
        Assert.Empty(Offenders(@"(?i)ServiceController|\bsc\.exe\b|schtasks|TaskScheduler\.Create|CreateService"));
        Assert.Empty(Offenders(@"(?i)RunOnce|\\Services\\"));
    }

    [Fact]
    public void StartupRegistrationTouchesOnlyTheCurrentUsersRunKey()
    {
        Assert.Empty(Offenders(@"Registry\.LocalMachine\.(Create|Set|Delete)"));
        var writers = Sources().Where(s => Regex.IsMatch(s.Code, @"SetValue\(|DeleteValue\(|CreateSubKey\(")).Select(s => s.Path).ToList();
        Assert.Equal(new[] { Path.Combine("App", "StartupRegistration.cs") }, writers);
    }

    [Fact]
    public void TheApplicationManifestRequestsNoElevation()
    {
        string manifest = File.ReadAllText(Path.Combine(Root.Value!, "app.manifest"));
        Assert.Contains("level=\"asInvoker\"", manifest);
        Assert.DoesNotContain("requireAdministrator", manifest);
        Assert.DoesNotContain("highestAvailable", manifest);
        Assert.Contains("uiAccess=\"false\"", manifest);
    }

    [Fact]
    public void NoNativeKeyboardOrMouseHooksAreInstalled()
    {
        // SendInput injects a fixed set of keys; nothing listens to the user's input.
        Assert.Empty(Offenders(@"SetWindowsHookEx|GetAsyncKeyState|GetKeyboardState|RegisterRawInputDevices"));
    }

    [Fact]
    public void NoThirdPartyPackagesAreReferencedByTheAgent()
    {
        string csproj = File.ReadAllText(Path.Combine(Root.Value!, "AliControlWindows.csproj"));
        Assert.DoesNotContain("PackageReference", csproj);
    }

    [Fact]
    public void NoMacOsOrCrossPlatformRuntimeResidueRemains()
    {
        Assert.Empty(Offenders(@"(?i)AppleScript|CoreBluetooth|NSWorkspace|Electron|node\.exe|python"));
    }
}
