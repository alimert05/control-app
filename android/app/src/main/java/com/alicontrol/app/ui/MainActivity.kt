package com.alicontrol.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alicontrol.app.AliControlApp
import com.alicontrol.app.R
import com.alicontrol.app.databinding.ActivityMainBinding
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.transport.TransportManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var transportManager: TransportManager

    private val transportListener = object : TransportListener {
        override fun onConnected() = refreshStatus()
        override fun onDisconnected() = refreshStatus()
        override fun onDataReceived(data: ByteArray) = Unit
        override fun onError(message: String) = refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transportManager = (application as AliControlApp).transportManager

        setupSectionCards()
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
        val connected = transportManager.isConnected
        val deviceName = transportManager.bluetooth.remoteName

        binding.deviceNameValue.text = deviceName.orEmpty()
        binding.deviceNameValue.visibility = if (connected && !deviceName.isNullOrBlank()) View.VISIBLE else View.GONE

        binding.connectionStatusValue.text = transportManager.statusText()

        binding.connectButton.visibility = if (connected) View.GONE else View.VISIBLE
        binding.disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun setupSectionCards() {
        val placeholder = View.OnClickListener { view ->
            val label = view.tag as? String ?: return@OnClickListener
            Toast.makeText(this, getString(R.string.section_coming_soon, label), Toast.LENGTH_SHORT).show()
        }

        binding.cardMedia.setOnClickListener(placeholder)
        binding.cardPc.setOnClickListener(placeholder)
        binding.cardDev.setOnClickListener(placeholder)
        binding.cardDevice.setOnClickListener(placeholder)
        binding.cardSettings.setOnClickListener(placeholder)
    }
}
