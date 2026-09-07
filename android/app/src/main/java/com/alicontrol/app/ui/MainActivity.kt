package com.alicontrol.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alicontrol.app.R
import com.alicontrol.app.databinding.ActivityMainBinding
import com.alicontrol.app.device.DeviceInfoProvider
import com.alicontrol.app.transport.TransportManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceInfo: DeviceInfoProvider
    private val transportManager = TransportManager()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceInfo = DeviceInfoProvider(applicationContext)
        setupSectionCards()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
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
        binding.connectionStatusValue.text = transportManager.statusText()

        val battery = deviceInfo.batteryPercent()
        binding.batteryValue.text = if (battery >= 0) "$battery%" else getString(R.string.value_placeholder)

        val volume = deviceInfo.volumePercent()
        binding.volumeValue.text = if (volume >= 0) "$volume%" else getString(R.string.value_placeholder)

        val brightness = deviceInfo.brightnessPercent()
        binding.brightnessValue.text = if (brightness >= 0) "$brightness%" else getString(R.string.value_placeholder)
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

    companion object {
        private const val STATUS_REFRESH_MS = 5000L
    }
}
