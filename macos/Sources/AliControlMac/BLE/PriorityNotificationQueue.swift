import Foundation

/// A two-tier send queue that mirrors CoreBluetooth's own backpressure
/// contract: the caller enqueues items, then drives delivery by repeatedly
/// calling [flush(send:)] with a closure shaped exactly like
/// `CBPeripheralManager.updateValue(_:for:onSubscribedCentrals:)` — return
/// `true` to keep going, `false` to pause. [highPriority] is always drained
/// completely before one [lowPriority] item is sent. Limiting each flush to
/// one artwork frame lets the main run loop service a newly arrived command
/// before the next artwork frame even when CoreBluetooth has ample buffer.
///
/// Pulled out of `PeripheralManager` specifically so this — the trickiest
/// part of TASK-009A's artwork transfer (priority ordering, pause/resume on
/// backpressure, cancellation) — is testable against a fake `send` closure
/// instead of a real `CBPeripheralManager`, which nothing in this project
/// mocks.
///
/// `PeripheralManager` maps this directly onto its two real uses: command
/// acks / live-state updates are [highPriority]; artwork chunks are
/// [lowPriority], so a large image transfer can never make a button press
/// feel unresponsive — any ack enqueued mid-transfer is sent on the very
/// next [flush(send:)] call, before another byte of artwork goes out.
final class PriorityNotificationQueue {

    private(set) var highPriority: [Data] = []
    private(set) var lowPriority: [Data] = []

    func enqueueHighPriority(_ data: Data) {
        highPriority.append(data)
    }

    /// Replaces the entire low-priority queue — never appends. This is what
    /// implements "a new artwork transfer cancels the previous one": there
    /// is only ever one artwork transfer in flight, so a fresh request or a
    /// track change simply throws away whatever was still queued.
    func replaceLowPriority(with items: [Data]) {
        lowPriority = items
    }

    func clearLowPriority() {
        lowPriority.removeAll()
    }

    func clearAll() {
        highPriority.removeAll()
        lowPriority.removeAll()
    }

    var isLowPriorityActive: Bool { !lowPriority.isEmpty }

    /// Sends all high-priority items it can, then at most one low-priority
    /// item, stopping the instant [send] returns `false` — the item that
    /// failed stays at the head of its queue, retried on the next call (typically from
    /// `peripheralManagerIsReady(toUpdateSubscribers:)`). Never busy-loops:
    /// a single `false` is one function return, not a retry spin.
    ///
    /// Returns `true` if no send was rejected, `false` if CoreBluetooth
    /// applied backpressure. A true result may still leave low-priority items
    /// for a later, explicitly scheduled flush.
    @discardableResult
    func flush(send: (Data) -> Bool) -> Bool {
        while let next = highPriority.first {
            guard send(next) else { return false }
            highPriority.removeFirst()
        }
        if let next = lowPriority.first {
            guard send(next) else { return false }
            lowPriority.removeFirst()
        }
        return true
    }
}
