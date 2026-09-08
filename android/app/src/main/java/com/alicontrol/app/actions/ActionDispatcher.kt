package com.alicontrol.app.actions

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import com.alicontrol.app.R
import com.alicontrol.app.ble.ProtocolMessage
import com.alicontrol.app.transport.TransportManager

/**
 * The single place that turns a tap on an [ActionDefinition] into a sent BLE
 * command — used by both [com.alicontrol.app.ui.ActionsActivity] and the
 * TASK-007 dashboard widgets, so there is exactly one action dispatch system
 * (never a second one duplicated in the widget layer, per TASK-007's
 * requirement).
 *
 * Preserves TASK-006 behavior exactly: [DangerLevel.CONFIRM] actions show an
 * [AlertDialog] first; every send re-checks the live connection state right
 * before writing (not just at render time) as a second line of defense
 * against a stale/disabled-but-still-tapped view.
 */
class ActionDispatcher(
    private val activity: AppCompatActivity,
    private val transportManager: TransportManager
) {

    fun dispatch(actionId: String) {
        val action = ActionLibrary.find(actionId) ?: return
        dispatch(action)
    }

    fun dispatch(action: ActionDefinition) {
        if (!transportManager.bluetooth.isConnected) {
            Toast.makeText(activity, R.string.error_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        if (action.dangerLevel == DangerLevel.CONFIRM) {
            AlertDialog.Builder(activity)
                .setTitle(action.label)
                .setMessage(activity.getString(R.string.confirm_action_message, action.label))
                .setPositiveButton(R.string.action_confirm) { _, _ -> send(action) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            send(action)
        }
    }

    private fun send(action: ActionDefinition) {
        if (!shouldSend(isConnected = transportManager.bluetooth.isConnected, requiresConfirmation = false, userConfirmed = true)) {
            Toast.makeText(activity, R.string.error_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        transportManager.bluetooth.send(ProtocolMessage.command(action.id).toBytes())
    }

    companion object {
        /**
         * Pure decision logic, exposed for testing without a real transport
         * or dialog: whether a tap should actually result in a send. A
         * CONFIRM action only sends once the user has confirmed; a
         * disconnected transport never sends regardless.
         */
        fun shouldSend(isConnected: Boolean, requiresConfirmation: Boolean, userConfirmed: Boolean): Boolean =
            isConnected && (!requiresConfirmation || userConfirmed)
    }
}
