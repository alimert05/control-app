using AliControlWindows.BLE;

namespace AliControlWindows.Tests.BLE;

/// <summary>
/// The priority/backpressure engine behind artwork transfer, driven with a fake
/// sender in place of the real GATT notify call: nothing here touches Bluetooth.
/// </summary>
public class PriorityNotificationQueueTests
{
    private static byte[] B(params byte[] b) => b;

    private static List<byte[]> DrainAll(PriorityNotificationQueue queue)
    {
        var sent = new List<byte[]>();
        while (queue.TryPeek(out QueuedNotification item))
        {
            sent.Add(item.Data);
            queue.Commit(item);
        }
        return sent;
    }

    [Fact]
    public void HighPriorityItemsAreSentInOrder()
    {
        var queue = new PriorityNotificationQueue();
        queue.EnqueueHighPriority(B(1));
        queue.EnqueueHighPriority(B(2));
        Assert.Equal(new[] { B(1), B(2) }, DrainAll(queue));
    }

    [Fact]
    public void HighPriorityIsAlwaysDrainedBeforeAnyLowPriorityItem()
    {
        var queue = new PriorityNotificationQueue();
        queue.ReplaceLowPriority(new[] { B(0xA1), B(0xA2) });
        queue.EnqueueHighPriority(B(1));
        queue.EnqueueHighPriority(B(2));
        Assert.Equal(new[] { B(1), B(2), B(0xA1), B(0xA2) }, DrainAll(queue));
    }

    [Fact]
    public void ANewAcknowledgementJumpsAheadOfAnAlreadyQueuedArtworkTransfer()
    {
        var queue = new PriorityNotificationQueue();
        queue.ReplaceLowPriority(new[] { B(0xA1), B(0xA2), B(0xA3) });
        var sent = new List<byte[]>();

        queue.TryPeek(out var first);
        sent.Add(first.Data);
        queue.Commit(first);                  // one artwork frame goes out...
        queue.EnqueueHighPriority(B(0x99));   // ...then a button press is acknowledged
        sent.AddRange(DrainAll(queue));

        Assert.Equal(new[] { B(0xA1), B(0x99), B(0xA2), B(0xA3) }, sent);
    }

    [Fact]
    public void AnItemThatWasNotCommittedStaysExactlyWhereItWas()
    {
        var queue = new PriorityNotificationQueue();
        queue.EnqueueHighPriority(B(1));
        queue.EnqueueHighPriority(B(2));

        Assert.True(queue.TryPeek(out var item)); // "send failed": never committed
        Assert.Equal(B(1), item.Data);
        Assert.True(queue.TryPeek(out var again));
        Assert.Equal(B(1), again.Data);
        Assert.Equal(new[] { B(1), B(2) }, queue.HighPrioritySnapshot());
    }

    [Fact]
    public void ReplacingTheLowPriorityQueueDiscardsWhateverWasQueuedBefore()
    {
        var queue = new PriorityNotificationQueue();
        queue.ReplaceLowPriority(new[] { B(0xA1), B(0xA2) });
        queue.ReplaceLowPriority(new[] { B(0xB1) });
        Assert.Equal(new[] { B(0xB1) }, queue.LowPrioritySnapshot());
    }

    [Fact]
    public void CommittingAnItemThatWasReplacedMidSendRemovesNothingElse()
    {
        var queue = new PriorityNotificationQueue();
        queue.ReplaceLowPriority(new[] { B(0xA1), B(0xA2) });
        queue.TryPeek(out var inFlight);

        queue.ReplaceLowPriority(new[] { B(0xB1), B(0xB2) }); // a new transfer arrives while A1 is being sent
        queue.Commit(inFlight);                               // the old send finishes

        Assert.Equal(new[] { B(0xB1), B(0xB2) }, queue.LowPrioritySnapshot());
    }

    [Fact]
    public void ClearLowPriorityLeavesHighPriorityUntouched()
    {
        var queue = new PriorityNotificationQueue();
        queue.EnqueueHighPriority(B(1));
        queue.ReplaceLowPriority(new[] { B(0xA1) });
        queue.ClearLowPriority();
        Assert.Equal(new[] { B(1) }, queue.HighPrioritySnapshot());
        Assert.False(queue.IsLowPriorityActive);
    }

