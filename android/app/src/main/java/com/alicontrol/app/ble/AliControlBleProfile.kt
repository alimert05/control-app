package com.alicontrol.app.ble

import java.util.UUID

/**
 * Stable BLE GATT identifiers for the Ali Control protocol.
 *
 * The Android tablet is always the BLE central/client. A desktop agent
 * (macOS in TASK-003, Windows later) is the BLE peripheral/server: it must
 * advertise [SERVICE_UUID] and expose [COMMAND_CHARACTERISTIC_UUID] (written
 * by the tablet) and [STATE_CHARACTERISTIC_UUID] (notified by the desktop).
 *
 * Nothing here is specific to any desktop operating system — this is the one
 * place these values are defined, and every transport/agent must agree on them.
 */
object AliControlBleProfile {

    /** Advertised by every Ali Control desktop peripheral. Used to filter BLE scans. */
    val SERVICE_UUID: UUID = UUID.fromString("7eb4052b-fc5f-436f-ae69-c3c9e7e9809f")

    /** Written by the tablet (central) to send a [com.alicontrol.app.ble.ProtocolMessage] command. */
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("6544209f-88b6-432e-9db1-c672b7a31916")

    /** Notified by the desktop (peripheral) with state/telemetry [com.alicontrol.app.ble.ProtocolMessage]s. */
    val STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("71dbc91d-eb99-4a50-b875-c3f045f4ea91")

    /** Standard BLE Client Characteristic Configuration descriptor (enables notifications). */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
