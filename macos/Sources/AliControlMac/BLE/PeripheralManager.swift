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
///
/// Startup is fully automatic and event-driven: `init` creates the
/// `CBPeripheralManager` immediately, and `peripheralManagerDidUpdateState`
/// registers the service and starts advertising itself the moment Bluetooth
/// reports `.poweredOn` — there is no "Start Server" step and no polling for
/// readiness.
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

    /// CoreBluetooth's peripheral role exposes no friendly name for a
    /// connected central (`CBCentral` has no `.name`) — this is its
    /// identifier, shortened, for the menu's "Tablet: Connected (…)" line.
    private(set) var subscribedCentralIdentifier: String?

    private let dispatcher: CommandDispatcher
    private let livePublisher: LiveStatePublisher
    private var peripheralManager: CBPeripheralManager!
    private var stateCharacteristic: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?

    /// Command acks and live metrics/media/track updates are high priority;
    /// artwork chunks are low priority, so a large image transfer can never
    /// make a button press feel unresponsive — see `PriorityNotificationQueue`.
    private let notificationQueue = PriorityNotificationQueue()
    private(set) var activeArtworkTransferId: String?
    private var isArtworkFlushScheduled = false
    private var artworkFlushGeneration = 0

    /// Latched so a central with an unexpectedly small MTU can't turn a
    /// dropped notification into a per-tick log loop.
    private var hasLoggedOversizedNotification = false

    init(
        dispatcher: CommandDispatcher = CommandDispatcher(),
        livePublisher: LiveStatePublisher = LiveStatePublisher()
    ) {
        self.dispatcher = dispatcher
        self.livePublisher = livePublisher
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

        // The publisher samples on its own serial queue; every BLE call has
        // to happen back on the main queue, where CoreBluetooth delivers its
        // delegate callbacks for this manager.
        livePublisher.onMessage = { [weak self] data in
            DispatchQueue.main.async { self?.sendLiveState(data) }
        }
        livePublisher.onArtworkChanged = { [weak self] artworkId in
            DispatchQueue.main.async {
                guard let self, self.activeArtworkTransferId != artworkId else { return }
                self.cancelArtworkTransfer()
            }
        }
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
        logDebug("advertising as \"\(localName)\"")
    }

    /// Manually restarts advertising — a lightweight recovery option (exposed
    /// as a menu item) if it ever appears stuck, without requiring a full app
    /// relaunch. A no-op unless Bluetooth is actually on; never loops or
    /// retries on its own.
    func restartAdvertising() {
        guard peripheralManager.state == .poweredOn else { return }
        peripheralManager.stopAdvertising()
        if stateCharacteristic == nil {
            addServiceIfNeeded()
        } else {
            startAdvertising()
        }
    }

    // MARK: - Sending state/ack messages

    private func sendState(_ message: ProtocolMessage) {
        notificationQueue.enqueueHighPriority(message.encoded())
        flushPendingNotifications()
    }

    /// Queues one live-state notification (telemetry/media), after checking
    /// it actually fits.
    ///
    /// `LiveWireLimits` already bounds every field so an oversized payload is
    /// impossible by construction — this is the backstop that guarantees the
    /// stack is never handed something it would silently truncate mid-JSON
    /// (and possibly mid-UTF-8) if that ever stopped being true, e.g. if a
    /// central negotiated an unexpectedly small MTU. Dropping one telemetry
    /// update is always better than corrupting the state stream: another
    /// sample follows two seconds later.
    private func sendLiveState(_ data: Data) {
        guard let central = subscribedCentral else { return }

        let limit = min(central.maximumUpdateValueLength, LiveWireLimits.maxNotificationBytes)
        guard data.count <= limit else {
            if !hasLoggedOversizedNotification {
                hasLoggedOversizedNotification = true
                logDebug("dropping \(data.count)B live-state notification — exceeds \(limit)B limit")
            }
            return
        }

        notificationQueue.enqueueHighPriority(data)
        flushPendingNotifications()
    }

    /// Handles an `artwork_request`: validates the id, looks it up in the
    /// live publisher's cache, and — only if both succeed — replaces
    /// whatever artwork transfer might already be queued with this one.
    ///
    /// "Only a known artworkId" is enforced structurally, not by a
    /// permission check: [ArtworkPipeline.lookup] is a dictionary read
    /// keyed by content hash. There is no code path from this string to a
    /// filesystem path, a filename, or a URL — an id that isn't already in
    /// the cache simply finds nothing, and nothing is sent.
    private func handleArtworkRequest(_ message: ArtworkRequestMessage) {
        guard let artwork = livePublisher.processedArtwork(for: message.artworkId) else {
            logDebug("artwork_request for unknown id — ignored")
            return
        }
        guard let central = subscribedCentral else { return }

        let chunkPayloadSize = ArtworkTransferSession.chunkPayloadSize(maximumUpdateValueLength: central.maximumUpdateValueLength)
        guard let frames = ArtworkTransferSession.buildFrames(
            artworkId: artwork.artworkId,
            mimeType: artwork.mimeType,
            payload: artwork.data,
            maxChunkPayloadBytes: chunkPayloadSize
        ) else {
            logDebug("could not build artwork frames for \(message.artworkId) (chunk payload \(chunkPayloadSize)B)")
            return
        }
        guard ArtworkTransferSession.frames(frames, fitWithin: central.maximumUpdateValueLength) else {
            logDebug("could not send artwork frames within negotiated \(central.maximumUpdateValueLength)B limit")
            return
        }

        // A new transfer wholesale-replaces any transfer still queued —
        // whether that's a genuine re-request or Android having moved on to
        // a different track's artwork, the old one is no longer wanted.
        activeArtworkTransferId = artwork.artworkId
        notificationQueue.replaceLowPriority(with: frames)
        flushPendingNotifications()
    }

    /// Drives `notificationQueue` against the real `CBPeripheralManager`.
    /// `updateValue(_:for:onSubscribedCentrals:)` can return false if the
    /// underlying transmit queue is full; the correct response (per Apple's
    /// docs) is to hold the value and retry from
    /// `peripheralManagerIsReady(toUpdateSubscribers:)`, not to drop it — see
    /// `PriorityNotificationQueue.flush(send:)` for the (separately tested)
    /// pause/resume and priority behavior this delegates to.
    private func flushPendingNotifications() {
        guard let stateCharacteristic else { return }
        let accepted = notificationQueue.flush { data in
            peripheralManager.updateValue(data, for: stateCharacteristic, onSubscribedCentrals: nil)
        }
        if !accepted {
            // Invalidate any already-enqueued run-loop continuation. Once
            // CoreBluetooth says false, *nothing* retries until its readiness
            // delegate callback explicitly calls this method again.
            artworkFlushGeneration += 1
            isArtworkFlushScheduled = false
            return
        }
        if !notificationQueue.isLowPriorityActive {
            activeArtworkTransferId = nil
            return
        }

        // When there was no CoreBluetooth backpressure, yield one main-run-
        // loop turn before the next artwork frame. That gives incoming
        // command writes and their high-priority ACKs a deterministic chance
        // to jump ahead. When updateValue returned false, schedule nothing:
        // only peripheralManagerIsReady may resume the exact retained frame.
        if accepted && !isArtworkFlushScheduled {
            isArtworkFlushScheduled = true
            artworkFlushGeneration += 1
            let generation = artworkFlushGeneration
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard self.artworkFlushGeneration == generation else { return }
                self.isArtworkFlushScheduled = false
                self.flushPendingNotifications()
            }
        }
    }

    /// Drops any in-flight/queued artwork transfer without sending an
    /// `artwork_end` — used on disconnect and unsubscribe. Android's own
    /// disconnect handling independently discards its receive buffer, so no
    /// explicit "cancelled" message is needed on the wire.
    private func cancelArtworkTransfer() {
        notificationQueue.clearLowPriority()
        activeArtworkTransferId = nil
        artworkFlushGeneration += 1
        isArtworkFlushScheduled = false
    }

    /// Local stderr line for debugging — never sent over BLE, and only ever
    /// written on an actual state change/error, never on a timer or loop.
    private func logDebug(_ message: String) {
        FileHandle.standardError.write(Data("Ali Control: \(message)\n".utf8))
    }
}

