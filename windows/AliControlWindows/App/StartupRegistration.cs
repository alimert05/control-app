using Microsoft.Win32;

namespace AliControlWindows.App;

/// <summary>The few registry reads/writes "Open at Startup" needs, abstracted so tests never touch the real registry.</summary>
public interface IStartupStore
{
    string? ReadRunValue(string name);
    void WriteRunValue(string name, string value);
    void DeleteRunValue(string name);

    /// <summary>The Task Manager / Settings "Startup apps" on-off flag for an entry, if one was ever written.</summary>
    byte[]? ReadStartupApproved(string name);
    void DeleteStartupApproved(string name);
}

/// <summary>
/// User-controllable "Open at Startup", using the standard per-user mechanism that
/// Windows itself shows under <i>Settings → Apps → Startup</i> and in Task Manager's
/// <i>Startup apps</i> tab:
///
/// <code>HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run   AliControl = "&lt;path&gt;\AliControl.exe"</code>
///
/// It needs no administrator rights, installs no service, creates no scheduled
/// task, drops no hidden file, and touches nothing outside that one value. It is
/// written only when the user ticks the tray menu item and removed when they
/// untick it; the agent never changes it on its own.
///
/// If the user has switched the entry off from Task Manager (Windows records that
/// separately under <c>Explorer\StartupApproved\Run</c>), it is reported as
/// disabled, and ticking the menu item again clears that flag so the setting the
/// user sees matches what will actually happen at sign-in.
/// </summary>
public sealed class StartupRegistration
{
    public const string ValueName = "AliControl";

    private readonly IStartupStore _store;
    private readonly string? _executablePath;

    public StartupRegistration(IStartupStore store, string? executablePath)
    {
        _store = store;
        _executablePath = executablePath;
    }

    public static StartupRegistration CreateDefault() =>
        new(new RegistryStartupStore(), Environment.ProcessPath);

    /// <summary>
    /// Whether this exact executable will be launched at sign-in. Registration is
    /// only possible from the real <c>AliControl.exe</c> — never when the agent is
    /// being run through the <c>dotnet</c> host, which would register <c>dotnet.exe</c>.
    /// </summary>
    public bool CanRegister =>
        !string.IsNullOrEmpty(_executablePath)
        && string.Equals(Path.GetFileName(_executablePath), "AliControl.exe", StringComparison.OrdinalIgnoreCase);

    public bool IsEnabled
    {
        get
        {
            if (!CanRegister) return false;
            string? value = _store.ReadRunValue(ValueName);
            if (!string.Equals(value, Quoted(_executablePath!), StringComparison.OrdinalIgnoreCase)) return false;

            byte[]? approval = _store.ReadStartupApproved(ValueName);
            // Windows: first byte even (02/06) = enabled, odd (03) = disabled in Startup apps.
            return approval is not { Length: > 0 } || (approval[0] & 1) == 0;
        }
    }

    /// <summary>Returns whether the resulting state matches <paramref name="enabled"/>.</summary>
    public bool SetEnabled(bool enabled)
    {
        if (!CanRegister) return !enabled;
        try
        {
            if (enabled)
            {
                _store.WriteRunValue(ValueName, Quoted(_executablePath!));
                _store.DeleteStartupApproved(ValueName);
            }
            else
            {
                _store.DeleteRunValue(ValueName);
            }
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            return false;
        }
        return IsEnabled == enabled;
    }

    /// <summary>The registry value is the path in double quotes, so a path containing spaces is launched correctly.</summary>
    public static string Quoted(string path) => "\"" + path + "\"";
}

internal sealed class RegistryStartupStore : IStartupStore
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ApprovedKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";

    public string? ReadRunValue(string name)
    {
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue(name) as string;
    }

    public void WriteRunValue(string name, string value)
    {
        using RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
        key.SetValue(name, value, RegistryValueKind.String);
    }

    public void DeleteRunValue(string name)
    {
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
        key?.DeleteValue(name, throwOnMissingValue: false);
    }

    public byte[]? ReadStartupApproved(string name)
    {
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey(ApprovedKey);
        return key?.GetValue(name) as byte[];
    }

    public void DeleteStartupApproved(string name)
    {
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey(ApprovedKey, writable: true);
        key?.DeleteValue(name, throwOnMissingValue: false);
    }
}
