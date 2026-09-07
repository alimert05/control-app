import Foundation

/// Minimal, transport-independent message envelope for the Ali Control
/// protocol — the Swift side of
/// android/app/src/main/java/com/alicontrol/app/ble/ProtocolMessage.kt.
/// Wire format is identical: compact JSON with `type`, optional `action`,
/// optional `status`, absent fields simply omitted (never emitted as `null`,
/// and Android's decoder doesn't expect that either).
///
/// This type has no idea it's currently carried over BLE — `PeripheralManager`
/// is the only thing that touches CoreBluetooth.
struct ProtocolMessage: Codable, Equatable {

    static let typeCommand = "command"
    static let typeState = "state"

    let type: String
    let action: String?
    let status: String?

    init(type: String, action: String? = nil, status: String? = nil) {
        self.type = type
        self.action = action
        self.status = status
    }

    static func command(_ action: String) -> ProtocolMessage {
        ProtocolMessage(type: typeCommand, action: action)
    }

    /// `action` is included so a state message can be tied back to the
    /// command it acknowledges (e.g. `{"type":"state","action":"volume_up","status":"ok"}`).
    /// It's omitted for messages not sent in response to a specific command,
    /// like the initial post-subscribe handshake.
    static func state(status: String, action: String? = nil) -> ProtocolMessage {
        ProtocolMessage(type: typeState, action: action, status: status)
    }

    func encoded() -> Data {
        (try? JSONEncoder().encode(self)) ?? Data()
    }

    static func decode(_ data: Data) -> ProtocolMessage? {
        try? JSONDecoder().decode(ProtocolMessage.self, from: data)
    }
}
