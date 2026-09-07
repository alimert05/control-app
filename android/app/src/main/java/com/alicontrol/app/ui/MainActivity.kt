package com.alicontrol.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alicontrol.app.AliControlApp
import com.alicontrol.app.R
import com.alicontrol.app.ble.ProtocolMessage
import com.alicontrol.app.databinding.ActivityMainBinding
import com.alicontrol.app.kiosk.KioskController
import com.alicontrol.app.transport.BluetoothTransport
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.transport.TransportManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var transportManager: TransportManager
    private lateinit var kioskController: KioskController

    /** Last BLE error, shown alongside the disconnected state until the next connect attempt. */
    private var lastErrorMessage: String? = null

    private val transportListener = object : TransportListener {
        override fun onConnected() {
            lastErrorMessage = null
            refreshStatus()
        }

        override fun onDisconnected() = refreshStatus()

        override fun onDataReceived(data: ByteArray) {
            val message = ProtocolMessage.fromBytes(data) ?: return
            if (message.type != ProtocolMessage.TYPE_STATE) return
            val action = message.action ?: return
            val status = message.status ?: return
            Toast.makeText(this@MainActivity, getString(R.string.mac_ack_format, action, status), Toast.LENGTH_SHORT).show()
        }

        override fun onError(message: String) {
            lastErrorMessage = message
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transportManager = (application as AliControlApp).transportManager
        kioskController = KioskController(this)

        setupControlCards()
        binding.connectButton.setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        binding.disconnectButton.setOnClickListener {
            transportManager.bluetooth.disconnect()
        }
        binding.deviceSettingsButton.setOnClickListener {
            startActivity(Intent(this, DeviceSettingsActivity::class.java))
        }
        binding.moreActionsButton.setOnClickListener {
            startActivity(Intent(this, ActionsActivity::class.java))
        }

        kioskController.attachEscapeHatch(binding.headerTextColumn, window)
    }

    override fun onResume() {
        super.onResume()
        kioskController.applyImmersiveMode(window)
        kioskController.applyKeepAwake(window)
        transportManager.bluetooth.addListener(transportListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        transportManager.bluetooth.removeListener(transportListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) kioskController.applyImmersiveMode(window)
    }

    /**
     * Kiosk: this is the app's home screen, so back press must not exit it —
     * use the escape hatch (long-press the connection status text) to reach
     * Home/Recents/Settings during development instead.
     */
    override fun onBackPressed() = Unit

    private fun refreshStatus() {
        val bluetooth = transportManager.bluetooth
        val connected = bluetooth.isConnected
        val deviceName = bluetooth.remoteName

        binding.deviceNameValue.text = deviceName.orEmpty()
        binding.deviceNameValue.visibility = if (connected && !deviceName.isNullOrBlank()) View.VISIBLE else View.GONE

        binding.connectionStatusValue.text = statusText(bluetooth.connectionState)

        binding.connectButton.visibility = if (connected) View.GONE else View.VISIBLE
        binding.disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE

        setControlCardsEnabled(connected)
    }

    private fun statusText(state: BluetoothTransport.ConnectionState): String = when (state) {
        BluetoothTransport.ConnectionState.CONNECTED -> getString(R.string.status_connected)
        BluetoothTransport.ConnectionState.PREPARING -> getString(R.string.status_preparing)
        BluetoothTransport.ConnectionState.CONNECTING -> getString(R.string.status_connecting)
        BluetoothTransport.ConnectionState.SCANNING -> getString(R.string.status_scanning)
        BluetoothTransport.ConnectionState.DISCONNECTED -> {
            val error = lastErrorMessage
            if (error != null) getString(R.string.status_connection_error, error) else getString(R.string.status_disconnected)
        }
    }

    private fun setupControlCards() {
        val listener = View.OnClickListener { view ->
            val action = view.tag as? String ?: return@OnClickListener
            sendCommand(action)
        }
        binding.cardVolumeUp.setOnClickListener(listener)
        binding.cardVolumeDown.setOnClickListener(listener)
        binding.cardMute.setOnClickListener(listener)
        binding.cardPlayPause.setOnClickListener(listener)
        binding.cardOpenTerminal.setOnClickListener(listener)
    }

    /** Visually dims and disables the command cards when nothing is connected — sendCommand() still guards too. */
    private fun setControlCardsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        for (card in listOf(binding.cardVolumeUp, binding.cardVolumeDown, binding.cardMute, binding.cardPlayPause, binding.cardOpenTerminal)) {
            card.isEnabled = enabled
            card.alpha = alpha
        }
    }

    private fun sendCommand(action: String) {
        if (!transportManager.bluetooth.isConnected) {
            Toast.makeText(this, R.string.error_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        transportManager.bluetooth.send(ProtocolMessage.command(action).toBytes())
    }
}