// MARK: - CBPeripheralManagerDelegate

extension PeripheralManager: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        logDebug("Bluetooth state: \(peripheral.state.aliControlDescription)")
        switch peripheral.state {
        case .poweredOn:
            addServiceIfNeeded()
        case .poweredOff:
            // The system drops any previously-added services/advertisement
            // when Bluetooth powers off (this also covers the Mac sleeping
            // with Bluetooth hardware suspended) — clear local state to
            // match, so addServiceIfNeeded() re-adds everything when it
            // powers back on instead of short-circuiting on a stale
            // characteristic reference.
            stateCharacteristic = nil
            subscribedCentral = nil
            subscribedCentralIdentifier = nil
            notificationQueue.clearAll()
            activeArtworkTransferId = nil
            livePublisher.stop()
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
        subscribedCentralIdentifier = String(central.identifier.uuidString.prefix(8))
        hasLoggedOversizedNotification = false
        status = .subscribed
        logDebug("tablet connected (\(subscribedCentralIdentifier ?? "?"))")
        sendState(.state(status: "connected"))
        // Telemetry exists only while someone is listening: sampling starts
        // here and stops again the moment the tablet unsubscribes.
        livePublisher.start()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == AliControlBleProfile.stateCharacteristicUUID else { return }
        if subscribedCentral === central {
            subscribedCentral = nil
            subscribedCentralIdentifier = nil
        }
        notificationQueue.clearAll()
        cancelArtworkTransfer()
        livePublisher.stop()
        status = .advertising
        logDebug("tablet disconnected")
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
            // Checked first: an artwork_request has an `artworkId` field no
            // real command carries, so this can never misfire against
            // {"type":"command",...}. Handled entirely separately from the
            // command path — no ack is sent either way, since a request
            // isn't a command and its "reply" is either an artwork transfer
            // or silence.
            if let artworkRequest = ArtworkRequestMessage.decode(data) {
                handleArtworkRequest(artworkRequest)
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
            let reply = dispatcher.dispatch(message)
            if reply.status == "error" {
                logDebug("command \"\(message.action ?? "?")\" failed")
            }
            sendState(reply)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushPendingNotifications()
    }
}

private extension CBManagerState {
    var aliControlDescription: String {
        switch self {
        case .poweredOn: return "poweredOn"
        case .poweredOff: return "poweredOff"
        case .unauthorized: return "unauthorized"
        case .unsupported: return "unsupported"
        case .resetting: return "resetting"
        case .unknown: return "unknown"
        @unknown default: return "unknown(\(rawValue))"
        }
    }
}
