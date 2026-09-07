import CoreBluetooth
import Foundation

/// BLE peripheral/server role: advertises the Ali Control service, accepts
/// the tablet's GATT connection, and moves raw bytes between the command/
/// state characteristics and `CommandDispatcher`.
///
/// This is the only type in the agent that touches CoreBluetooth. It knows
/// nothing about JSON or macOS actions — it decodes bytes into a
/// `ProtocolMessage` and hands it to `CommandDispatcher`, then encodes
/// whatever comes back and notifies it.
final class PeripheralManager: NSObject {

    /// Local, in-process status for the menu bar UI. Never sent over BLE —
    /// the wire-level connection state Android cares about is derived from
    /// GATT subscribe/unsubscribe, not from this enum.
    enum Status: Equatable {
        case poweredOff
        case unauthorized
        case unsupported
        case advertising
        case subscribed
    }

    var onStatusChanged: ((Status) -> Void)?

    private(set) var status: Status = .poweredOff {
        didSet {
            guard oldValue != status else { return }
            onStatusChanged?(status)
        }
    }

    private let dispatcher: CommandDispatcher
    private var peripheralManager: CBPeripheralManager!
    private var stateCharacteristic: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?
    private var pendingNotifications: [Data] = []

    init(dispatcher: CommandDispatcher = CommandDispatcher()) {
        self.dispatcher = dispatcher
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: - Service setup

    private func addServiceIfNeeded() {
        guard peripheralManager.state == .poweredOn, stateCharacteristic == nil else { return }

        let command = CBMutableCharacteristic(
            type: AliControlBleProfile.commandCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let state = CBMutableCharacteristic(
            type: AliControlBleProfile.stateCharacteristicUUID,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        stateCharacteristic = state

        let service = CBMutableService(type: AliControlBleProfile.serviceUUID, primary: true)
        service.characteristics = [command, state]
        peripheralManager.add(service)
    }

    private func startAdvertising() {
        let localName = Host.current().localizedName ?? "Ali Control Mac"
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [AliControlBleProfile.serviceUUID],
            CBAdvertisementDataLocalNameKey: localName
        ])
        status = .advertising
    }

    // MARK: - Sending state/ack messages

    private func sendState(_ message: ProtocolMessage) {
        pendingNotifications.append(message.encoded())
        flushPendingNotifications()
    }

    /// `updateValue(_:for:onSubscribedCentrals:)` can return false if the
    /// underlying transmit queue is full; the correct response (per Apple's
    /// docs) is to hold the value and retry from
    /// `peripheralManagerIsReady(toUpdateSubscribers:)`, not to drop it.
    private func flushPendingNotifications() {
        guard let stateCharacteristic else { return }
        while let next = pendingNotifications.first {
            let sent = peripheralManager.updateValue(next, for: stateCharacteristic, onSubscribedCentrals: nil)
            guard sent else { return }
            pendingNotifications.removeFirst()
        }
    }

    /// Local stderr line for debugging — never sent over BLE. Temporary/
    /// lightweight by design: this is a menu-bar helper, not a service with
    /// a real logging story.
    private func logDebug(_ message: String) {
        FileHandle.standardError.write(Data("Ali Control: \(message)\n".utf8))
    }
}

// MARK: - CBPeripheralManagerDelegate

extension PeripheralManager: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            addServiceIfNeeded()
        case .poweredOff:
            // The system drops any previously-added services/advertisement
            // when Bluetooth powers off — clear local state to match, so
            // addServiceIfNeeded() re-adds everything if it powers back on
            // instead of short-circuiting on a stale characteristic reference.
            stateCharacteristic = nil
            subscribedCentral = nil
            pendingNotifications.removeAll()
            status = .poweredOff
        case .unauthorized:
            status = .unauthorized
        case .unsupported:
            status = .unsupported
        case .resetting, .unknown:
            break
        @unknown default:
            break
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else {
            logDebug("failed to add BLE service: \(error!)")
            return
        }
        startAdvertising()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == AliControlBleProfile.stateCharacteristicUUID else { return }
        subscribedCentral = central
        status = .subscribed
        sendState(.state(status: "connected"))
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == AliControlBleProfile.stateCharacteristicUUID else { return }
        if subscribedCentral === central {
            subscribedCentral = nil
        }
        pendingNotifications.removeAll()
        status = .advertising
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        // CoreBluetooth treats `requests` as one batch: respond(to:withResult:)
        // must be called exactly once per didReceiveWrite invocation (using
        // one representative request), never once per element in the array —
        // Android's simple single-characteristic write only ever produces one
        // request here in practice, but this stays correct if that changes.
        guard let first = requests.first else { return }

        guard requests.allSatisfy({ $0.characteristic.uuid == AliControlBleProfile.commandCharacteristicUUID }) else {
            peripheral.respond(to: first, withResult: .attributeNotFound)
            return
        }

        // ATT-level ack happens up front regardless of payload validity —
        // the application-level ack (a state notification) is a separate
        // message sent afterwards, once each payload has been parsed.
        peripheral.respond(to: first, withResult: .success)

        for request in requests {
            guard let data = request.value else {
                logDebug("received write request with no payload")
                sendState(.state(status: "error"))
                continue
            }
            guard let message = ProtocolMessage.decode(data) else {
                // String(decoding:as:) never throws — invalid UTF-8 bytes
                // become U+FFFD rather than crashing this handler, so it's
                // safe to log even a genuinely garbled/binary payload.
                let payload = String(decoding: data, as: UTF8.self)
                logDebug("received unparseable command payload: \(payload)")
                sendState(.state(status: "error"))
                continue
            }
            sendState(dispatcher.dispatch(message))
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushPendingNotifications()
    }
}
