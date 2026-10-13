namespace AliControlWindows.BLE;

public readonly record struct QueuedNotification(long Id, byte[] Data, bool IsLowPriority);

/// <summary>
/// A two-tier notification queue. <b>High priority</b> (command acknowledgements,
/// connection state, live telemetry/media/track messages) is always drained
/// completely before the next <b>low priority</b> item (artwork frames), and the
/// choice is re-made before *every* send — so a button press that arrives in the
/// middle of an artwork transfer has its acknowledgement sent before another byte
/// of artwork, and a large image can never make the controls feel unresponsive.
///
/// The consumer peeks, sends, and only then <see cref="Commit"/>s: an item whose
/// send failed stays exactly where it was (nothing is dropped or skipped). Every
/// item has a unique id, so committing after a concurrent
/// <see cref="ReplaceLowPriority"/> can never remove the wrong item.
///
/// Thread-safe: producers (the peripheral logic) and the single consumer (the
/// notification pump) run on different threads.
/// </summary>
public sealed class PriorityNotificationQueue
{
    private readonly object _gate = new();
    private readonly List<QueuedNotification> _high = new();
    private readonly List<QueuedNotification> _low = new();
    private long _nextId;

    public void EnqueueHighPriority(byte[] data)
    {
        lock (_gate) _high.Add(new QueuedNotification(++_nextId, data, false));
    }

    /// <summary>
    /// Replaces the entire low-priority queue — never appends. That is what
    /// implements "a new artwork transfer cancels the previous one": there is only
    /// ever one artwork transfer in flight, so a fresh request or a track change
    /// throws away whatever was still queued.
    /// </summary>
    public void ReplaceLowPriority(IEnumerable<byte[]> items)
    {
        lock (_gate)
        {
            _low.Clear();
            foreach (byte[] item in items) _low.Add(new QueuedNotification(++_nextId, item, true));
        }
    }

    public void ClearLowPriority()
    {
        lock (_gate) _low.Clear();
    }

    public void ClearAll()
    {
        lock (_gate)
        {
            _high.Clear();
            _low.Clear();
        }
    }

    public bool IsLowPriorityActive
    {
        get
        {
            lock (_gate) return _low.Count > 0;
        }
    }

    public bool IsEmpty
    {
        get
        {
            lock (_gate) return _high.Count == 0 && _low.Count == 0;
        }
    }

    public int HighPriorityCount
    {
        get
        {
            lock (_gate) return _high.Count;
        }
    }

    public int LowPriorityCount
    {
        get
        {
            lock (_gate) return _low.Count;
        }
    }

    /// <summary>The next item to send: the head of the high-priority queue if there is one, otherwise the head of the low-priority queue.</summary>
    public bool TryPeek(out QueuedNotification item)
    {
        lock (_gate)
        {
            if (_high.Count > 0)
            {
                item = _high[0];
                return true;
            }
            if (_low.Count > 0)
            {
                item = _low[0];
                return true;
            }
        }
        item = default;
        return false;
    }

    /// <summary>Removes <paramref name="item"/> after it was sent (or deliberately discarded). A no-op if it is no longer queued.</summary>
    public void Commit(QueuedNotification item)
    {
        lock (_gate)
        {
            List<QueuedNotification> list = item.IsLowPriority ? _low : _high;
            if (list.Count > 0 && list[0].Id == item.Id) list.RemoveAt(0);
        }
    }

    public IReadOnlyList<byte[]> HighPrioritySnapshot()
    {
        lock (_gate) return _high.Select(n => n.Data).ToArray();
    }

    public IReadOnlyList<byte[]> LowPrioritySnapshot()
    {
        lock (_gate) return _low.Select(n => n.Data).ToArray();
    }
}
