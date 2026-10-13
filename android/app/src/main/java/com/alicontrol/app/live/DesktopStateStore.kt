package com.alicontrol.app.live

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.transport.TransportListener
import com.alicontrol.app.live.artwork.ArtworkCache
import com.alicontrol.app.live.artwork.ArtworkTransferController
import com.alicontrol.app.live.artwork.MediaArtworkState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets the store re-check freshness after a quiet period. Abstracted so unit
 * tests never need a `Looper`.
 */
interface StalenessScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancel()
}

/** Default implementation: one main-thread [Handler] for the whole app. */
class MainThreadStalenessScheduler : StalenessScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
        cancel()
        val runnable = Runnable { action() }
        pending = runnable
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}

/**
 * The single, application-scoped source of live desktop state.
 *
 * There is exactly one of these (created by
 * [com.alicontrol.app.AliControlApp]) and it owns exactly one BLE listener.
 * Widgets never subscribe to the transport, never parse a payload, and never
 * poll: they read whatever this store last published. That is what makes a
 * CPU tile on the Dashboard page and another on the Dev page show the same
 * number — they are two renderings of one value, not two independent
 * subscriptions.
 *
 * Responsibilities:
 *
 * - parse live-state notifications (and ignore everything else, including
 *   the command acknowledgements that share the same characteristic);
 * - merge the two media messages — playback and track identity arrive
 *   separately — into one coherent [MediaState];
 * - drop everything on disconnect, so stale readings are never presented as
 *   current;
 * - expire data that stops arriving even while the link still looks up.
 */
class DesktopStateStore(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val scheduler: StalenessScheduler = MainThreadStalenessScheduler(),
    private val artwork: ArtworkTransferController = ArtworkTransferController(
        ArtworkCache(),
        decoder = { _, _ -> null },
        sendRequest = {}
    )
) : TransportListener {

    fun interface Listener {
        fun onDesktopStateChanged(state: DesktopState)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var state: DesktopState = DesktopState.EMPTY
        private set

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        // A newly attached view starts from the current truth rather than
        // waiting for the next notification to arrive.
        listener.onDesktopStateChanged(visibleState())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** The state as it should be rendered *now*, with stale sections already blanked. */
    fun visibleState(): DesktopState {
        val now = clock()
        return state.copy(metrics = state.metricsAt(now), media = state.mediaAt(now), artwork = state.artworkAt(now))
    }

    // -- TransportListener --

    override fun onConnected() {
        // A fresh session: never inherit anything from the previous one — including
        // which desktop it was — even if it was the same PC a moment ago.
        clear()
    }

    override fun onDisconnected() = clear()

    override fun onError(message: String) = Unit

    override fun onDataReceived(data: ByteArray) {
        if (artwork.handleIncoming(data)) {
            val nextArtwork = artwork.state
            if (state.artwork != nextArtwork) {
                state = state.copy(artwork = nextArtwork)
                publish()
            }
            return
        }
        val message = LiveStateMessage.fromBytes(data) ?: return
        apply(message)
    }

    /**
     * Applies one parsed message. Public (rather than only reachable through
     * [onDataReceived]) so the merge rules can be tested directly without
     * fabricating byte arrays.
     */
    fun apply(message: LiveStateMessage) {
        val now = clock()
        state = when (message) {
            is LiveStateMessage.Metrics -> state.copy(
                metrics = SystemMetrics(
                    cpuPercent = message.cpuPercent,
                    memoryPercent = message.memoryPercent,
                    gpuPercent = message.gpuPercent,
                    cpuTemperatureCelsius = message.cpuTemperatureCelsius,
                    gpuTemperatureCelsius = message.gpuTemperatureCelsius
                ),
                metricsReceivedAtMs = now
            )

            is LiveStateMessage.Playback -> state.copy(
                media = if (message.isAvailable) {
                    // Keep the track text already received; this message
                    // carries none, and re-sending it every two seconds is
                    // exactly what the split avoids.
                    state.media.copy(
                        isAvailable = true,
                        source = message.source,
                        isPlaying = message.isPlaying,
                        positionSeconds = message.positionSeconds,
                        durationSeconds = message.durationSeconds
                    )
                } else {
                    // Nothing playable: drop the metadata too, so the widget
                    // can't show a title next to "unavailable".
                    MediaState.UNAVAILABLE
                },
                artwork = if (message.isAvailable) state.artwork else artwork.onTrackArtworkChanged(null),
                mediaReceivedAtMs = now
            )

            is LiveStateMessage.Desktop -> state.copy(
                // A new introduction starts the capability list over: the desktop re-announces
                // everything it supports in the messages that follow.
                desktop = DesktopInfo(
                    hasIdentity = true,
                    platform = Platform.fromWireId(message.platformId),
                    platformId = message.platformId,
                    name = message.name?.takeIf { it.isNotBlank() },
                    protocolVersion = message.protocolVersion,
                    capabilities = null
                )
            )

            is LiveStateMessage.Capabilities -> state.copy(
                desktop = state.desktop.copy(
                    // Additive: a long list may be split over several messages. Ids this build
                    // does not know are ignored.
                    capabilities = (state.desktop.capabilities ?: emptySet()) +
                        message.ids.mapNotNull(DesktopCapability::fromWireId)
                )
            )

            is LiveStateMessage.Track -> {
                val artworkState = artwork.onTrackArtworkChanged(message.artworkId)
                state.copy(
                media = state.media.copy(
                    title = message.title,
                    artist = message.artist,
                    album = message.album,
                    artworkId = artworkState.artworkId
                ),
                artwork = artworkState,
                mediaReceivedAtMs = now
                )
            }
        }
        publish()
    }

    /** Forgets all live data — on disconnect, and whenever a new session starts. */
    fun clear() {
        scheduler.cancel()
        artwork.disconnect()
        if (state == DesktopState.EMPTY) return
        state = DesktopState.EMPTY
        listeners.forEach { it.onDesktopStateChanged(state) }
    }

    private fun publish() {
        val visible = visibleState()
        listeners.forEach { it.onDesktopStateChanged(visible) }
        scheduleStalenessCheck()
    }

    /**
     * Re-publishes once the newest data would have aged out, so widgets fall
     * back to `--` if the desktop goes quiet while the BLE link still looks
     * connected. One timer for the whole app, rescheduled only when new data
     * arrives — never a polling loop.
     */
    private fun scheduleStalenessCheck() {
        scheduler.schedule(DesktopState.STALE_TIMEOUT_MS + STALENESS_CHECK_MARGIN_MS) {
            val visible = visibleState()
            if (visible.metrics == SystemMetrics.UNAVAILABLE && visible.media == MediaState.UNAVAILABLE) {
                listeners.forEach { it.onDesktopStateChanged(visible) }
            }
        }
    }

    private companion object {
        /** Small cushion so the check lands just after the timeout, not exactly on it. */
        const val STALENESS_CHECK_MARGIN_MS = 500L
    }
}
