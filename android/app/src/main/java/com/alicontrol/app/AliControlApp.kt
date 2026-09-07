package com.alicontrol.app

import android.app.Application
import com.alicontrol.app.transport.TransportManager

/**
 * Holds the single [TransportManager] instance for the app's lifetime, so the
 * BLE connection survives navigating between activities.
 */
class AliControlApp : Application() {
    val transportManager: TransportManager by lazy { TransportManager(this) }
}
