import Foundation

/// Turns a decoded `ProtocolMessage` command into an executed action and
/// produces the state/ack `ProtocolMessage` to send back.
///
///     BLE Peripheral (PeripheralManager)
///         -> Protocol Parser (ProtocolMessage.decode)
///             -> Command Dispatcher (this)
///                 -> Action Registry (id -> handler lookup + permission check)
///                     -> Media/System/App/Window/Productivity controllers
///
/// This is the only place that turns a protocol action ID into macOS
/// behavior. `PeripheralManager` never touches the registry/controllers
/// directly, and nothing here touches CoreBluetooth.
///
/// The registry is a strict allowlist: any action ID that isn't registered
/// there is rejected with an error state and never executed — there is no
/// generic/arbitrary command path anywhere in this chain.
final class CommandDispatcher {

    private let registry: ActionRegistry

    init(registry: ActionRegistry = ActionRegistry()) {
        self.registry = registry
    }

    func dispatch(_ message: ProtocolMessage) -> ProtocolMessage {
        guard message.type == ProtocolMessage.typeCommand else {
            return .state(status: "error", action: message.action)
        }
        guard let actionId = message.action, let handler = registry.handler(for: actionId) else {
            return .state(status: "error", action: message.action)
        }
        guard registry.canExecute(handler) else {
            // Permission-gated (Accessibility) and not currently trusted:
            // fail safely with a clear error ack. Never prompts, never
            // crashes, never affects any other/subsequent command.
            return .state(status: "error", action: actionId)
        }

        let succeeded = handler.execute()
        return .state(status: succeeded ? "ok" : "error", action: actionId)
    }
}
