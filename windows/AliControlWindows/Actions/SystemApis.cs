using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace AliControlWindows.Actions;

/// <summary>Injects one key chord (all keys down in order, then all up in reverse) as a single atomic <c>SendInput</c> call.</summary>
public interface IKeyboardInput
{
    /// <summary>False if Windows refused the input (for example the foreground window is elevated and this process is not — UIPI).</summary>
    bool SendChord(IReadOnlyList<VirtualKey> keys);
}

/// <summary>Opens a *fixed* target (an executable path or a URI scheme the agent chose) through the shell. Never takes arguments.</summary>
public interface IShell
{
    bool Open(string target);
}

/// <summary>Read-only questions about this machine that fixed app launches depend on.</summary>
public interface ISystemProbe
{
    bool FileExists(string path);
    string? GetEnvironmentVariable(string name);

    /// <summary>The path registered under HKCU/HKLM <c>App Paths\&lt;exeName&gt;</c>, if any.</summary>
    string? ReadAppPath(string exeName);

    /// <summary>Whether a URI scheme (for example <c>spotify</c>) has a registered handler — probing first avoids the shell's "get an app to open this link" dialog.</summary>
    bool IsUriSchemeRegistered(string scheme);

    /// <summary>The executable of the user's default <c>https</c> handler, resolved by the shell's own association API.</summary>
    string? DefaultBrowserExecutable();
}

/// <summary>Power-related system calls (lock, sleep, display off).</summary>
public interface IPowerControl
{
    bool LockWorkStation();

    /// <summary>
    /// Requests sleep shortly *after* returning, so the acknowledgement can still
    /// reach the tablet before the Bluetooth radio suspends. The call cannot know
    /// whether the OS ultimately honours it.
    /// </summary>
    bool ScheduleSleep();

    bool DisplayOff();
}

public sealed record WindowInfo(nint Handle, string ClassName, int ProcessId, bool IsVisible, bool IsMaximized);

public enum SysCommand
{
    Minimize,
    Maximize,
    Restore,
    Close
}

/// <summary>Foreground-window operations, expressed as the window's own system commands (never process termination).</summary>
public interface IWindowApi
{
    WindowInfo? GetForegroundWindow();
    bool SendSysCommand(nint handle, SysCommand command);
}

/// <summary><c>SendInput</c> keyboard injection (user-mode, no special permission, no administrator rights).</summary>
internal sealed class SendInputKeyboard : IKeyboardInput
{
    private const uint InputKeyboard = 1;
    private const uint KeyEventExtendedKey = 0x0001;
    private const uint KeyEventKeyUp = 0x0002;
    private const uint MapVkToVsc = 0;

    public bool SendChord(IReadOnlyList<VirtualKey> keys)
    {
        if (keys.Count == 0) return false;

        var inputs = new Input[keys.Count * 2];
        for (int i = 0; i < keys.Count; i++)
        {
            inputs[i] = KeyEvent(keys[i], keyUp: false);
            inputs[keys.Count + i] = KeyEvent(keys[keys.Count - 1 - i], keyUp: true);
        }
        uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
        return sent == inputs.Length;
    }

    private static Input KeyEvent(VirtualKey key, bool keyUp)
    {
        uint flags = (keyUp ? KeyEventKeyUp : 0) | (key == VirtualKey.LeftWindows ? KeyEventExtendedKey : 0);
        return new Input
        {
            type = InputKeyboard,
            u = new InputUnion
            {
                ki = new KeyboardInput
                {
                    wVk = (ushort)key,
                    wScan = (ushort)MapVirtualKeyW((uint)key, MapVkToVsc),
                    dwFlags = flags
                }
            }
        };
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint type;
        public InputUnion u;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MouseInput mi;
        [FieldOffset(0)] public KeyboardInput ki;
        [FieldOffset(0)] public HardwareInput hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HardwareInput
    {
        public uint uMsg;
        public ushort wParamL;
        public ushort wParamH;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, [In] Input[] inputs, int size);

    [DllImport("user32.dll")]
    private static extern uint MapVirtualKeyW(uint code, uint mapType);
}

/// <summary><c>Process.Start</c> with <c>UseShellExecute</c> for fixed targets. Failure (not installed, no handler) is a clean <c>false</c>.</summary>
internal sealed class WindowsShell : IShell
{
    public bool Open(string target)
    {
        if (string.IsNullOrWhiteSpace(target)) return false;
        try
        {
            using Process? process = Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
            return true;
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException or FileNotFoundException or PlatformNotSupportedException)
        {
            return false;
        }
    }
}

internal sealed class SystemProbe : ISystemProbe
{
    public bool FileExists(string path) => File.Exists(path);

