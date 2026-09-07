package com.alicontrol.app.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.alicontrol.app.ble.AliControlBleProfile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BLE central/client implementation of [Transport].
 *
 * This is the only class in the app that touches the Android Bluetooth APIs
 * directly. It scans for devices advertising [AliControlBleProfile.SERVICE_UUID],
 * connects to one, and exchanges bytes over the command/state characteristics.
 * A desktop peripheral implementing this same profile (TASK-003+) is required
 * to actually exercise the connect/send/receive path end to end.
 */
class BluetoothTransport(context: Context) : Transport {

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    override val type: TransportType = TransportType.BLUETOOTH

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<TransportListener>()

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var commandCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var scanCallback: ScanCallback? = null
    @Volatile private var connectTimeoutRunnable: Runnable? = null
    private val scanStopRunnable = Runnable { stopScan() }

    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    override val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    @Volatile override var remoteName: String? = null
        private set

    val isBluetoothSupported: Boolean
        get() = adapter != null

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    override fun addListener(listener: TransportListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    /**
     * Starts a BLE scan filtered to [AliControlBleProfile.SERVICE_UUID] only —
     * arbitrary Bluetooth accessories are never surfaced. Stops itself after
     * [SCAN_TIMEOUT_MS] if [stopScan] isn't called first.
     */
    fun startScan(listener: BleScanListener) {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            listener.onScanFailed("Bluetooth is not enabled")
            return
        }
        val scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onScanFailed("BLE scanning is not available")
            return
        }

        stopScan()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(AliControlBleProfile.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                listener.onDeviceFound(result.device, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onScanFailed("Scan failed (code $errorCode)")
            }
        }
        scanCallback = callback
        connectionState = ConnectionState.SCANNING
        scanner.startScan(filters, settings, callback)
        handler.postDelayed(scanStopRunnable, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        handler.removeCallbacks(scanStopRunnable)
        val callback = scanCallback ?: return
        scanCallback = null
        val btAdapter = adapter ?: return
        val scanner = btAdapter.bluetoothLeScanner ?: return
        scanner.stopScan(callback)
        if (connectionState == ConnectionState.SCANNING) {
            connectionState = ConnectionState.DISCONNECTED
        }
    }

    /** Connects to a device discovered via [startScan]. BLE-specific: not part of [Transport]. */
    fun connect(device: BluetoothDevice, listener: TransportListener) {
        stopScan()
        addListener(listener)
        disconnect()

        connectionState = ConnectionState.CONNECTING
        remoteName = device.name

        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        val timeout = Runnable {
            if (connectionState == ConnectionState.CONNECTING) {
                handleFailure("Connection timed out")
            }
        }
        connectTimeoutRunnable = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    override fun disconnect() {
        clearConnectTimeout()
        val wasConnected = connectionState == ConnectionState.CONNECTED
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        commandCharacteristic = null
        remoteName = null
        connectionState = ConnectionState.DISCONNECTED
        if (wasConnected) notifyDisconnected()
    }

    @Suppress("DEPRECATION")
    override fun send(data: ByteArray) {
        val activeGatt = gatt
        val characteristic = commandCharacteristic
        if (activeGatt == null || characteristic == null || !isConnected) {
            notifyError("Not connected")
            return
        }
        characteristic.value = data
        activeGatt.writeCharacteristic(characteristic)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = connectionState == ConnectionState.CONNECTED
                    clearConnectTimeout()
                    commandCharacteristic = null
                    connectionState = ConnectionState.DISCONNECTED
                    remoteName = null
                    g.close()
                    if (gatt === g) gatt = null
                    if (wasConnected) notifyDisconnected() else notifyError("Failed to connect")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleFailure("Service discovery failed (status $status)")
                return
            }
            val service = g.getService(AliControlBleProfile.SERVICE_UUID)
            if (service == null) {
                handleFailure("Ali Control service not found on device")
                return
            }
            commandCharacteristic = service.getCharacteristic(AliControlBleProfile.COMMAND_CHARACTERISTIC_UUID)
            val stateCharacteristic = service.getCharacteristic(AliControlBleProfile.STATE_CHARACTERISTIC_UUID)

            if (commandCharacteristic == null || stateCharacteristic == null) {
                handleFailure("Ali Control characteristics not found on device")
                return
            }

            enableNotifications(g, stateCharacteristic)

            clearConnectTimeout()
            connectionState = ConnectionState.CONNECTED
            notifyConnected()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == AliControlBleProfile.STATE_CHARACTERISTIC_UUID) {
                notifyDataReceived(characteristic.value ?: ByteArray(0))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(AliControlBleProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(descriptor)
    }

    private fun handleFailure(message: String) {
        clearConnectTimeout()
        gatt?.close()
        gatt = null
        commandCharacteristic = null
        connectionState = ConnectionState.DISCONNECTED
        remoteName = null
        notifyError(message)
    }

    private fun clearConnectTimeout() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    private fun notifyConnected() = handler.post { listeners.forEach { it.onConnected() } }
    private fun notifyDisconnected() = handler.post { listeners.forEach { it.onDisconnected() } }
    private fun notifyError(message: String) = handler.post { listeners.forEach { it.onError(message) } }
    private fun notifyDataReceived(data: ByteArray) = handler.post { listeners.forEach { it.onDataReceived(data) } }

    companion object {
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
