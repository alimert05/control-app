package com.alicontrol.app.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alicontrol.app.AliControlApp
import com.alicontrol.app.R
import com.alicontrol.app.actions.ActionDefinition
import com.alicontrol.app.actions.ActionDispatcher
import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.DangerLevel
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.ble.ProtocolMessage
import com.alicontrol.app.databinding.ActivityActionsBinding
import com.alicontrol.app.kiosk.KioskController
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.transport.TransportManager

/**
 * TASK-006 temporary/testing screen: lists every [ActionLibrary] action that
 * supports macOS (the only implemented desktop agent so far), grouped by
 * category, so the new action IDs can be exercised manually without
 * overcrowding the main dashboard. Rows are built at runtime directly from
 * the registry — not hand-authored XML — which is the whole point of having
 * a structured action library. TASK-007 replaces this screen with the real
 * customizable layout; this is explicitly not that.
 */
class ActionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActionsBinding
    private lateinit var transportManager: TransportManager
    private lateinit var kioskController: KioskController
    private lateinit var actionDispatcher: ActionDispatcher

    private val transportListener = object : TransportListener {
        override fun onConnected() = refreshStatus()
        override fun onDisconnected() = refreshStatus()

        override fun onDataReceived(data: ByteArray) {
            val message = ProtocolMessage.fromBytes(data) ?: return
            if (message.type != ProtocolMessage.TYPE_STATE) return
            val action = message.action ?: return
            val status = message.status ?: return
            Toast.makeText(this@ActionsActivity, getString(R.string.mac_ack_format, action, status), Toast.LENGTH_SHORT).show()
        }

        override fun onError(message: String) = refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transportManager = (application as AliControlApp).transportManager
        kioskController = KioskController(this)
        actionDispatcher = ActionDispatcher(this, transportManager)

        binding.closeButton.setOnClickListener { finish() }
        kioskController.attachEscapeHatch(binding.screenTitle, window)

        buildActionRows()
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

    private fun refreshStatus() {
        binding.statusMessage.text =
            if (transportManager.bluetooth.isConnected) getString(R.string.status_connected) else getString(R.string.status_disconnected)
    }

    private fun buildActionRows() {
        val byCategory = ActionLibrary.forPlatform(Platform.MACOS).groupBy { it.category }
        for ((category, actions) in byCategory) {
            addCategoryHeader(category.name)
            for (action in actions) addActionRow(action)
        }
    }

    private fun addCategoryHeader(title: String) {
        val header = TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            isAllCaps = true
            letterSpacing = 0.08f
            setPadding(dp(4), dp(20), dp(4), dp(6))
        }
        binding.actionsContainer.addView(header)
    }

    private fun addActionRow(action: ActionDefinition) {
        // Reuses the existing plain-TextView row style already established
        // for the BLE device list — deliberately not a new layout, per
        // "do not spend significant effort on final layout design".
        val row = layoutInflater.inflate(R.layout.item_ble_device, binding.actionsContainer, false) as TextView
        row.text = if (action.dangerLevel == DangerLevel.CONFIRM) {
            getString(R.string.action_row_confirm_suffix, action.label)
        } else {
            action.label
        }
        row.isClickable = true
        row.isFocusable = true
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        row.setBackgroundResource(outValue.resourceId)
        row.setOnClickListener { actionDispatcher.dispatch(action) }
        binding.actionsContainer.addView(row)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(getColor(R.color.divider))
        }
        binding.actionsContainer.addView(divider)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
