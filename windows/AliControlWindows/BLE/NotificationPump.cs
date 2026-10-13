namespace AliControlWindows.BLE;

/// <summary>
/// Delivers a <see cref="PriorityNotificationQueue"/> to the tablet, one
/// notification at a time.
///
/// On Windows, sending a notification is an awaited operation that completes when
/// the stack has handed it to the radio, so awaiting each send <i>is</i> the flow
/// control: the next item is only picked once the previous one has gone out, and
/// the choice between "next acknowledgement" and "next artwork frame" is made fresh
/// every time (see the queue). Nothing busy-loops, nothing is retried in a tight
/// spin, and nothing blocks a UI or protocol thread.
///
/// <list type="bullet">
/// <item><c>Sent</c>: commit and continue.</item>
/// <item><c>Retry</c>: keep the item at the head, wait <c>retryDelay</c>, try again —
/// up to <c>maxAttemptsPerItem</c>, then discard it (pause/resume without ever
/// dropping an item early or skipping past it).</item>
/// <item><c>Dropped</c>: this item cannot be delivered; discard and continue.</item>
/// <item><c>ClientGone</c>: stop; the owner clears the queue.</item>
/// </list>
///
/// Exactly one drain loop runs at a time; <see cref="Kick"/> wakes it whenever
/// something is enqueued. <see cref="Cancel"/> aborts an in-flight send (a
/// disconnect mid-transfer) and makes the pump ready for the next session.
/// </summary>
public sealed class NotificationPump
{
    public delegate Task<NotifyStatus> SendAsync(byte[] data, CancellationToken cancellationToken);

    private readonly PriorityNotificationQueue _queue;
    private readonly SendAsync _send;
    private readonly TimeSpan _retryDelay;
    private readonly int _maxAttemptsPerItem;
    private readonly Action<string>? _log;

    private readonly object _gate = new();
    private CancellationTokenSource _cts = new();
    private int _running;
    private TaskCompletionSource _idle = CreateCompletedIdle();

    public NotificationPump(
        PriorityNotificationQueue queue,
        SendAsync send,
        TimeSpan? retryDelay = null,
        int maxAttemptsPerItem = 10,
        Action<string>? log = null)
    {
        _queue = queue;
        _send = send;
        _retryDelay = retryDelay ?? TimeSpan.FromMilliseconds(100);
        _maxAttemptsPerItem = maxAttemptsPerItem;
        _log = log;
    }

    /// <summary>Raised (from the pump's thread) each time the queue has been fully drained.</summary>
    public event Action? Drained;

    /// <summary>Wakes the pump if it is idle. Safe to call from any thread, any number of times.</summary>
    public void Kick()
    {
        lock (_gate)
        {
            if (_running != 0) return;
            _running = 1;
            _idle = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        _ = Task.Run(RunAsync);
    }

    /// <summary>Aborts any in-flight send and any pending retry. The pump is immediately usable again for the next session.</summary>
    public void Cancel()
    {
        CancellationTokenSource old;
        lock (_gate)
        {
            old = _cts;
            _cts = new CancellationTokenSource();
        }
        old.Cancel();
        old.Dispose();
    }

    /// <summary>Completes when no drain loop is running (tests and orderly shutdown).</summary>
    public Task WhenIdleAsync()
    {
        lock (_gate) return _idle.Task;
    }

    private async Task RunAsync()
    {
        try
        {
            while (true)
            {
                CancellationToken token;
                lock (_gate) token = _cts.Token;

                bool emptied = await DrainAsync(token).ConfigureAwait(false);
                if (emptied) Drained?.Invoke();

                lock (_gate)
                {
                    // Keep going if something was enqueued between the last peek and
                    // now, or if a Cancel() superseded this run while the next
                    // session's items were already waiting (a Kick() during the
                    // unwind would otherwise have been ignored).
                    bool superseded = !token.Equals(_cts.Token);
                    if (!_queue.IsEmpty && (emptied || superseded)) continue;
                    _running = 0;
                    _idle.TrySetResult();
                    return;
                }
            }
        }
        catch (Exception e)
        {
            _log?.Invoke($"notification pump stopped unexpectedly: {e.GetType().Name}: {e.Message}");
            lock (_gate)
            {
                _running = 0;
                _idle.TrySetResult();
            }
        }
    }

    /// <summary>True when the queue was drained; false when stopped early (cancelled, or the client went away).</summary>
    private async Task<bool> DrainAsync(CancellationToken token)
    {
        long retryingId = 0;
        int attempts = 0;

        while (!token.IsCancellationRequested)
        {
            if (!_queue.TryPeek(out QueuedNotification item)) return true;

            NotifyStatus status;
            try
            {
                status = await _send(item.Data, token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return false;
            }

            switch (status)
            {
                case NotifyStatus.Sent:
                case NotifyStatus.Dropped:
                    _queue.Commit(item);
                    retryingId = 0;
                    break;

                case NotifyStatus.Retry:
                    attempts = retryingId == item.Id ? attempts + 1 : 1;
                    retryingId = item.Id;
                    if (attempts >= _maxAttemptsPerItem)
                    {
                        _log?.Invoke($"discarding a {item.Data.Length}B notification after {attempts} failed attempts");
                        _queue.Commit(item);
                        retryingId = 0;
                        break;
                    }
                    try
                    {
                        await Task.Delay(_retryDelay, token).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        return false;
                    }
                    break;

                case NotifyStatus.ClientGone:
                    return false;
            }
        }
        return false;
    }

    private static TaskCompletionSource CreateCompletedIdle()
    {
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        tcs.SetResult();
        return tcs;
    }
}