    [Fact]
    public void ClearAllEmptiesBothQueues()
    {
        var queue = new PriorityNotificationQueue();
        queue.EnqueueHighPriority(B(1));
        queue.ReplaceLowPriority(new[] { B(0xA1) });
        queue.ClearAll();
        Assert.True(queue.IsEmpty);
    }

    [Fact]
    public void IsLowPriorityActiveReflectsWhetherATransferIsStillQueued()
    {
        var queue = new PriorityNotificationQueue();
        Assert.False(queue.IsLowPriorityActive);
        queue.ReplaceLowPriority(new[] { B(0xA1) });
        Assert.True(queue.IsLowPriorityActive);
        DrainAll(queue);
        Assert.False(queue.IsLowPriorityActive);
    }

    [Fact]
    public void IsThreadSafeUnderConcurrentProducers()
    {
        var queue = new PriorityNotificationQueue();
        Parallel.For(0, 8, p =>
        {
            for (int i = 0; i < 500; i++) queue.EnqueueHighPriority(B((byte)p));
        });
        Assert.Equal(4000, queue.HighPriorityCount);
        Assert.Equal(4000, DrainAll(queue).Count);
    }
}

public class NotificationPumpTests
{
    private static byte[] B(params byte[] b) => b;

    private sealed class Harness
    {
        public readonly PriorityNotificationQueue Queue = new();
        public readonly List<byte[]> Sent = new();
        public Func<byte[], int, NotifyStatus> Behaviour = (_, _) => NotifyStatus.Sent;
        public int Calls;
        public TaskCompletionSource? Gate;
        public NotificationPump Pump;
        public int DrainedCount;

        public Harness(TimeSpan? retryDelay = null, int maxAttempts = 10)
        {
            Pump = new NotificationPump(Queue, SendAsync, retryDelay ?? TimeSpan.FromMilliseconds(1), maxAttempts);
            Pump.Drained += () => Interlocked.Increment(ref DrainedCount);
        }

        private async Task<NotifyStatus> SendAsync(byte[] data, CancellationToken ct)
        {
            int call = Interlocked.Increment(ref Calls);
            if (Gate != null) await Gate.Task.WaitAsync(ct);
            NotifyStatus status = Behaviour(data, call);
            if (status == NotifyStatus.Sent) lock (Sent) Sent.Add(data);
            return status;
        }

