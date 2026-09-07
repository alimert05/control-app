package com.alicontrol.app.transport

/**
 * Common contract for a communication channel between this tablet and a desktop
 * agent. No implementation exists yet — Bluetooth, USB and Wi-Fi transports are
 * built in later tasks against this same interface.
 */
interface Transport {

    val type: TransportType

    val isConnected: Boolean

    fun connect(listener: TransportListener)

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
