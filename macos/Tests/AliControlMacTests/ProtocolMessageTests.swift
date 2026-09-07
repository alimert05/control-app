import XCTest
@testable import AliControlMac

final class ProtocolMessageTests: XCTestCase {

    func testCommandEncodesTypeAndAction() throws {
        let json = try jsonObject(.command("ping"))
        XCTAssertEqual(json["type"] as? String, "command")
        XCTAssertEqual(json["action"] as? String, "ping")
        XCTAssertNil(json["status"])
    }

    func testStateEncodesStatusAndOptionalAction() throws {
        let json = try jsonObject(.state(status: "ok", action: "volume_up"))
        XCTAssertEqual(json["type"] as? String, "state")
        XCTAssertEqual(json["status"] as? String, "ok")
        XCTAssertEqual(json["action"] as? String, "volume_up")
    }

    func testInitialConnectedStateOmitsAction() throws {
        let json = try jsonObject(.state(status: "connected"))
        XCTAssertEqual(json["status"] as? String, "connected")
        XCTAssertNil(json["action"])
    }

    func testRoundTripsThroughEncodedData() {
        let original = ProtocolMessage.command("ping")
        XCTAssertEqual(ProtocolMessage.decode(original.encoded()), original)
    }

    /// Exactly the bytes android/.../ble/ProtocolMessage.kt produces —
    /// verifies the two sides actually agree on wire format.
    func testDecodesAndroidProducedJson() {
        let json = "{\"type\":\"command\",\"action\":\"ping\"}"
        XCTAssertEqual(ProtocolMessage.decode(Data(json.utf8)), .command("ping"))
    }

    func testDecodesAndroidProducedStateJson() {
        let json = "{\"type\":\"state\",\"status\":\"connected\"}"
        XCTAssertEqual(ProtocolMessage.decode(Data(json.utf8)), .state(status: "connected"))
    }

    func testMalformedDataReturnsNil() {
        XCTAssertNil(ProtocolMessage.decode(Data("not json".utf8)))
        XCTAssertNil(ProtocolMessage.decode(Data()))
    }

    private func jsonObject(_ message: ProtocolMessage) throws -> [String: Any] {
        try XCTUnwrap(try JSONSerialization.jsonObject(with: message.encoded()) as? [String: Any])
    }
}
