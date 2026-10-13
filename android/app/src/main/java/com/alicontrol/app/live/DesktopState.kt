package com.alicontrol.app.live

import com.alicontrol.app.live.artwork.MediaArtworkState

/**
 * Live system telemetry reported by the connected desktop.
 *
 * Every field is nullable and null means **unavailable**, never zero. The
 * desktop omits a metric it cannot measure (the Windows agent has no reliable
 * user-mode GPU-temperature or CPU-temperature source, and omits GPU usage on
 * machines without GPU engine counters), and that absence travels all the way to
 * the widget, which renders `--`. A widget must never
 * be able to confuse "no data" with "measured 0%".
 */
data class SystemMetrics(
    val cpuPercent: Int? = null,
    val memoryPercent: Int? = null,
    val gpuPercent: Int? = null,
    val cpuTemperatureCelsius: Int? = null,
    val gpuTemperatureCelsius: Int? = null
) {
    companion object {
        val UNAVAILABLE = SystemMetrics()
    }
}

/**
 * Live Now Playing state reported by the connected desktop.
 *
 * Playback (state/position/duration) and track identity (title/artist/album)
 * arrive as two separate BLE messages — playback changes constantly and is
 * tiny, track text changes rarely and is the only unbounded part of the
 * protocol. This type holds the merged result of both; see
 * [DesktopStateStore] for how partial updates are combined.
 */
data class MediaState(
    val isAvailable: Boolean = false,
    val source: String? = null,
    val isPlaying: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkId: String? = null,
    val positionSeconds: Int? = null,
    val durationSeconds: Int? = null
) {
    /** Whether there is real metadata to show, as opposed to just a playback state. */
    val hasTrackMetadata: Boolean get() = !title.isNullOrBlank()

    companion object {
        val UNAVAILABLE = MediaState()
    }
}

/**
 * Everything the tablet currently knows about the connected desktop.
 *
 * Deliberately **separate from the dashboard layout model**: layout is user
 * configuration that is persisted to disk (see
 * [com.alicontrol.app.dashboard.DashboardStore]), while this is volatile
 * live data that is never written anywhere. A widget's *position* is saved;
 * the CPU reading it happens to be showing is not.
 *
 * [desktop] is the connected desktop's identity (platform, name, capabilities) as
 * announced by its `desktop_info` and `capabilities` messages — the one place the
 * app learns which platform it is connected to.
 *
 * [metricsReceivedAtMs]/[mediaReceivedAtMs] are elapsed-time marks used to
 * expire data that has stopped arriving — see [isMetricsFresh].
 */
data class DesktopState(
    val metrics: SystemMetrics = SystemMetrics.UNAVAILABLE,
    val media: MediaState = MediaState.UNAVAILABLE,
    val artwork: MediaArtworkState = MediaArtworkState.NONE,
    /** Who the desktop is and what it can do. Never expires while connected; reset on disconnect. */
    val desktop: DesktopInfo = DesktopInfo.UNKNOWN,
    val metricsReceivedAtMs: Long = 0L,
    val mediaReceivedAtMs: Long = 0L
) {

    /**
     * Whether telemetry is recent enough to still present as live. The
     * desktop samples every 2s, so several missed updates in a row means
     * something stopped — showing a minutes-old CPU figure as current would
     * be a lie, and `--` is the honest answer.
     */
    fun isMetricsFresh(nowMs: Long): Boolean =
        metricsReceivedAtMs > 0L && nowMs - metricsReceivedAtMs <= STALE_TIMEOUT_MS

    fun isMediaFresh(nowMs: Long): Boolean =
        mediaReceivedAtMs > 0L && nowMs - mediaReceivedAtMs <= STALE_TIMEOUT_MS

    /** The metrics to render right now: the received values, or nothing once stale. */
    fun metricsAt(nowMs: Long): SystemMetrics =
        if (isMetricsFresh(nowMs)) metrics else SystemMetrics.UNAVAILABLE

    fun mediaAt(nowMs: Long): MediaState =
        if (isMediaFresh(nowMs)) media else MediaState.UNAVAILABLE

    fun artworkAt(nowMs: Long): MediaArtworkState =
        if (isMediaFresh(nowMs) && artwork.artworkId == media.artworkId) artwork else MediaArtworkState.NONE

    companion object {
        /**
         * Five missed 2-second samples. Long enough to ride out a slow tick
         * or a busy moment, short enough that a stalled desktop doesn't leave
         * stale numbers on screen for long.
         */
        const val STALE_TIMEOUT_MS = 10_000L

        val EMPTY = DesktopState()
    }
}
