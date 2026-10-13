namespace AliControlWindows.Artwork;

/// <summary>
/// A small, bounded LRU cache of processed artwork, with two independent indexes:
///
/// <list type="bullet">
/// <item><b>by track identity</b> — so revisiting a track within a session never
/// re-runs the fetch + resize + compress pipeline (the expensive part), and so a
/// track with <i>no</i> artwork is asked about only once;</item>
/// <item><b>by artworkId</b> — what an <c>artwork_request</c> actually looks up,
/// so previously seen artwork can be re-sent without touching the media session
/// again.</item>
/// </list>
///
/// The dataset is tiny (a handful of ≤20 KiB entries) and accessed rarely, so
/// plain lock-guarded dictionaries plus an access-order list are plenty. It is
/// synchronised because the live-state loop populates it while the BLE command
/// path reads it.
/// </summary>
public sealed class ArtworkStore
{
    private readonly int _capacity;
    private readonly object _gate = new();

    private readonly Dictionary<string, ProcessedArtwork> _artworkById = new();
    private readonly List<string> _artworkIdOrder = new();

    // A null value is meaningful: "checked, confirmed no artwork".
    private readonly Dictionary<string, ProcessedArtwork?> _byTrackIdentity = new();
    private readonly List<string> _trackIdentityOrder = new();

    public ArtworkStore(int capacity = 8) => _capacity = capacity;

    /// <summary>Stores under both its own id and the track it belongs to, evicting the least-recently-used entry of each kind when over capacity.</summary>
    public void Store(ProcessedArtwork artwork, string trackIdentity)
    {
        lock (_gate)
        {
            Insert(_artworkById, _artworkIdOrder, artwork.ArtworkId, artwork);
            Insert(_byTrackIdentity, _trackIdentityOrder, trackIdentity, artwork);
        }
    }

    /// <summary>Remembers that <paramref name="trackIdentity"/> was checked and has no artwork, so it is never re-fetched.</summary>
    public void StoreNoArtwork(string trackIdentity)
    {
        lock (_gate)
        {
            Insert(_byTrackIdentity, _trackIdentityOrder, trackIdentity, null);
        }
    }

    /// <summary>Not checked yet → false. Checked: true, with <paramref name="artwork"/> null when confirmed none.</summary>
    public bool TryGetCachedResult(string trackIdentity, out ProcessedArtwork? artwork)
    {
        lock (_gate)
        {
            if (!_byTrackIdentity.TryGetValue(trackIdentity, out artwork)) return false;
            Touch(_trackIdentityOrder, trackIdentity);
            return true;
        }
    }

    public ProcessedArtwork? Find(string artworkId)
    {
        lock (_gate)
        {
            if (!_artworkById.TryGetValue(artworkId, out ProcessedArtwork? artwork)) return null;
            Touch(_artworkIdOrder, artworkId);
            return artwork;
        }
    }

    public IReadOnlyList<string> CachedArtworkIds
    {
        get
        {
            lock (_gate) return _artworkIdOrder.ToArray();
        }
    }

    private void Insert<T>(Dictionary<string, T> map, List<string> order, string key, T value)
    {
        if (!map.ContainsKey(key) && order.Count >= _capacity && order.Count > 0)
        {
            string oldest = order[0];
            order.RemoveAt(0);
            map.Remove(oldest);
        }
        map[key] = value;
        Touch(order, key);
    }

    private static void Touch(List<string> order, string key)
    {
        order.Remove(key);
        order.Add(key);
    }
}