        public async Task KickAndWaitAsync()
        {
            Pump.Kick();
            await Pump.WhenIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));
        }
    }

    [Fact]
    public async Task DeliversEverythingInPriorityOrder()
    {
        var h = new Harness();
        h.Queue.ReplaceLowPriority(new[] { B(0xA1), B(0xA2) });
        h.Queue.EnqueueHighPriority(B(1));
        h.Queue.EnqueueHighPriority(B(2));

        await h.KickAndWaitAsync();

        Assert.Equal(new[] { B(1), B(2), B(0xA1), B(0xA2) }, h.Sent);
        Assert.True(h.Queue.IsEmpty);
    }

    [Fact]
    public async Task ARetryKeepsTheItemAtTheHeadAndResumesFromExactlyThere()
    {
        var h = new Harness();
        // The second send attempt hits "backpressure" once, then succeeds.
        h.Behaviour = (_, call) => call == 2 ? NotifyStatus.Retry : NotifyStatus.Sent;
        h.Queue.EnqueueHighPriority(B(1));
        h.Queue.EnqueueHighPriority(B(2));
        h.Queue.EnqueueHighPriority(B(3));

        await h.KickAndWaitAsync();

        Assert.Equal(new[] { B(1), B(2), B(3) }, h.Sent); // nothing dropped, nothing reordered
        Assert.Equal(4, h.Calls);                          // item 2 was attempted twice
    }

    [Fact]
    public async Task ARetryOnALowPriorityItemDoesNotDropOrSkipIt()
    {
        var h = new Harness();
        h.Behaviour = (_, call) => call == 1 ? NotifyStatus.Retry : NotifyStatus.Sent;
        h.Queue.ReplaceLowPriority(new[] { B(0xA1), B(0xA2) });

        await h.KickAndWaitAsync();

        Assert.Equal(new[] { B(0xA1), B(0xA2) }, h.Sent);
    }

    [Fact]
    public async Task AnItemThatKeepsFailingIsEventuallyDiscardedAndLaterOnesStillGoOut()
    {
        var h = new Harness(maxAttempts: 3);
        h.Behaviour = (data, _) => data[0] == 1 ? NotifyStatus.Retry : NotifyStatus.Sent;
        h.Queue.EnqueueHighPriority(B(1));
        h.Queue.EnqueueHighPriority(B(2));

        await h.KickAndWaitAsync();

        Assert.Equal(new[] { B(2) }, h.Sent);
        Assert.True(h.Queue.IsEmpty);
    }

    [Fact]
    public async Task ADroppedNotificationIsDiscardedAndTheRestContinue()
    {
        var h = new Harness();
        h.Behaviour = (data, _) => data[0] == 1 ? NotifyStatus.Dropped : NotifyStatus.Sent;
        h.Queue.EnqueueHighPriority(B(1));
        h.Queue.EnqueueHighPriority(B(2));

        await h.KickAndWaitAsync();

        Assert.Equal(new[] { B(2) }, h.Sent);
    }

    [Fact]
    public async Task WhenTheClientIsGoneThePumpStopsAndLeavesTheQueueForTheOwnerToClear()
    {
        var h = new Harness();
        h.Behaviour = (_, _) => NotifyStatus.ClientGone;
        h.Queue.EnqueueHighPriority(B(1));
        h.Queue.EnqueueHighPriority(B(2));

        await h.KickAndWaitAsync();

        Assert.Equal(1, h.Calls); // no hammering a dead link
        Assert.False(h.Queue.IsEmpty);
    }

    [Fact]
    public async Task NeverSendsMoreOftenThanThereAreItems()
    {
        var h = new Harness();
        h.Queue.EnqueueHighPriority(B(1));
        h.Queue.EnqueueHighPriority(B(2));

        await h.KickAndWaitAsync();
        h.Pump.Kick(); // a spurious wake with nothing queued must do nothing
        await h.Pump.WhenIdleAsync();

        Assert.Equal(2, h.Calls);
    }

    [Fact]
    public async Task AnAcknowledgementEnqueuedMidTransferIsSentBeforeTheNextArtworkFrame()
    {
        var h = new Harness();
        h.Gate = new TaskCompletionSource();
        h.Queue.ReplaceLowPriority(new[] { B(0xA1), B(0xA2), B(0xA3) });
        h.Pump.Kick();
        await WaitUntil(() => h.Calls == 1); // A1 is in flight, blocked on the gate

        h.Queue.EnqueueHighPriority(B(0x99));
        h.Pump.Kick();                       // already running: must not start a second loop
        h.Gate.SetResult();
        await h.Pump.WhenIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new[] { B(0xA1), B(0x99), B(0xA2), B(0xA3) }, h.Sent);
    }

    [Fact]
    public async Task CancelAbortsAnInFlightSendAndThePumpIsReusableForTheNextSession()
    {
        var h = new Harness();
        h.Gate = new TaskCompletionSource();
        h.Queue.EnqueueHighPriority(B(1));
        h.Pump.Kick();
        await WaitUntil(() => h.Calls == 1);

        h.Queue.ClearAll();
        h.Pump.Cancel();
        await h.Pump.WhenIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Empty(h.Sent);

        h.Gate = null;
        h.Queue.EnqueueHighPriority(B(2));
        await h.KickAndWaitAsync();
        Assert.Equal(new[] { B(2) }, h.Sent);
    }

    [Fact]
    public async Task ItemsQueuedByTheNextSessionWhileTheOldRunUnwindsAreNotStranded()
    {
        var h = new Harness();
        h.Gate = new TaskCompletionSource();
        h.Queue.EnqueueHighPriority(B(1));
        h.Pump.Kick();
        await WaitUntil(() => h.Calls == 1);

        h.Queue.ClearAll();
        h.Pump.Cancel();
        h.Gate = null;                           // the next session's sends are not blocked
        h.Queue.EnqueueHighPriority(B(2));
        h.Pump.Kick();                           // arrives while the cancelled loop is still unwinding
        await h.Pump.WhenIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new[] { B(2) }, h.Sent);
    }

    [Fact]
    public async Task RaisesDrainedWhenTheQueueEmpties()
    {
        var h = new Harness();
        h.Queue.EnqueueHighPriority(B(1));
        await h.KickAndWaitAsync();
        Assert.True(h.DrainedCount >= 1);
    }

    private static async Task WaitUntil(Func<bool> condition)
    {
        DateTime deadline = DateTime.UtcNow.AddSeconds(5);
        while (!condition())
        {
            if (DateTime.UtcNow > deadline) throw new TimeoutException("condition not met");
            await Task.Delay(5);
        }
    }
}
