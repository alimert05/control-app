package com.alicontrol.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.alicontrol.app.AliControlApp
import com.alicontrol.app.R
import com.alicontrol.app.databinding.ActivityConnectBinding
import com.alicontrol.app.kiosk.KioskController
import com.alicontrol.app.transport.BleScanListener
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.transport.TransportManager

/**
 * BLE-specific device connection flow: request the location permission BLE
 * scanning needs on API 27, scan for Ali Control peripherals, and connect to
 * the one the user picks. Nothing here is reused by [MainActivity], which
 * only ever reads the transport-agnostic [TransportManager] status.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var transportManager: TransportManager
    private lateinit var kioskController: KioskController

    private val discovered = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var adapter: ArrayAdapter<String>

    private var isConnecting = false

    private val scanListener = object : BleScanListener {
        override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
            if (discovered.put(device.address, device) == null) {
                runOnUiThread { refreshDeviceList() }
            }
        }

        override fun onScanFailed(reason: String) {
            runOnUiThread { showMessage(reason, showRetry = true) }
        }
    }

    private val connectListener = object : TransportListener {
        override fun onConnected() {
            runOnUiThread {
                isConnecting = false
                setResult(RESULT_OK)
                finish()
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                if (isConnecting) {
                    isConnecting = false
                    showMessage(getString(R.string.connect_failed, getString(R.string.status_disconnected)), showRetry = true)
                }
            }
        }

        override fun onDataReceived(data: ByteArray) = Unit

        override fun onError(message: String) {
            runOnUiThread {
                isConnecting = false
                showMessage(getString(R.string.connect_failed, message), showRetry = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transportManager = (application as AliControlApp).transportManager
        kioskController = KioskController(this)

        adapter = ArrayAdapter(this, R.layout.item_ble_device, mutableListOf())
        binding.deviceList.adapter = adapter
        binding.deviceList.setOnItemClickListener { _, _, position, _ ->
            val device = discovered.values.toList().getOrNull(position) ?: return@setOnItemClickListener
            connectTo(device)
        }

        binding.closeButton.setOnClickListener { finish() }

        kioskController.attachEscapeHatch(binding.screenTitle, window)
    }

    override fun onResume() {
        super.onResume()
        kioskController.applyImmersiveMode(window)
        kioskController.applyKeepAwake(window)
        if (!isConnecting) startFlow()
    }

    override fun onPause() {
        super.onPause()
        transportManager.bluetooth.stopScan()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) kioskController.applyImmersiveMode(window)
    }

    override fun onDestroy() {
        super.onDestroy()
        transportManager.bluetooth.removeListener(connectListener)
    }

    @Suppress("DEPRECATION")
    private fun startFlow() {
        when {
            !transportManager.bluetooth.isBluetoothSupported -> {
                showMessage(getString(R.string.bluetooth_unsupported), showRetry = false)
            }
            !transportManager.bluetooth.isBluetoothEnabled -> {
                showMessage(getString(R.string.bluetooth_disabled_message), showRetry = true, retryLabel = R.string.action_enable_bluetooth) {
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
                }
            }
            !hasScanPermission() -> {
                showMessage(getString(R.string.permission_required_message), showRetry = true, retryLabel = R.string.action_grant_permission) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION_PERMISSION)
                }
            }
            else -> beginScan()
        }
    }

    private fun beginScan() {
        discovered.clear()
        refreshDeviceList()
        showMessage(getString(R.string.scanning_message), showRetry = false)
        transportManager.bluetooth.startScan(scanListener)
    }

    private fun connectTo(device: BluetoothDevice) {
        transportManager.bluetooth.stopScan()
        isConnecting = true
        showMessage(getString(R.string.connecting_to_device, device.name ?: device.address), showRetry = false)
        transportManager.bluetooth.connect(device, connectListener)
    }

    private fun refreshDeviceList() {
        adapter.clear()
        adapter.addAll(discovered.values.map { it.name ?: it.address })
        adapter.notifyDataSetChanged()
    }

    private fun showMessage(message: String, showRetry: Boolean, retryLabel: Int = R.string.action_scan_again, onRetry: (() -> Unit)? = null) {
        binding.statusMessage.text = message
        binding.actionButton.visibility = if (showRetry) View.VISIBLE else View.GONE
        binding.actionButton.text = getString(retryLabel)
        binding.actionButton.setOnClickListener { (onRetry ?: { startFlow() })() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) startFlow()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) startFlow()
    }

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQUEST_ENABLE_BLUETOOTH = 1001
        private const val REQUEST_LOCATION_PERMISSION = 1002
    }
}
