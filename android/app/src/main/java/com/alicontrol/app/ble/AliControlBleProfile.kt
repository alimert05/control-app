package com.alicontrol.app.ble

import java.util.UUID

/**
 * Stable BLE GATT identifiers for the Ali Control protocol.
 *
 * The Android tablet is always the BLE central/client. The desktop agent
 * (Windows) is the BLE peripheral/server: it advertises the Ali Control service
 * and exposes [COMMAND_CHARACTERISTIC_UUID] (written by the tablet) and
 * [STATE_CHARACTERISTIC_UUID] (notified by the desktop).
 *
 * Nothing here is specific to any desktop operating system — this is the one
 * place these values are defined, and every transport/agent must agree on them.
 * The service and characteristic UUIDs are unchanged since the original
 * protocol and are preserved on Windows.
 */
object AliControlBleProfile {

    /** The Ali Control GATT service. The agent advertises it whenever the adapter allows. */
    val SERVICE_UUID: UUID = UUID.fromString("7eb4052b-fc5f-436f-ae69-c3c9e7e9809f")

    /**
     * A compact (32-bit) alias of the service, advertised *instead of*
     * [SERVICE_UUID] by an agent whose Bluetooth adapter/driver refuses to
     * advertise a connectable 128-bit service UUID (the Windows advertisement
     * status goes to `Aborted`). The agent then hosts the **same** characteristics
     * under this alias service, so everything after discovery is identical.
     * Must equal the agent's `AliControlBleProfile.DiscoveryServiceUuid`.
     */
    val DISCOVERY_SERVICE_UUID: UUID = UUID.fromString("a11c0e01-0000-1000-8000-00805f9b34fb")

    /** Every service UUID a scan looks for (OR-ed): the full UUID first, then the compact alias. */
    val SCAN_SERVICE_UUIDS: List<UUID> = listOf(SERVICE_UUID, DISCOVERY_SERVICE_UUID)

    /** Written by the tablet (central) to send a [com.alicontrol.app.ble.ProtocolMessage] command. */
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("6544209f-88b6-432e-9db1-c672b7a31916")

    /** Notified by the desktop (peripheral) with state/telemetry [com.alicontrol.app.ble.ProtocolMessage]s. */
    val STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("71dbc91d-eb99-4a50-b875-c3f045f4ea91")

    /** Standard BLE Client Characteristic Configuration descriptor (enables notifications). */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Picks the Ali Control service out of a connected device's GATT services:
     * the full-UUID service if the device exposes it, otherwise the compact
     * discovery service. Pure (takes the lookup as a function) so the rule is
     * unit-tested without a Bluetooth stack.
     */
    fun <T> pickService(lookup: (UUID) -> T?): T? = SCAN_SERVICE_UUIDS.firstNotNullOfOrNull { lookup(it) }
}
