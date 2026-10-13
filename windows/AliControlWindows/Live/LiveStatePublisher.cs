using AliControlWindows.Artwork;
using AliControlWindows.Media;
using AliControlWindows.Telemetry;

namespace AliControlWindows.Live;

/// <summary>
/// Samples system telemetry and media state on a single low-frequency timer and
/// emits the resulting notification payloads.
///
/// <para><b>Lifecycle.</b> The publisher runs <b>only while a tablet is
/// subscribed</b>: the peripheral starts it on subscribe and stops it on
/// unsubscribe, Bluetooth power-off and quit, so an unattended PC does no
/// telemetry work at all — no timer, no performance counters, no media session
/// reads. There is exactly one loop for the whole app (never one per widget,
/// page or metric). Consecutive sessions never overlap: a new loop waits for the
/// previous one to finish before it resets and primes the samplers.</para>
///
/// <para><b>What gets sent.</b> Metrics every tick that produces any value;
/// playback only when it differs from the last one sent (while a track plays that
/// is every tick because the position advances; while paused or idle it goes
/// quiet on its own); track identity only when the track actually changes — it is
/// the one message carrying unbounded text, so keeping it event-driven stops
/// metadata from dominating the link.</para>
///
/// <para>A session <i>generation</i> number guards every emission, so a tick that
/// was already in flight when the tablet disconnected can never leak a stale
/// message into the next connection.</para>
/// </summary>
public sealed class LiveStatePublisher
{
    /// <summary>Default sampling interval, centralised here so it is one line to change.</summary>
    public static readonly TimeSpan DefaultInterval = TimeSpan.FromSeconds(2);

    private readonly ITelemetryProvider _telemetry;
    private readonly MediaSessionCoordinator _media;
    private readonly ArtworkPipeline _artwork;
    private readonly TimeSpan _interval;
    private readonly Action<string>? _log;

    private readonly object _gate = new();
    private CancellationTokenSource? _cts;
    private Task _loop = Task.CompletedTask;
    private int _generation;

    private byte[]? _lastPlayback;
    private string? _lastTrackIdentity;

    /// <summary>Raised with each encoded notification payload. May be raised from the sampling thread.</summary>
    public event Action<byte[]>? MessageReady;

    /// <summary>
    /// Announces the artwork identity for the newly accepted track; <c>null</c>
    /// means any queued artwork transfer is stale and must be discarded. Raised
    /// *before* the matching track message so a stale transfer is cancelled first.
    /// </summary>
    public event Action<string?>? ArtworkChanged;

    public LiveStatePublisher(
        ITelemetryProvider telemetry,
        MediaSessionCoordinator media,
        ArtworkPipeline artwork,
        TimeSpan? interval = null,
        Action<string>? log = null)
    {
        _telemetry = telemetry;
        _media = media;
        _artwork = artwork;
        _interval = interval ?? DefaultInterval;
        _log = log;
    }

    public bool IsRunning
    {
        get
        {
            lock (_gate) return _cts != null;
        }
    }

    /// <summary>Looks up previously processed artwork by id — what an <c>artwork_request</c> resolves against. Thread-safe.</summary>
    public ProcessedArtwork? ProcessedArtwork(string artworkId) => _artwork.Lookup(artworkId);

    /// <summary>Begins sampling. A duplicate call while running is a no-op, so a repeated subscribe callback can never create a second sampler.</summary>
    public void Start()
    {
        lock (_gate)
        {
            if (_cts != null) return;
            var cts = new CancellationTokenSource();
            _cts = cts;
            int generation = ++_generation;
            Task previous = _loop;
            _loop = Task.Run(() => RunAsync(previous, generation, cts.Token));
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            if (_cts == null) return;
            _generation++;
            _cts.Cancel();
            _cts = null;
        }
    }

    /// <summary>Completes once the sampling loop has fully stopped (tests and orderly shutdown).</summary>
    public Task StopAndWaitAsync()
    {
        Stop();
        Task loop;
        lock (_gate) loop = _loop;
        return loop;
    }

    private async Task RunAsync(Task previous, int generation, CancellationToken token)
    {
        try
        {
            try { await previous.ConfigureAwait(false); } catch { /* the previous session's failure is not ours */ }

            // A fresh session: forget what the previous tablet was told, so a
            // reconnecting one receives the full current state rather than being
            // diffed against a stale baseline.
            ResetSession();
            // CPU/GPU usage are deltas: prime a baseline now so the first real
            // tick reports the interval since this moment instead of nothing.
            _telemetry.Sample();

            using var timer = new PeriodicTimer(_interval);
            while (await timer.WaitForNextTickAsync(token).ConfigureAwait(false))
            {
                try
                {
                    await TickCoreAsync(generation, token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (token.IsCancellationRequested)
                {
                    break;
                }
                catch (Exception e)
                {
                    _log?.Invoke($"live-state tick failed: {e.GetType().Name}: {e.Message}");
                }
            }
        }
        catch (OperationCanceledException)
        {
            // normal stop
        }
        finally
        {
            ResetSession();
        }
    }

    private void ResetSession()
    {
        _lastPlayback = null;
        _lastTrackIdentity = null;
        _media.Reset();
        _telemetry.Reset();
    }

    /// <summary>One sampling pass against the current session. Exposed so tests can drive it directly instead of waiting on wall-clock time.</summary>
    public Task TickAsync(CancellationToken cancellationToken = default)
    {
        int generation;
        lock (_gate) generation = _generation;
        return TickCoreAsync(generation, cancellationToken);
    }

    private bool IsCurrent(int generation)
    {
        lock (_gate) return generation == _generation;
    }

    private void Emit(int generation, byte[] payload)
    {
        if (IsCurrent(generation)) MessageReady?.Invoke(payload);
    }

    private async Task TickCoreAsync(int generation, CancellationToken cancellationToken)
    {
        SystemMetrics metrics = _telemetry.Sample();
        if (metrics.HasAnyValue) Emit(generation, LiveStateMessages.Metrics(metrics));

        MediaState? state = await _media.CurrentStateAsync(cancellationToken).ConfigureAwait(false);
        if (!IsCurrent(generation)) return;

        if (state is { HasTrack: true } && state.TrackIdentity != _lastTrackIdentity)
        {
            // Artwork is fetched/processed here — once per *track change*, never
            // once per tick — and only the resulting id (if any) rides along in the
            // track message. The image bytes are never pushed; the tablet requests
            // them separately, and only on a cache miss.
            ArtworkPipeline.Result artwork = await _artwork.GetArtworkAsync(state, _media, cancellationToken).ConfigureAwait(false);
            if (!IsCurrent(generation)) return;
            if (artwork.Kind == ArtworkPipeline.ResultKind.TrackChanged)
            {
                // The session advanced while its artwork was being read. Publish
                // neither the stale track nor stale playback; the next normal tick
                // samples the new track coherently.
                return;
            }

            string? artworkId = artwork.Kind == ArtworkPipeline.ResultKind.Ready ? artwork.Artwork!.ArtworkId : null;
            _lastTrackIdentity = state.TrackIdentity;
            ArtworkChanged?.Invoke(artworkId);
            Emit(generation, LiveStateMessages.Track(state, artworkId));
        }
        else if (state is not { HasTrack: true })
        {
            if (_lastTrackIdentity != null) ArtworkChanged?.Invoke(null);
            _lastTrackIdentity = null;
        }

        byte[] playback = LiveStateMessages.Playback(state);
        if (_lastPlayback == null || !playback.AsSpan().SequenceEqual(_lastPlayback))
        {
            _lastPlayback = playback;
            Emit(generation, playback);
        }
    }
}
