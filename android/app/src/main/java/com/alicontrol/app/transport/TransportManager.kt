package com.alicontrol.app.transport

/**
 * Owns whichever [Transport] is currently active. No concrete transport is
 * registered yet, so this always reports disconnected until a future task
 * plugs in a BluetoothTransport/UsbTransport/WifiTransport implementation.
 */
class TransportManager {

    private var active: Transport? = null

    val isConnected: Boolean
        get() = active?.isConnected == true

    fun statusText(): String =
        if (isConnected) "Connected" else "No device connected"
}
