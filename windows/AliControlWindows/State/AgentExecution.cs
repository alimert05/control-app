using System.Threading.Channels;

namespace AliControlWindows.State;

/// <summary>
/// Serialises all peripheral logic. BLE callbacks arrive on arbitrary thread-pool
/// threads, but the connection state machine is a plain, single-threaded object:
/// every event is posted here and handled one at a time, in order, so no lock is
/// needed inside it and events can never interleave.
/// </summary>
public interface IAgentExecutor
{
    void Post(Action work);
    void Post(Func<Task> work);
}

/// <summary>Production executor: one consumer loop over an unbounded channel.</summary>
public sealed class SerialAgentExecutor : IAgentExecutor, IAsyncDisposable
{
    private readonly Channel<Func<Task>> _channel = Channel.CreateUnbounded<Func<Task>>(
        new UnboundedChannelOptions { SingleReader = true, AllowSynchronousContinuations = false });
    private readonly Task _loop;
    private readonly Action<string>? _log;

    public SerialAgentExecutor(Action<string>? log = null)
    {
        _log = log;
        _loop = Task.Run(RunAsync);
    }

    public void Post(Action work) => Post(() =>
    {
        work();
        return Task.CompletedTask;
    });

    public void Post(Func<Task> work) => _channel.Writer.TryWrite(work);

    /// <summary>Completes after everything posted so far has been handled.</summary>
    public Task FlushAsync()
    {
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        Post(() =>
        {
            tcs.SetResult();
            return Task.CompletedTask;
        });
        return tcs.Task;
    }

    private async Task RunAsync()
    {
        await foreach (Func<Task> work in _channel.Reader.ReadAllAsync().ConfigureAwait(false))
        {
            try
            {
                await work().ConfigureAwait(false);
            }
            catch (Exception e)
            {
                // One failing event must never take the whole agent down.
                _log?.Invoke($"unhandled error in agent event: {e.GetType().Name}: {e.Message}");
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _channel.Writer.TryComplete();
        await _loop.ConfigureAwait(false);
    }
}

/// <summary>One-shot delayed callbacks (advertising retries, post-resume restarts). Abstracted so tests control time.</summary>
public interface IScheduler
{
    IDisposable Schedule(TimeSpan delay, Action callback);
}

public sealed class TimerScheduler : IScheduler
{
    public IDisposable Schedule(TimeSpan delay, Action callback)
    {
        var cts = new CancellationTokenSource();
        _ = Task.Delay(delay, cts.Token).ContinueWith(
            t =>
            {
                if (!t.IsCanceled) callback();
            },
            CancellationToken.None,
            TaskContinuationOptions.None,
            TaskScheduler.Default);
        return new Cancellation(cts);
    }

    private sealed class Cancellation : IDisposable
    {
        private readonly CancellationTokenSource _cts;
        public Cancellation(CancellationTokenSource cts) => _cts = cts;

        public void Dispose()
        {
            _cts.Cancel();
            _cts.Dispose();
        }
    }
}
