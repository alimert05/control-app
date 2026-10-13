namespace AliControlWindows.Media;

/// <summary>
/// Converts the raw timeline a Windows media session reports into the
/// (position, duration) pair the protocol carries.
///
/// A session reports its position as of <c>LastUpdatedTime</c>, not as of now,
/// so while playing the elapsed time since that update (scaled by the playback
/// rate) is added — otherwise the tablet's progress bar would lag by however
/// long ago the player last pushed a timeline.
/// </summary>
public static class MediaTimeline
{
    public readonly record struct Result(double? PositionSeconds, double? DurationSeconds);

    public static Result Compute(
        TimeSpan start,
        TimeSpan end,
        TimeSpan position,
        DateTimeOffset lastUpdated,
        DateTimeOffset now,
        bool isPlaying,
        double? playbackRate)
    {
        double length = (end - start).TotalSeconds;
        // Live streams and apps that publish no timeline report an empty or
        // inverted range: no duration, and a position would be meaningless.
        if (!double.IsFinite(length) || length <= 0) return new Result(null, null);

        double elapsed = (position - start).TotalSeconds;
        if (isPlaying && lastUpdated != default && now > lastUpdated)
        {
            double rate = playbackRate is > 0 and < 16 ? playbackRate.Value : 1d;
            elapsed += (now - lastUpdated).TotalSeconds * rate;
        }
        if (!double.IsFinite(elapsed)) return new Result(null, length);
        return new Result(Math.Clamp(elapsed, 0d, length), length);
    }
}
