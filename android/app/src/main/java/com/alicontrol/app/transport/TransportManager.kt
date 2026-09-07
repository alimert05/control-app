package com.alicontrol.app.transport

import android.content.Context

/**
 * Owns the app's single active transport. TASK-002 wires in [BluetoothTransport]
 * as the only implementation; a future UsbTransport/WifiTransport would be
 * added here the same way, without any change to how the UI reads status.
 */
class TransportManager(context: Context) {

    val bluetooth: BluetoothTransport = BluetoothTransport(context.applicationContext)

    val isConnected: Boolean
        get() = bluetooth.isConnected

    fun statusText(): String =
        if (bluetooth.isConnected) "Connected" else "No device connected"
}
