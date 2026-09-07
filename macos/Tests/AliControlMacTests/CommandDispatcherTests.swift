import XCTest
@testable import AliControlMac

/// Records calls instead of touching real system volume / launching Terminal,
/// so these tests can run in CI/automated `swift test` safely.
private final class FakeMacOSController: MacOSControlling {
    var result = true
    private(set) var calls: [String] = []

    func volumeUp() -> Bool { calls.append("volumeUp"); return result }
    func volumeDown() -> Bool { calls.append("volumeDown"); return result }
    func toggleMute() -> Bool { calls.append("toggleMute"); return result }
    func playPause() -> Bool { calls.append("playPause"); return result }
    func openTerminal() -> Bool { calls.append("openTerminal"); return result }
}

final class CommandDispatcherTests: XCTestCase {

    func testPingSucceedsWithoutCallingController() {
        let controller = FakeMacOSController()
        let reply = CommandDispatcher(controller: controller).dispatch(.command("ping"))

        XCTAssertEqual(reply.status, "ok")
        XCTAssertEqual(reply.action, "ping")
        XCTAssertTrue(controller.calls.isEmpty)
    }

    func testEachSupportedActionDispatchesToTheRightControllerMethod() {
        let controller = FakeMacOSController()
        let dispatcher = CommandDispatcher(controller: controller)

        _ = dispatcher.dispatch(.command("volume_up"))
        _ = dispatcher.dispatch(.command("volume_down"))
        _ = dispatcher.dispatch(.command("mute_toggle"))
        _ = dispatcher.dispatch(.command("play_pause"))
        _ = dispatcher.dispatch(.command("open_terminal"))

        XCTAssertEqual(controller.calls, ["volumeUp", "volumeDown", "toggleMute", "playPause", "openTerminal"])
    }

    func testSuccessfulDispatchReturnsOkStateEchoingAction() {
        let controller = FakeMacOSController()
        let reply = CommandDispatcher(controller: controller).dispatch(.command("volume_up"))

        XCTAssertEqual(reply.type, ProtocolMessage.typeState)
        XCTAssertEqual(reply.action, "volume_up")
        XCTAssertEqual(reply.status, "ok")
    }

    func testControllerFailureReturnsErrorState() {
        let controller = FakeMacOSController()
        controller.result = false

        let reply = CommandDispatcher(controller: controller).dispatch(.command("open_terminal"))

        XCTAssertEqual(reply.status, "error")
        XCTAssertEqual(reply.action, "open_terminal")
    }

    func testUnsupportedActionReturnsErrorWithoutCallingController() {
        let controller = FakeMacOSController()
        let reply = CommandDispatcher(controller: controller).dispatch(.command("shutdown"))

        XCTAssertEqual(reply.status, "error")
        XCTAssertTrue(controller.calls.isEmpty)
    }

    func testEmptyActionReturnsErrorWithoutCallingController() {
        let controller = FakeMacOSController()
        let reply = CommandDispatcher(controller: controller).dispatch(ProtocolMessage(type: ProtocolMessage.typeCommand))

        XCTAssertEqual(reply.status, "error")
        XCTAssertTrue(controller.calls.isEmpty)
    }

    func testNonCommandMessageReturnsErrorWithoutCallingController() {
        let controller = FakeMacOSController()
        let reply = CommandDispatcher(controller: controller).dispatch(.state(status: "connected"))

        XCTAssertEqual(reply.status, "error")
        XCTAssertTrue(controller.calls.isEmpty)
    }
}
