import XCTest
@testable import AliControlMac

final class CommandDispatcherTests: XCTestCase {

    func testPingSucceedsWithoutCallingAnyController() {
        let fakes = FakeControllers()
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.command("ping"))

        XCTAssertEqual(reply.status, "ok")
        XCTAssertEqual(reply.action, "ping")
        XCTAssertTrue(fakes.allCalls.isEmpty)
    }

    func testEachSupportedActionDispatchesToTheRightControllerMethod() {
        let fakes = FakeControllers()
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        _ = dispatcher.dispatch(.command("volume_up"))
        _ = dispatcher.dispatch(.command("volume_down"))
        _ = dispatcher.dispatch(.command("mute_toggle"))
        _ = dispatcher.dispatch(.command("open_terminal"))

        XCTAssertEqual(fakes.media.calls, ["volumeUp", "volumeDown", "muteToggle"])
        XCTAssertEqual(fakes.apps.calls, ["openTerminal"])
    }

    func testSuccessfulDispatchReturnsOkStateEchoingAction() {
        let fakes = FakeControllers()
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.command("volume_up"))

        XCTAssertEqual(reply.type, ProtocolMessage.typeState)
        XCTAssertEqual(reply.action, "volume_up")
        XCTAssertEqual(reply.status, "ok")
    }

    func testControllerFailureReturnsErrorState() {
        let fakes = FakeControllers()
        fakes.apps.result = false
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.command("open_terminal"))

        XCTAssertEqual(reply.status, "error")
        XCTAssertEqual(reply.action, "open_terminal")
    }

    func testUnsupportedActionReturnsErrorWithoutCallingAnyController() {
        let fakes = FakeControllers()
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.command("shutdown"))

        XCTAssertEqual(reply.status, "error")
        XCTAssertTrue(fakes.allCalls.isEmpty)
    }

    func testEmptyActionReturnsErrorWithoutCallingAnyController() {
        let fakes = FakeControllers()
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(ProtocolMessage(type: ProtocolMessage.typeCommand))

        XCTAssertEqual(reply.status, "error")
        XCTAssertTrue(fakes.allCalls.isEmpty)
    }

    func testNonCommandMessageReturnsErrorWithoutCallingAnyController() {
        let fakes = FakeControllers()
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.state(status: "connected"))

        XCTAssertEqual(reply.status, "error")
        XCTAssertTrue(fakes.allCalls.isEmpty)
    }

    // MARK: - Accessibility-gated actions fail safely (TASK-006 requirement #13)

    func testAccessibilityGatedActionFailsSafelyWhenNotTrusted() {
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = false
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.command("play_pause"))

        XCTAssertEqual(reply.status, "error")
        XCTAssertEqual(reply.action, "play_pause")
        // The controller method itself must never even be called — the
        // permission check happens before execute(), not inside it.
        XCTAssertTrue(fakes.media.calls.isEmpty)
    }

    func testAccessibilityGatedActionSucceedsWhenTrusted() {
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = true
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.command("play_pause"))

        XCTAssertEqual(reply.status, "ok")
        XCTAssertEqual(fakes.media.calls, ["playPause"])
    }

    func testMissingAccessibilityDoesNotAffectUnrelatedCommands() {
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = false
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        // An Accessibility-gated action fails...
        let blocked = dispatcher.dispatch(.command("copy"))
        XCTAssertEqual(blocked.status, "error")

        // ...but a NONE-permission action right after it still works fine.
        let unaffected = dispatcher.dispatch(.command("volume_up"))
        XCTAssertEqual(unaffected.status, "ok")
        XCTAssertEqual(fakes.media.calls, ["volumeUp"])
    }

    func testRepeatedDispatchOfBlockedActionNeverCallsController() {
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = false
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        // Dispatching the same permission-gated action multiple times must
        // not somehow "prompt" or escalate — it just keeps failing safely,
        // and the underlying controller is never invoked.
        for _ in 0..<3 {
            let reply = dispatcher.dispatch(.command("undo"))
            XCTAssertEqual(reply.status, "error")
        }
        XCTAssertTrue(fakes.productivity.calls.isEmpty)
    }

    // MARK: - lock_screen (fixed: the legacy CGSession path was removed on current macOS)

    func testLockScreenDispatchesAndSucceedsWithoutAccessibilityTrust() {
        // lock_screen must stay usable even when Accessibility was never
        // granted — its metadata is permission NONE, unlike the 14
        // keystroke-simulation actions.
        let fakes = FakeControllers()
        fakes.accessibility.isTrusted = false
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let reply = dispatcher.dispatch(.command("lock_screen"))

        XCTAssertEqual(reply.status, "ok")
        XCTAssertEqual(reply.action, "lock_screen")
        XCTAssertEqual(fakes.system.calls, ["lockScreen"])
    }

    func testLockScreenFailureReturnsErrorWithoutAffectingOtherCommands() {
        let fakes = FakeControllers()
        fakes.system.result = false
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())

        let lockReply = dispatcher.dispatch(.command("lock_screen"))
        XCTAssertEqual(lockReply.status, "error")

        let volumeReply = dispatcher.dispatch(.command("volume_up"))
        XCTAssertEqual(volumeReply.status, "ok")
        XCTAssertEqual(fakes.media.calls, ["volumeUp"])
    }
}
