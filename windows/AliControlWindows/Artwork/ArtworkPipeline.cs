using AliControlWindows.Media;

namespace AliControlWindows.Artwork;

/// <summary>
/// Orchestrates artwork for the currently playing track: fetch the raw bytes
/// from the selected media session, process them once, and cache both the outcome
/// (including "this track has none") and the processed result.
///
/// The live-state publisher calls <see cref="GetArtworkAsync"/> <b>only when the
/// track identity changes</b> — never on every 2-second tick — which is what stops
/// a still-playing track from re-reading its thumbnail and re-running JPEG
/// compression over and over.
/// </summary>
public sealed class ArtworkPipeline
{
    public enum ResultKind { Ready, Unavailable, TrackChanged }

    public readonly record struct Result(ResultKind Kind, ProcessedArtwork? Artwork = null)
    {
        public static Result Unavailable => new(ResultKind.Unavailable);
        public static Result TrackChanged => new(ResultKind.TrackChanged);
        public static Result Ready(ProcessedArtwork artwork) => new(ResultKind.Ready, artwork);
    }

    private readonly ArtworkStore _store;
    private readonly Func<byte[], Task<ProcessedArtwork?>> _processor;

    public ArtworkPipeline(ArtworkStore store, Func<byte[], Task<ProcessedArtwork?>> processor)
    {
        _store = store;
        _processor = processor;
    }

    public ArtworkPipeline(ArtworkStore store, ArtworkProcessor processor) : this(store, processor.ProcessAsync) { }

    /// <summary>
    /// The processed artwork for <paramref name="state"/>'s track, fetching and
    /// processing it at most once per distinct track identity. Every outcome —
    /// including "no artwork" and "could not process" — is cached too.
    /// </summary>
    public async Task<Result> GetArtworkAsync(MediaState state, MediaSessionCoordinator coordinator, CancellationToken cancellationToken = default)
    {
        string trackIdentity = state.TrackIdentity;
        if (_store.TryGetCachedResult(trackIdentity, out ProcessedArtwork? cached))
        {
            return cached == null ? Result.Unavailable : Result.Ready(cached);
        }

        MediaSessionCoordinator.ArtworkFetchResult fetched = await coordinator.FetchArtworkAsync(state, cancellationToken).ConfigureAwait(false);
        switch (fetched.Kind)
        {
            case MediaSessionCoordinator.ArtworkFetchKind.TrackChanged:
                return Result.TrackChanged;
            case MediaSessionCoordinator.ArtworkFetchKind.Unavailable:
                _store.StoreNoArtwork(trackIdentity);
                return Result.Unavailable;
        }

        ProcessedArtwork? processed = await _processor(fetched.Data!).ConfigureAwait(false);
        if (processed == null)
        {
            _store.StoreNoArtwork(trackIdentity);
            return Result.Unavailable;
        }
        _store.Store(processed, trackIdentity);
        return Result.Ready(processed);
    }

    /// <summary>
    /// What an <c>artwork_request</c> looks up. Only ever a dictionary read
    /// against already-processed, already-bounded data — never a filesystem path,
    /// never a fresh fetch.
    /// </summary>
    public ProcessedArtwork? Lookup(string artworkId) => _store.Find(artworkId);
}
