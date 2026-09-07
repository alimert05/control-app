package com.alicontrol.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.alicontrol.app.AliControlApp
import com.alicontrol.app.R
import com.alicontrol.app.databinding.ActivityDeviceSettingsBinding
import com.alicontrol.app.device.DeviceController
import com.alicontrol.app.kiosk.KioskController

/**
 * In-app Device/Settings screen: Wi-Fi/Bluetooth settings entry points, and
 * direct in-app control of brightness, tablet media volume, keep-awake, and
 * battery — everything that would otherwise send the user out to stock
 * Android settings. All device-control logic itself lives in
 * [com.alicontrol.app.device]/[KioskController]; this Activity only wires UI.
 */
class DeviceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceSettingsBinding
    private lateinit var deviceController: DeviceController
    private lateinit var kioskController: KioskController

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshBattery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceController = DeviceController(this)
        kioskController = KioskController(this)

        binding.closeButton.setOnClickListener { finish() }

        binding.wifiSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        binding.bluetoothSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        binding.brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                deviceController.brightness.setPercent(progress)
                binding.brightnessValueText.text = getString(R.string.percent_format, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binding.brightnessPermissionButton.setOnClickListener {
            startActivity(deviceController.brightness.createPermissionIntent())
        }

        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                deviceController.volume.setLevel(progress)
                refreshVolumeLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.keepAwakeSwitch.setOnCheckedChangeListener { _, isChecked ->
            kioskController.keepScreenAwake = isChecked
            kioskController.applyKeepAwake(window)
        }

        kioskController.attachEscapeHatch(binding.screenTitle, window)
    }

    override fun onResume() {
        super.onResume()
        kioskController.applyImmersiveMode(window)
        kioskController.applyKeepAwake(window)

        refreshBluetoothStatus()
        refreshBrightness()
        refreshVolume()
        refreshBattery()
        binding.keepAwakeSwitch.isChecked = kioskController.keepScreenAwake

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) kioskController.applyImmersiveMode(window)
    }

    private fun refreshBluetoothStatus() {
        val enabled = (application as AliControlApp).transportManager.bluetooth.isBluetoothEnabled
        binding.bluetoothStatusValue.text = getString(if (enabled) R.string.bluetooth_on else R.string.bluetooth_off)
    }

    private fun refreshBrightness() {
        val hasPermission = deviceController.brightness.hasWritePermission
        binding.brightnessSeekBar.isEnabled = hasPermission
        binding.brightnessPermissionButton.visibility = if (hasPermission) View.GONE else View.VISIBLE

        val percent = deviceController.brightness.currentPercent()
        if (percent >= 0) {
            binding.brightnessSeekBar.progress = percent
            binding.brightnessValueText.text = getString(R.string.percent_format, percent)
        } else {
            binding.brightnessValueText.text = getString(R.string.value_placeholder)
        }
    }

    private fun refreshVolume() {
        binding.volumeSeekBar.max = deviceController.volume.maxLevel
        binding.volumeSeekBar.progress = deviceController.volume.currentLevel
        refreshVolumeLabel()
    }

    private fun refreshVolumeLabel() {
        binding.volumeValueText.text = getString(R.string.percent_format, deviceController.volume.currentPercent())
    }

    private fun refreshBattery() {
        val percent = deviceController.battery.currentPercent()
        binding.batteryValueText.text =
            if (percent >= 0) getString(R.string.percent_format, percent) else getString(R.string.value_placeholder)
    }
}
