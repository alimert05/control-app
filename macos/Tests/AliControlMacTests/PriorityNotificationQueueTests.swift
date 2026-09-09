import XCTest
@testable import AliControlMac

/// The backpressure/priority engine behind artwork transfer, driven with a
/// fake `send` closure that stands in for
/// `CBPeripheralManager.updateValue(...)` — nothing here touches real
/// CoreBluetooth.
final class PriorityNotificationQueueTests: XCTestCase {

    func testHighPriorityItemsAreSentInOrder() {
        let queue = PriorityNotificationQueue()
        queue.enqueueHighPriority(Data([1]))
        queue.enqueueHighPriority(Data([2]))
        var sent: [Data] = []

        queue.flush { sent.append($0); return true }

        XCTAssertEqual(sent, [Data([1]), Data([2])])
    }

    func testHighPriorityIsAlwaysDrainedCompletelyBeforeAnyLowPriorityItem() {
        let queue = PriorityNotificationQueue()
        queue.replaceLowPriority(with: [Data([0xA1]), Data([0xA2])])
        queue.enqueueHighPriority(Data([0x01]))
        queue.enqueueHighPriority(Data([0x02]))
        var sent: [Data] = []

        queue.flush { sent.append($0); return true }
        queue.flush { sent.append($0); return true }

        XCTAssertEqual(sent, [Data([0x01]), Data([0x02]), Data([0xA1]), Data([0xA2])])
    }

    func testANewHighPriorityCommandJumpsAheadOfAnAlreadyQueuedArtworkTransfer() {
        // Simulates: an artwork transfer is mid-flight, then a button press
        // ack is enqueued before the next flush — it must go out first, so a
        // large image transfer never makes commands feel blocked.
        let queue = PriorityNotificationQueue()
        queue.replaceLowPriority(with: [Data([0xA1]), Data([0xA2]), Data([0xA3])])
        var sent: [Data] = []

        // One flush intentionally advances one artwork chunk, then yields.
        queue.flush { data in sent.append(data); return true }
        XCTAssertEqual(sent, [Data([0xA1])])

        // A command ack arrives mid-transfer.
        queue.enqueueHighPriority(Data([0x99]))
        queue.flush { sent.append($0); return true }
        queue.flush { sent.append($0); return true }

        XCTAssertEqual(sent, [Data([0xA1]), Data([0x99]), Data([0xA2]), Data([0xA3])])
    }

    // MARK: - backpressure / pause-resume

    func testFlushStopsTheInstantSendReturnsFalseAndRetainsTheUnsentItem() {
        let queue = PriorityNotificationQueue()
        queue.enqueueHighPriority(Data([1]))
        queue.enqueueHighPriority(Data([2]))
        var sendCount = 0

        let completed = queue.flush { _ in
            sendCount += 1
            return sendCount < 1 // the very first send fails ("queue full")
        }

        XCTAssertFalse(completed)
        XCTAssertEqual(queue.highPriority, [Data([1]), Data([2])], "the failed item must not be dropped or advanced past")
    }

    func testResumingAfterBackpressureContinuesFromExactlyWhereItStopped() {
        let queue = PriorityNotificationQueue()
        queue.enqueueHighPriority(Data([1]))
        queue.enqueueHighPriority(Data([2]))
        queue.enqueueHighPriority(Data([3]))
        var sent: [Data] = []

        // First flush: only the first item gets through (simulating a full transmit queue).
        queue.flush { data in
            if sent.isEmpty {
                sent.append(data)
                return true
            }
            return false // reject everything else this round
        }
        XCTAssertEqual(sent, [Data([1])])

        // "peripheralManagerIsReady" fires: resume.
        queue.flush { sent.append($0); return true }

        XCTAssertEqual(sent, [Data([1]), Data([2]), Data([3])])
    }

    func testBackpressureOnALowPriorityItemDoesNotDropOrSkipIt() {
        let queue = PriorityNotificationQueue()
        queue.replaceLowPriority(with: [Data([0xA1]), Data([0xA2])])

        let completed = queue.flush { _ in false }

        XCTAssertFalse(completed)
        XCTAssertEqual(queue.lowPriority, [Data([0xA1]), Data([0xA2])])
    }

    func testFlushNeverCallsSendMoreTimesThanThereAreQueuedItems() {
        // A crude but effective busy-loop guard: with 2 items and send always
        // succeeding, exactly 2 calls happen — flush must return rather than
        // spin once the queues are empty.
        let queue = PriorityNotificationQueue()
        queue.enqueueHighPriority(Data([1]))
        queue.enqueueHighPriority(Data([2]))
        var callCount = 0

        queue.flush { _ in callCount += 1; return true }

        XCTAssertEqual(callCount, 2)
    }

    func testOneFlushNeverFloodsMoreThanOneArtworkFrame() {
        let queue = PriorityNotificationQueue()
        queue.replaceLowPriority(with: [Data([1]), Data([2]), Data([3])])
        var sent: [Data] = []

        XCTAssertTrue(queue.flush { sent.append($0); return true })

        XCTAssertEqual(sent, [Data([1])])
        XCTAssertEqual(queue.lowPriority, [Data([2]), Data([3])])
    }

    // MARK: - replace / cancel semantics

    func testReplacingLowPriorityDiscardsWhateverWasQueuedBefore() {
        let queue = PriorityNotificationQueue()
        queue.replaceLowPriority(with: [Data([0xA1]), Data([0xA2])])

        queue.replaceLowPriority(with: [Data([0xB1])])

        XCTAssertEqual(queue.lowPriority, [Data([0xB1])])
    }

    func testClearLowPriorityLeavesHighPriorityUntouched() {
        let queue = PriorityNotificationQueue()
        queue.enqueueHighPriority(Data([1]))
        queue.replaceLowPriority(with: [Data([0xA1])])

        queue.clearLowPriority()

        XCTAssertEqual(queue.highPriority, [Data([1])])
        XCTAssertTrue(queue.lowPriority.isEmpty)
    }

    func testClearAllEmptiesBothQueues() {
        let queue = PriorityNotificationQueue()
        queue.enqueueHighPriority(Data([1]))
        queue.replaceLowPriority(with: [Data([0xA1])])

        queue.clearAll()

        XCTAssertTrue(queue.highPriority.isEmpty)
        XCTAssertTrue(queue.lowPriority.isEmpty)
    }

    func testIsLowPriorityActiveReflectsWhetherAnArtworkTransferIsStillQueued() {
        let queue = PriorityNotificationQueue()
        XCTAssertFalse(queue.isLowPriorityActive)

        queue.replaceLowPriority(with: [Data([0xA1])])
        XCTAssertTrue(queue.isLowPriorityActive)

        queue.flush { _ in true }
        XCTAssertFalse(queue.isLowPriorityActive)
    }
}
