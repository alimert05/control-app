using System.Text;
using AliControlWindows.Protocol;

namespace AliControlWindows.Media;

/// <summary>
/// Turns a media session's AppUserModelId — which can be as long and opaque as
/// <c>SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify</c> — into the short,
/// wire-safe source identifier the tablet shows and stores (<c>spotify</c>).
/// </summary>
public static class MediaSourceNames
{
    private static readonly (string Needle, string Name)[] KnownApps =
    {
        ("spotify", "spotify"),
        ("zunemusic", "media_player"),
        ("zunevideo", "movies_tv"),
        ("itunes", "itunes"),
        ("applemusic", "apple_music"),
        ("msedge", "edge"),
        ("chrome", "chrome"),
        ("firefox", "firefox"),
        ("brave", "brave"),
        ("opera", "opera"),
        ("vlc", "vlc"),
        ("tidal", "tidal"),
        ("deezer", "deezer"),
        ("amazonmusic", "amazon_music"),
        ("youtubemusic", "youtube_music"),
        ("foobar", "foobar2000"),
        ("musicbee", "musicbee"),
        ("aimp", "aimp")
    };

    public const string Fallback = "media";

    public static string Normalize(string? appUserModelId)
    {
        if (string.IsNullOrWhiteSpace(appUserModelId)) return Fallback;

        string lower = appUserModelId.Trim().ToLowerInvariant();
        foreach ((string needle, string name) in KnownApps)
        {
            if (lower.Contains(needle, StringComparison.Ordinal)) return name;
        }

        // "Package_family!App" -> "App"; "C:\path\player.exe" -> "player".
        int bang = lower.LastIndexOf('!');
        string candidate = bang >= 0 ? lower[(bang + 1)..] : lower;
        int slash = Math.Max(candidate.LastIndexOf('\\'), candidate.LastIndexOf('/'));
        if (slash >= 0) candidate = candidate[(slash + 1)..];
        if (candidate.EndsWith(".exe", StringComparison.Ordinal)) candidate = candidate[..^4];

        var cleaned = new StringBuilder();
        foreach (char c in candidate)
        {
            if (cleaned.Length >= WireLimits.MaxSourceBytes) break;
            cleaned.Append(c is (>= 'a' and <= 'z') or (>= '0' and <= '9') or '.' or '_' or '-' ? c : '_');
        }
        string result = cleaned.ToString().Trim('_', '.', '-');
        return result.Length == 0 ? Fallback : result;
    }
}
