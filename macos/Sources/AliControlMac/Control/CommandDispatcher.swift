import Foundation

/// Turns a decoded `ProtocolMessage` command into a `MacOSControlling` call
/// and produces the state/ack `ProtocolMessage` to send back.
///
///     BLE Peripheral (PeripheralManager)
///         -> Protocol Parser (ProtocolMessage.decode)
///             -> Command Dispatcher (this)
///                 -> MacOSController
///
/// This is the only place that maps protocol action strings to macOS
/// behavior. `PeripheralManager` never calls `MacOSController` directly, and
/// nothing here touches CoreBluetooth.
final class CommandDispatcher {

    private let controller: MacOSControlling

    init(controller: MacOSControlling = MacOSController()) {
        self.controller = controller
    }

    func dispatch(_ message: ProtocolMessage) -> ProtocolMessage {
        guard message.type == ProtocolMessage.typeCommand else {
            return .state(status: "error", action: message.action)
        }
        guard let actionRaw = message.action, let command = AliControlCommand(rawValue: actionRaw) else {
            return .state(status: "error", action: message.action)
        }

        let succeeded: Bool
        switch command {
        case .ping:
            succeeded = true
        case .volumeUp:
            succeeded = controller.volumeUp()
        case .volumeDown:
            succeeded = controller.volumeDown()
        case .muteToggle:
            succeeded = controller.toggleMute()
        case .playPause:
            succeeded = controller.playPause()
        case .openTerminal:
            succeeded = controller.openTerminal()
        }

        return .state(status: succeeded ? "ok" : "error", action: actionRaw)
    }
}
