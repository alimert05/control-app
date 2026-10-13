namespace AliControlWindows.Protocol;

/// <summary>
/// Size budget and field limits for desktop→tablet notifications.
///
/// Android negotiates an ATT MTU of 185 right after connecting, which leaves
/// about 182 usable bytes per notification. Track metadata is the only part of
/// the protocol with unbounded length, so it is the only part that could
/// overflow — and a notification that doesn't fit is truncated by the stack,
/// which would hand the tablet half a JSON object, possibly cut through the
/// middle of a UTF-8 sequence.
///
/// Two mechanisms together make that impossible (unchanged from the original
/// protocol design):
///
/// 1. Bounded UTF-8 fields: title/artist/album are truncated on whole grapheme
///    boundaries to a fixed *escaped-byte* budget.
/// 2. Two semantic messages: playback state (small, frequent) is separate from
///    track identity (larger, sent only when the track changes).
///
/// The sender additionally refuses to write any notification larger than the
/// connected client's real maximum notification size.
/// </summary>
public static class WireLimits
{
    /// <summary>Hard cap every live-state notification is designed to stay under (verified by tests).</summary>
    public const int MaxNotificationBytes = 160;

    public const int MaxTitleBytes = 48;
    public const int MaxArtistBytes = 36;
    public const int MaxAlbumBytes = 26;

    /// <summary>Machine name budget in <c>desktop_info</c>.</summary>
    public const int MaxDesktopNameBytes = 24;

    /// <summary>Media source identifier budget (short app id such as "spotify").</summary>
    public const int MaxSourceBytes = 24;

    /// <summary>
    /// Largest inbound (tablet→desktop) payload the agent will even try to parse.
    /// Real commands are ~35-65 bytes; anything larger is malformed or hostile
    /// and is rejected before any JSON parsing.
    /// </summary>
    public const int MaxInboundBytes = 512;
}
