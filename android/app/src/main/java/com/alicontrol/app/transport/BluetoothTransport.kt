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
import android.util.Log
import com.alicontrol.app.ble.AliControlBleProfile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BLE central/client implementation of [Transport].
 *
 * This is the only class in the app that touches the Android Bluetooth APIs
 * directly. It scans for devices advertising [AliControlBleProfile.SERVICE_UUID],
 * connects to one, and exchanges bytes over the command/state characteristics.
 *
 * [connectionState] only reaches [ConnectionState.CONNECTED] once the link is
 * genuinely usable: GATT connected, MTU negotiated, services discovered, both
 * characteristics found, and the state-characteristic notification descriptor
 * write has been *confirmed* (not just issued) — see [ConnectionState.PREPARING].
 *
 * After an unexpectedly dropped connection (not a user-initiated disconnect),
 * this attempts a small bounded number of reconnects to the same device with
 * increasing delays — see [scheduleReconnect]. It never scans in the
 * background to do this (it reconnects directly by device address) and it
 * never retries forever.
 */
class BluetoothTransport(context: Context) : Transport {

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, PREPARING, CONNECTED }

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

    /** The negotiated ATT MTU, used to reject outgoing messages that wouldn't fit. */
    @Volatile private var negotiatedMtu: Int = DEFAULT_ATT_MTU

    /** The device to retry against — remembered so reconnects never need a fresh scan. */
    @Volatile private var lastDevice: BluetoothDevice? = null
    @Volatile private var userInitiatedDisconnect = false
    @Volatile private var autoReconnecting = false
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnectRunnable: Runnable? = null

    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    override val isConnected: Boolean
        get() = isTransportReady(connectionState)

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
     * [SCAN_TIMEOUT_MS] if [stopScan] isn't called first. This is the only
     * scan entry point in the app — reconnects never trigger one.
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
                Log.w(TAG, "Scan failed (code $errorCode)")
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

    /**
     * Connects to a device discovered via [startScan]. BLE-specific: not part
     * of [Transport]. Always available manually, regardless of any ongoing or
     * exhausted automatic-reconnect sequence.
     */
    fun connect(device: BluetoothDevice, listener: TransportListener) {
        addListener(listener)
        beginConnection(device, resetRetryBudget = true)
    }

    override fun disconnect() {
        userInitiatedDisconnect = true
        autoReconnecting = false
        reconnectAttempt = 0
        clearReconnectTimer()
        clearConnectTimeout()

        val wasConnected = connectionState == ConnectionState.CONNECTED
        Log.d(TAG, "Disconnecting (user-initiated)")
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

        // Never silently truncate: refuse anything the current connection
        // can't carry intact and say so, rather than writing a payload the
        // stack might cut short.
        val maxPayloadBytes = negotiatedMtu - ATT_WRITE_OVERHEAD_BYTES
        if (data.size > maxPayloadBytes) {
            Log.w(TAG, "Refusing to send ${data.size}B message — exceeds ${maxPayloadBytes}B usable payload at MTU $negotiatedMtu")
            notifyError("Message too large for current connection")
            return
        }

        characteristic.value = data
        activeGatt.writeCharacteristic(characteristic)
    }

    // -- Connection lifecycle --

    /**
     * Guards the async GATT callbacks against acting on a stale connection
     * attempt: if a callback for an abandoned `gatt` (e.g. one we already
     * gave up on via a connect timeout, or superseded by a newer connect/
     * reconnect call) arrives after the fact — a real possibility, since
     * `close()` doesn't retroactively cancel Binder messages already
     * in flight — it must not be allowed to resurrect state like flipping
     * back to CONNECTED for a link nothing is currently using.
     */
    private fun isCurrentGatt(g: BluetoothGatt): Boolean = gatt === g

    private fun beginConnection(device: BluetoothDevice, resetRetryBudget: Boolean) {
        stopScan()
        clearConnectTimeout()
        clearReconnectTimer()
        gatt?.close()
        gatt = null
        commandCharacteristic = null
        negotiatedMtu = DEFAULT_ATT_MTU

        lastDevice = device
        userInitiatedDisconnect = false
        if (resetRetryBudget) {
            reconnectAttempt = 0
            autoReconnecting = false
        }
        connectionState = ConnectionState.CONNECTING
        remoteName = device.name

        Log.d(TAG, if (resetRetryBudget) "Connecting to ${device.address}" else "Reconnecting to ${device.address} (attempt $reconnectAttempt)")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        val timeout = Runnable {
            if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.PREPARING) {
                handleFailure("Connection timed out")
            }
        }
        connectTimeoutRunnable = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    /**
     * Schedules one bounded reconnect attempt against [lastDevice], with an
     * increasing delay per attempt. Stops on its own once [RECONNECT_DELAYS_MS]
     * is exhausted — never loops forever, never scans.
     */
    private fun scheduleReconnect() {
        val device = lastDevice
        if (device == null || reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            if (autoReconnecting) Log.d(TAG, "Giving up reconnecting after $reconnectAttempt attempt(s)")
            autoReconnecting = false
            return
        }
        autoReconnecting = true
        val delay = RECONNECT_DELAYS_MS[reconnectAttempt]
        reconnectAttempt++
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempt/${RECONNECT_DELAYS_MS.size} in ${delay}ms")

        val runnable = Runnable {
            if (adapter?.isEnabled == true) {
                beginConnection(device, resetRetryBudget = false)
            } else {
                Log.d(TAG, "Skipping reconnect attempt — Bluetooth is off")
                autoReconnecting = false
            }
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun clearReconnectTimer() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun handleGattDisconnected(g: BluetoothGatt) {
        val wasFullyConnected = connectionState == ConnectionState.CONNECTED
        clearConnectTimeout()
        commandCharacteristic = null
        connectionState = ConnectionState.DISCONNECTED
        remoteName = null
        g.close()
        if (gatt === g) gatt = null

        val shouldRetry = shouldScheduleReconnect(
            userInitiatedDisconnect = userInitiatedDisconnect,
            wasFullyConnected = wasFullyConnected,
            autoReconnecting = autoReconnecting
        )

        Log.d(TAG, "GATT disconnected (wasConnected=$wasFullyConnected, willRetry=$shouldRetry)")

        if (wasFullyConnected) {
            notifyDisconnected()
        } else if (!shouldRetry) {
            notifyError("Failed to connect")
        }

        if (shouldRetry) scheduleReconnect() else autoReconnecting = false
    }

    private fun handleFailure(message: String) {
        clearConnectTimeout()
        gatt?.close()
        gatt = null
        commandCharacteristic = null
        connectionState = ConnectionState.DISCONNECTED
        remoteName = null

        val shouldRetry = !userInitiatedDisconnect && autoReconnecting
        Log.w(TAG, "$message (willRetry=$shouldRetry)")
        notifyError(message)
        if (shouldRetry) scheduleReconnect() else autoReconnecting = false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                // Negotiate a larger ATT MTU before touching services. The
                // default (23, i.e. 20 usable bytes per write/notification)
                // silently truncates every protocol message — they're all
                // 30+ bytes. discoverServices() runs from onMtuChanged(), not
                // here, so the two GATT operations never overlap
                // (BluetoothGatt only tolerates one outstanding operation at
                // a time). connectionState becomes PREPARING here, not
                // CONNECTED — see the class doc for what "genuinely ready"
                // means.
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState = ConnectionState.PREPARING
                    g.requestMtu(DESIRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleGattDisconnected(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(g)) return
            // Proceed regardless of the negotiated result (or a failure) —
            // even the pre-negotiation default still lets service discovery
            // itself work; send() independently refuses any message that
            // wouldn't fit the MTU actually in effect, so nothing is ever
            // silently truncated even if negotiation didn't improve on it.
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.d(TAG, "MTU negotiated: $mtu")
            } else {
                Log.w(TAG, "MTU negotiation failed (status $status), staying at $negotiatedMtu")
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(g)) return
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

            if (!enableNotifications(g, stateCharacteristic)) {
                handleFailure("Could not enable state notifications on device")
                return
            }
            // Stays PREPARING until onDescriptorWrite confirms the CCCD
            // write actually succeeded — that's what makes CONNECTED mean
            // "the transport can really send commands now".
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrentGatt(g)) return
            if (descriptor.uuid != AliControlBleProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleFailure("Failed to enable notifications (status $status)")
                return
            }
            clearConnectTimeout()
            reconnectAttempt = 0
            autoReconnecting = false
            connectionState = ConnectionState.CONNECTED
            Log.d(TAG, "Connected and ready (device=${remoteName ?: "unknown"}, mtu=$negotiatedMtu)")
            notifyConnected()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == AliControlBleProfile.STATE_CHARACTERISTIC_UUID) {
                notifyDataReceived(characteristic.value ?: ByteArray(0))
            }
        }
    }

    /** Returns whether the descriptor write was successfully queued — not whether it will succeed. */
    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(AliControlBleProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return g.writeDescriptor(descriptor)
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
        private const val TAG = "BluetoothTransport"

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** ATT MTU before any negotiation — 20 usable bytes, too small for any protocol message. */
        private const val DEFAULT_ATT_MTU = 23

        /** ATT header overhead (1-byte opcode + 2-byte handle) subtracted from the MTU to get usable payload. */
        private const val ATT_WRITE_OVERHEAD_BYTES = 3

        /**
         * Requested ATT MTU. 185 is a conservative, broadly-supported value
         * (well within what Bluetooth 4.0+ controllers negotiate) that still
         * leaves comfortable headroom over the protocol's longest message
         * (~43 bytes for the longest command today). The framework clamps to
         * whatever the peripheral actually supports if it's less.
         */
        private const val DESIRED_MTU = 185

        /**
         * Bounded, increasing reconnect delays after an unexpected drop.
         * Exactly 3 attempts, never more — after this the user must tap
         * Connect manually. No infinite loop, no background scanning.
         */
        val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

        /** Pure: whether [ConnectionState] represents a transport that can actually send commands. */
        fun isTransportReady(state: ConnectionState): Boolean = state == ConnectionState.CONNECTED

        /**
         * Pure reconnect decision logic, exposed for testing without a real
         * GATT callback. A retry is warranted when the disconnect wasn't
         * requested by the user, and either the link had reached full
         * readiness before dropping, or we're already partway through a
         * bounded auto-reconnect sequence (so a failed retry attempt itself
         * still counts toward — and can continue — that same sequence).
         */
        fun shouldScheduleReconnect(userInitiatedDisconnect: Boolean, wasFullyConnected: Boolean, autoReconnecting: Boolean): Boolean =
            !userInitiatedDisconnect && (wasFullyConnected || autoReconnecting)

        /** Pure: the delay for the next reconnect attempt, or null once the budget is exhausted. */
        fun nextReconnectDelayMs(attempt: Int, delays: LongArray = RECONNECT_DELAYS_MS): Long? =
            delays.getOrNull(attempt)

        /** Pure: whether a message of [messageSizeBytes] would fit the given negotiated [mtu]. */
        fun exceedsMtuPayload(messageSizeBytes: Int, mtu: Int, attOverheadBytes: Int = ATT_WRITE_OVERHEAD_BYTES): Boolean =
            messageSizeBytes > (mtu - attOverheadBytes)
    }
}
