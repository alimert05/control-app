using System.Text;

namespace AliControlWindows.App;

/// <summary>
/// Concise, event-driven diagnostics: Bluetooth state, advertising, tablet
/// connect/disconnect, unparseable payloads, command failures. Nothing is logged
/// on a timer, and nothing sensitive is written — only action ids and sizes,
/// never protocol payload content, track metadata or the machine name.
///
/// Written to <c>%LOCALAPPDATA%\AliControl\agent.log</c>, capped at 256 KiB with
/// one rotated generation (<c>agent.log.1</c>). A tray application has no console,
/// so a file is the only place BLE problems can be diagnosed from.
/// </summary>
public sealed class AgentLog
{
    private const long MaxBytes = 256 * 1024;
    private readonly object _gate = new();
    private readonly string _path;

    public AgentLog(string? path = null)
    {
        _path = path ?? DefaultPath;
    }

    public static string DefaultPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AliControl", "agent.log");

    public string FilePath => _path;

    public void Info(string message)
    {
        lock (_gate)
        {
            try
            {
                string? directory = Path.GetDirectoryName(_path);
                if (directory != null) Directory.CreateDirectory(directory);

                if (File.Exists(_path) && new FileInfo(_path).Length > MaxBytes)
                {
                    File.Move(_path, _path + ".1", overwrite: true);
                }
                File.AppendAllText(_path, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}  {message}{Environment.NewLine}", Encoding.UTF8);
            }
            catch
            {
                // Logging must never be able to break the agent.
            }
        }
    }
}
