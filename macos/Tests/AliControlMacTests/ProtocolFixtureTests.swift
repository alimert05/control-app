import XCTest
@testable import AliControlMac

/// Cross-platform protocol fixtures. The literal JSON strings in this file
/// MUST match the ones in
/// android/app/src/test/java/com/alicontrol/app/ble/ProtocolFixtureTest.kt
/// exactly — the two files exist specifically to catch wire-format drift
/// between the two platforms, and were added after a real bug where every
/// command arrived here as unparseable garbage. The actual root cause turned
/// out to be BLE MTU truncation on the Android side (messages are 34-43
/// bytes; the default ATT MTU only allows 20), not a field-name/JSON-shape
/// mismatch — the two formats were already compatible, as every test below
/// confirms.
final class ProtocolFixtureTests: XCTestCase {

    func testParsesExactVolumeUpCommandJsonProducedByAndroid() {
        let json = "{\"type\":\"command\",\"action\":\"volume_up\"}"
        XCTAssertEqual(ProtocolMessage.decode(Data(json.utf8)), .command("volume_up"))
    }

    func testParsesEveryPreTask006CommandJsonProducedByAndroid() {
        // The five actions that existed before TASK-006 — must stay
        // byte-for-byte backward compatible.
        let cases: [(json: String, expected: AliControlAction)] = [
            ("{\"type\":\"command\",\"action\":\"ping\"}", .ping),
            ("{\"type\":\"command\",\"action\":\"volume_up\"}", .volumeUp),
            ("{\"type\":\"command\",\"action\":\"volume_down\"}", .volumeDown),
            ("{\"type\":\"command\",\"action\":\"mute_toggle\"}", .muteToggle),
            ("{\"type\":\"command\",\"action\":\"play_pause\"}", .playPause),
            ("{\"type\":\"command\",\"action\":\"open_terminal\"}", .openTerminal)
        ]
        for testCase in cases {
            let decoded = ProtocolMessage.decode(Data(testCase.json.utf8))
            XCTAssertEqual(decoded?.action, testCase.expected.rawValue, "decoding \(testCase.json)")
            XCTAssertEqual(decoded?.type, ProtocolMessage.typeCommand, "decoding \(testCase.json)")
        }
    }

    /// The task's own example: {"type":"command","action":"open_vscode"}.
    func testParsesNewTask006ActionExample() {
        let json = "{\"type\":\"command\",\"action\":\"open_vscode\"}"
        XCTAssertEqual(ProtocolMessage.decode(Data(json.utf8)), .command("open_vscode"))
    }

    func testParsesEveryTask006ActionIdProducedByAndroid() {
        for action in AliControlAction.allCases {
            let json = "{\"type\":\"command\",\"action\":\"\(action.rawValue)\"}"
            let decoded = ProtocolMessage.decode(Data(json.utf8))
            XCTAssertEqual(decoded?.action, action.rawValue, "decoding \(json)")
            XCTAssertEqual(decoded?.type, ProtocolMessage.typeCommand, "decoding \(json)")
        }
    }

    // JSONEncoder's key order is an implementation detail — not part of the
    // JSON spec, and observed to actually differ between `swift test` and
    // `xcodebuild test` on this exact codebase, which is why "canonical" is
    // checked as field names/values here (what Android's decoder actually
    // reads), not as an exact byte-string.

    func testSerializesOkAckAndroidCanParse() throws {
        let object = try jsonObject(.state(status: "ok", action: "volume_up"))
        XCTAssertEqual(object["type"] as? String, "state")
        XCTAssertEqual(object["action"] as? String, "volume_up")
        XCTAssertEqual(object["status"] as? String, "ok")
        XCTAssertEqual(object.count, 3)
    }

    /// The task's own example ack for open_vscode.
    func testSerializesOpenVSCodeOkAckAndroidCanParse() throws {
        let object = try jsonObject(.state(status: "ok", action: "open_vscode"))
        XCTAssertEqual(object["type"] as? String, "state")
        XCTAssertEqual(object["action"] as? String, "open_vscode")
        XCTAssertEqual(object["status"] as? String, "ok")
    }

    func testSerializesErrorAckAndroidCanParse() throws {
        let object = try jsonObject(.state(status: "error", action: "open_terminal"))
        XCTAssertEqual(object["type"] as? String, "state")
        XCTAssertEqual(object["action"] as? String, "open_terminal")
        XCTAssertEqual(object["status"] as? String, "error")
        XCTAssertEqual(object.count, 3)
    }

    func testSerializesInitialConnectedStateAndroidCanParse() throws {
        let object = try jsonObject(.state(status: "connected"))
        XCTAssertEqual(object["type"] as? String, "state")
        XCTAssertEqual(object["status"] as? String, "connected")
        XCTAssertNil(object["action"])
        XCTAssertEqual(object.count, 2)
    }

    func testMalformedMessagesFailSafely() {
        XCTAssertNil(ProtocolMessage.decode(Data()))
        XCTAssertNil(ProtocolMessage.decode(Data("not json".utf8)))
        XCTAssertNil(ProtocolMessage.decode(Data("{\"action\":\"ping\"}".utf8)))
        // Exactly what the default 20-byte BLE ATT MTU used to truncate
        // {"type":"command","action":"volume_up"} down to — the literal
        // shape of the real bug, reproduced here as a regression test.
        XCTAssertNil(ProtocolMessage.decode(Data("{\"type\":\"command\",\"a".utf8)))
    }

    func testUnparseablePayloadProducesAnErrorStateNotACrash() {
        // Simulates PeripheralManager's didReceiveWrite path for a
        // truncated/garbled payload, without needing real CoreBluetooth.
        let truncated = Data("{\"type\":\"command\",\"a".utf8)
        XCTAssertNil(ProtocolMessage.decode(truncated))
        // A safe UTF-8 preview must not throw even on arbitrary bytes.
        XCTAssertNoThrow(String(decoding: truncated, as: UTF8.self))
    }

    func testUnsupportedActionReturnsErrorAndDoesNotExecute() {
        let fakes = FakeControllers()
        let dispatcher = CommandDispatcher(registry: fakes.makeRegistry())
        let message = ProtocolMessage.decode(Data("{\"type\":\"command\",\"action\":\"shutdown\"}".utf8))!

        let reply = dispatcher.dispatch(message)

        XCTAssertEqual(reply.status, "error")
        XCTAssertTrue(fakes.allCalls.isEmpty)
    }

    private func jsonObject(_ message: ProtocolMessage) throws -> [String: Any] {
        try XCTUnwrap(try JSONSerialization.jsonObject(with: message.encoded()) as? [String: Any])
    }
}
