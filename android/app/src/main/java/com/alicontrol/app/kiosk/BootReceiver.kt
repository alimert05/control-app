package com.alicontrol.app.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alicontrol.app.ui.MainActivity

/**
 * Launches Ali Control after the system finishes booting.
 *
 * No background service, no polling: this receiver runs once for the single
 * BOOT_COMPLETED broadcast the system sends, starts the activity, and does
 * nothing else — there is nothing left running afterwards.
 *
 * BOOT_COMPLETED is one of the broadcasts explicitly exempted from Android
 * 8's implicit-broadcast manifest restrictions, so a plain manifest-declared
 * receiver (as opposed to a runtime-registered one, which wouldn't exist yet
 * at boot time anyway) still works here on API 27+.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldLaunch(intent.action)) return
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
    }

    companion object {
        /** Pure decision logic, exposed for testing without a BroadcastReceiver/Context. */
        fun shouldLaunch(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED
    }
}
