package com.alicontrol.app.transport

/**
 * Common contract for a communication channel between this tablet and a desktop
 * agent. [BluetoothTransport] is the first implementation (TASK-002); future
 * USB and Wi-Fi transports plug in the same way without any change to the
 * code that only talks to this interface (e.g. the main UI).
 *
 * Acquiring a connection (scanning, picking a target device) is inherently
 * transport-specific and is therefore NOT part of this interface — see
 * [BluetoothTransport] for BLE scanning/connect-to-device.
 */
interface Transport {

    val type: TransportType

    val isConnected: Boolean

    /** Human-readable name of the currently connected remote, if any. */
    val remoteName: String?

    fun addListener(listener: TransportListener)

    fun removeListener(listener: TransportListener)

    fun disconnect()

    fun send(data: ByteArray)
}

interface TransportListener {
    fun onConnected()
    fun onDisconnected()
    fun onDataReceived(data: ByteArray)
    fun onError(message: String)
}

enum class TransportType {
    BLUETOOTH,
    USB,
    WIFI
}
