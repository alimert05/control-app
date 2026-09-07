import CoreBluetooth

/// Stable BLE GATT identifiers for the Ali Control protocol.
///
/// These MUST exactly match
/// android/app/src/main/java/com/alicontrol/app/ble/AliControlBleProfile.kt —
/// they were defined there in TASK-002 and are reused verbatim here, not
/// regenerated. Do not change either side without changing both.
enum AliControlBleProfile {
    /// Advertised by this peripheral; the Android central scan-filters on it.
    static let serviceUUID = CBUUID(string: "7EB4052B-FC5F-436F-AE69-C3C9E7E9809F")

    /// Written by the tablet (central) to send a command.
    static let commandCharacteristicUUID = CBUUID(string: "6544209F-88B6-432E-9DB1-C672B7A31916")

    /// Notified by this peripheral with state/ack messages.
    static let stateCharacteristicUUID = CBUUID(string: "71DBC91D-EB99-4A50-B875-C3F045F4EA91")

    // The CCCD (00002902-...) isn't declared here: CoreBluetooth manages the
    // notify-enable descriptor for `.notify` characteristics automatically on
    // the peripheral side. Android's BluetoothTransport is the side that has
    // to write it explicitly, since it's the GATT client.
}
