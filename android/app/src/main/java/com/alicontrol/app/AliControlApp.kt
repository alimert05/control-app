package com.alicontrol.app

import android.app.Application
import com.alicontrol.app.live.DesktopStateStore
import com.alicontrol.app.live.artwork.AndroidArtworkDecoder
import com.alicontrol.app.live.artwork.ArtworkCache
import com.alicontrol.app.live.artwork.ArtworkTransferController
import com.alicontrol.app.transport.TransportManager

/**
 * Holds the app-lifetime singletons: the single [TransportManager] (so the
 * BLE connection survives navigating between activities) and the single
 * [DesktopStateStore] that turns the desktop's live-state notifications into
 * one shared source of truth for every widget.
 *
 * The store is attached to the transport once, here — not per Activity and
 * certainly not per widget — so live state keeps flowing while the user is on
 * any screen, and there is only ever one parser and one listener.
 */
class AliControlApp : Application() {

    val transportManager: TransportManager by lazy { TransportManager(this) }

    val desktopStateStore: DesktopStateStore by lazy {
        DesktopStateStore(
            artwork = ArtworkTransferController(
                cache = ArtworkCache(),
                decoder = AndroidArtworkDecoder,
                sendRequest = transportManager.bluetooth::send
            )
        )
    }

    override fun onCreate() {
        super.onCreate()
        transportManager.bluetooth.addListener(desktopStateStore)
    }
}