    public string? GetEnvironmentVariable(string name) => Environment.GetEnvironmentVariable(name);

    public string? ReadAppPath(string exeName)
    {
        string subKey = $@"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\{exeName}";
        foreach (RegistryKey root in new[] { Registry.CurrentUser, Registry.LocalMachine })
        {
            try
            {
                using RegistryKey? key = root.OpenSubKey(subKey);
                if (key?.GetValue(null) is string path && !string.IsNullOrWhiteSpace(path)) return path.Trim('"');
            }
            catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
            {
                // unreadable key = not found
            }
        }
        return null;
    }

    public bool IsUriSchemeRegistered(string scheme)
    {
        try
        {
            using RegistryKey? key = Registry.ClassesRoot.OpenSubKey($@"{scheme}\shell\open\command");
            return key?.GetValue(null) is string { Length: > 0 };
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
            return false;
        }
    }

    public string? DefaultBrowserExecutable()
    {
        const uint AssocfIsProtocol = 0x00001000;
        const int AssocstrExecutable = 2;
        var buffer = new StringBuilder(1024);
        uint length = (uint)buffer.Capacity;
        int hr = AssocQueryStringW(AssocfIsProtocol, AssocstrExecutable, "https", "open", buffer, ref length);
        return hr == 0 && buffer.Length > 0 ? buffer.ToString() : null;
    }

    [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
    private static extern int AssocQueryStringW(uint flags, int str, string assoc, string? extra, StringBuilder output, ref uint outputLength);
}

internal sealed class WindowsPowerControl : IPowerControl
{
    private const uint WmSysCommand = 0x0112;
    private const nint ScMonitorPower = 0xF170;
    private const nint MonitorOff = 2;
    private const uint SmtoAbortIfHung = 0x0002;

    public bool LockWorkStation() => LockWorkStationNative();

    public bool ScheduleSleep()
    {
        _ = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(1)).ConfigureAwait(false);
            // hibernate=false, force=false, wakeupEventsDisabled=false: a normal sleep.
            SetSuspendState(false, false, false);
        });
        return true;
    }

    public bool DisplayOff() =>
        SendMessageTimeoutW(GetDesktopWindow(), WmSysCommand, ScMonitorPower, MonitorOff, SmtoAbortIfHung, 1000, out _) != IntPtr.Zero;

    [DllImport("user32.dll", EntryPoint = "LockWorkStation", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LockWorkStationNative();

    [DllImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.U1)]
    private static extern bool SetSuspendState([MarshalAs(UnmanagedType.U1)] bool hibernate, [MarshalAs(UnmanagedType.U1)] bool force, [MarshalAs(UnmanagedType.U1)] bool disableWakeEvent);

    [DllImport("user32.dll")]
    private static extern IntPtr GetDesktopWindow();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SendMessageTimeoutW(IntPtr hWnd, uint msg, nint wParam, nint lParam, uint flags, uint timeoutMs, out IntPtr result);
}

internal sealed class Win32WindowApi : IWindowApi
{
    private const uint WmSysCommand = 0x0112;
    private const uint GaRoot = 2;

    public WindowInfo? GetForegroundWindow()
    {
        IntPtr hwnd = GetForegroundWindowNative();
        if (hwnd == IntPtr.Zero) return null;
        IntPtr root = GetAncestor(hwnd, GaRoot);
        if (root != IntPtr.Zero) hwnd = root;

        var className = new StringBuilder(256);
        if (GetClassNameW(hwnd, className, className.Capacity) == 0) return null;
        GetWindowThreadProcessId(hwnd, out uint processId);
        return new WindowInfo(hwnd, className.ToString(), (int)processId, IsWindowVisible(hwnd), IsZoomed(hwnd));
    }

    public bool SendSysCommand(nint handle, SysCommand command)
    {
        nint wParam = command switch
        {
            SysCommand.Minimize => 0xF020,
            SysCommand.Maximize => 0xF030,
            SysCommand.Restore => 0xF120,
            SysCommand.Close => 0xF060,
            _ => 0
        };
        return wParam != 0 && PostMessageW(handle, WmSysCommand, wParam, 0);
    }

    [DllImport("user32.dll", EntryPoint = "GetForegroundWindow")]
    private static extern IntPtr GetForegroundWindowNative();

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassNameW(IntPtr hwnd, StringBuilder className, int maxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr hwnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsZoomed(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessageW(IntPtr hwnd, uint msg, nint wParam, nint lParam);
}
