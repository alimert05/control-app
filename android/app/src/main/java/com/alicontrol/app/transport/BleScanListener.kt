package com.alicontrol.app.transport

import android.bluetooth.BluetoothDevice

/**
 * Callback for [BluetoothTransport.startScan]. BLE-specific — the connect flow
 * UI (which needs to list discovered devices) talks to [BluetoothTransport]
 * directly rather than through the transport-agnostic [Transport] interface.
 */
interface BleScanListener {
    fun onDeviceFound(device: BluetoothDevice, rssi: Int)
    fun onScanFailed(reason: String)
}
