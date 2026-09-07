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
import com.alicontrol.app.transport.BluetoothTransport
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.transport.TransportManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var transportManager: TransportManager

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

        setupControlCards()
        binding.connectButton.setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        binding.disconnectButton.setOnClickListener {
            transportManager.bluetooth.disconnect()
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        transportManager.bluetooth.addListener(transportListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        transportManager.bluetooth.removeListener(transportListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun refreshStatus() {
        val bluetooth = transportManager.bluetooth
        val connected = bluetooth.isConnected
        val deviceName = bluetooth.remoteName

        binding.deviceNameValue.text = deviceName.orEmpty()
        binding.deviceNameValue.visibility = if (connected && !deviceName.isNullOrBlank()) View.VISIBLE else View.GONE

        binding.connectionStatusValue.text = statusText(bluetooth.connectionState)

        binding.connectButton.visibility = if (connected) View.GONE else View.VISIBLE
        binding.disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun statusText(state: BluetoothTransport.ConnectionState): String = when (state) {
        BluetoothTransport.ConnectionState.CONNECTED -> getString(R.string.status_connected)
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

    private fun sendCommand(action: String) {
        if (!transportManager.bluetooth.isConnected) {
            Toast.makeText(this, R.string.error_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        transportManager.bluetooth.send(ProtocolMessage.command(action).toBytes())
    }
}
