package com.alicontrol.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.alicontrol.app.AliControlApp
import com.alicontrol.app.R
import com.alicontrol.app.actions.ActionDispatcher
import com.alicontrol.app.ble.ProtocolMessage
import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.DefaultDashboardFactory
import com.alicontrol.app.databinding.ActivityMainBinding
import com.alicontrol.app.kiosk.KioskController
import com.alicontrol.app.transport.BluetoothTransport
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.transport.TransportManager
import com.alicontrol.app.ui.dashboard.DashboardPageRenderer
import com.alicontrol.app.ui.dashboard.DashboardPagerAdapter
import com.alicontrol.app.ui.dashboard.DashboardWidgetViewFactory
import com.alicontrol.app.ui.dashboard.DotsIndicator

/**
 * TASK-007: Ali Control's primary screen — a multi-page, widget-based
 * dashboard (see [Dashboard]/[DefaultDashboardFactory]) replacing the old
 * single control-card row. Connect, Device/Settings and All Actions remain
 * one tap away from the header, matching the old screen's accessibility
 * guarantees; the dashboard itself never navigates away just because BLE
 * disconnects — widgets simply dim (see [DashboardWidgetViewFactory]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var transportManager: TransportManager
    private lateinit var kioskController: KioskController
    private lateinit var dashboard: Dashboard
    private lateinit var pagerAdapter: DashboardPagerAdapter
    private lateinit var dotsIndicator: DotsIndicator

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
            android.widget.Toast.makeText(this@MainActivity, getString(R.string.mac_ack_format, action, status), android.widget.Toast.LENGTH_SHORT).show()
        }

        override fun onError(message: String) {
            lastErrorMessage = message
            refreshStatus()
        }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updatePageChrome(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transportManager = (application as AliControlApp).transportManager
        kioskController = KioskController(this)
        dashboard = DefaultDashboardFactory.create()

        setupDashboard()

        binding.connectButton.setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        binding.disconnectButton.setOnClickListener {
            transportManager.bluetooth.disconnect()
        }
        binding.deviceSettingsButton.setOnClickListener {
            startActivity(Intent(this, DeviceSettingsActivity::class.java))
        }
        binding.allActionsButton.setOnClickListener {
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

    private fun setupDashboard() {
        val pages = dashboard.orderedPages
        val dispatcher = ActionDispatcher(this, transportManager)
        val widgetFactory = DashboardWidgetViewFactory(this, dispatcher)
        val renderer = DashboardPageRenderer(widgetFactory)
        pagerAdapter = DashboardPagerAdapter(pages, renderer)
        binding.dashboardPager.adapter = pagerAdapter
        binding.dashboardPager.registerOnPageChangeCallback(pageChangeCallback)

        dotsIndicator = DotsIndicator(binding.pageIndicator)
        dotsIndicator.bind(pages.size, binding.dashboardPager.currentItem)
        updatePageChrome(binding.dashboardPager.currentItem)
    }

    override fun onDestroy() {
        binding.dashboardPager.unregisterOnPageChangeCallback(pageChangeCallback)
        binding.dashboardPager.adapter = null
        super.onDestroy()
    }

    private fun updatePageChrome(position: Int) {
        val pages = dashboard.orderedPages
        val name = pages.getOrNull(position)?.name.orEmpty()
        binding.currentPageLabel.text = name
        dotsIndicator.bind(pages.size, position)
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

        pagerAdapter.updateConnectionState(connected)
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
}
