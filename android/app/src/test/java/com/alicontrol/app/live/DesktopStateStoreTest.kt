package com.alicontrol.app.live

import com.alicontrol.app.dashboard.DashboardSerializer
import com.alicontrol.app.dashboard.DefaultDashboardFactory
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.alicontrol.app.live.artwork.ArtworkCache
import com.alicontrol.app.live.artwork.ArtworkStatus
import com.alicontrol.app.live.artwork.ArtworkTransferController

/**
 * The shared live-state store: merging the two media messages, clearing on
 * disconnect, accepting a fresh session on reconnect, and expiring data that
 * stops arriving.
 */
class DesktopStateStoreTest {

    private class FakeScheduler : StalenessScheduler {
        private var pending: (() -> Unit)? = null
        var lastDelayMs: Long? = null
            private set

        override fun schedule(delayMs: Long, action: () -> Unit) {
            lastDelayMs = delayMs
            pending = action
        }

        override fun cancel() {
            pending = null
        }

        /** Runs whatever the store scheduled, as the main-thread handler would. */
        fun fire() {
            val action = pending
            pending = null
            action?.invoke()
        }

        val hasPending: Boolean get() = pending != null
    }

    private var now = 1_000L
    private val scheduler = FakeScheduler()
    private val store = DesktopStateStore(clock = { now }, scheduler = scheduler)

    private fun record(): MutableList<DesktopState> {
        val states = mutableListOf<DesktopState>()
        store.addListener { states.add(it) }
        return states
    }

    // -- telemetry --

    @Test
    fun metricsMessagesBecomeVisibleState() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 34, memoryPercent = 48))

        assertEquals(34, store.visibleState().metrics.cpuPercent)
        assertEquals(48, store.visibleState().metrics.memoryPercent)
        assertNull(store.visibleState().metrics.gpuPercent)
    }

    @Test
    fun listenersAreNotifiedOfEachUpdateAndOfCurrentStateOnSubscribe() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 10))
        val states = record()

        assertEquals("a new subscriber is given the current state immediately", 1, states.size)
        assertEquals(10, states.last().metrics.cpuPercent)

        store.apply(LiveStateMessage.Metrics(cpuPercent = 20))

        assertEquals(2, states.size)
        assertEquals(20, states.last().metrics.cpuPercent)
    }

    @Test
    fun everyListenerReceivesTheSameSharedState() {
        // Two widgets on different pages showing the same reading must be two
        // renderings of one value, not two independent samples.
        val first = mutableListOf<DesktopState>()
        val second = mutableListOf<DesktopState>()
        store.addListener { first.add(it) }
        store.addListener { second.add(it) }

        store.apply(LiveStateMessage.Metrics(cpuPercent = 42, memoryPercent = 43))

        assertEquals(first.last(), second.last())
        assertEquals(42, first.last().metrics.cpuPercent)
    }

    // -- media merging --

    @Test
    fun playbackAndTrackMessagesMergeIntoOneMediaState() {
        store.apply(LiveStateMessage.Track(title = "Song", artist = "Artist", album = "Album"))
        store.apply(
            LiveStateMessage.Playback(
                isAvailable = true, source = "spotify", isPlaying = true,
                positionSeconds = 83, durationSeconds = 214
            )
        )

        val media = store.visibleState().media
        assertEquals("Song", media.title)
        assertEquals("Artist", media.artist)
        assertEquals("spotify", media.source)
        assertTrue(media.isPlaying)
        assertEquals(83, media.positionSeconds)
    }

    @Test
    fun repeatedPlaybackUpdatesKeepTheTrackMetadataAlreadyReceived() {
        store.apply(LiveStateMessage.Track(title = "Song", artist = "Artist"))
        store.apply(LiveStateMessage.Playback(isAvailable = true, isPlaying = true, positionSeconds = 10))
        store.apply(LiveStateMessage.Playback(isAvailable = true, isPlaying = true, positionSeconds = 12))

        // The frequent message deliberately carries no text; it must not wipe it.
        assertEquals("Song", store.visibleState().media.title)
        assertEquals(12, store.visibleState().media.positionSeconds)
    }

    @Test
    fun mediaBecomingUnavailableAlsoDropsTheTrackMetadata() {
        store.apply(LiveStateMessage.Track(title = "Song", artist = "Artist"))
        store.apply(LiveStateMessage.Playback(isAvailable = true, isPlaying = true))

        store.apply(LiveStateMessage.Playback(isAvailable = false))

        val media = store.visibleState().media
        assertFalse(media.isAvailable)
        assertNull(media.title)
        assertFalse(media.hasTrackMetadata)
    }

    @Test
    fun aNewTrackReplacesThePreviousMetadata() {
        store.apply(LiveStateMessage.Track(title = "First", artist = "A", album = "X"))
        store.apply(LiveStateMessage.Track(title = "Second", artist = "B", album = null))

        val media = store.visibleState().media
        assertEquals("Second", media.title)
        assertEquals("B", media.artist)
        assertNull(media.album)
    }

    @Test
    fun trackArtworkIdTriggersOneCacheMissRequestAndIsMergedWithMetadata() {
        val requests = mutableListOf<ByteArray>()
        val artwork = ArtworkTransferController(ArtworkCache(), decoder = { _, _ -> null }, sendRequest = { requests += it })
        val artworkStore = DesktopStateStore(clock = { now }, scheduler = FakeScheduler(), artwork = artwork)

        artworkStore.apply(LiveStateMessage.Track("Song", "Artist", "Album", "0123456789abcdef"))
        artworkStore.apply(LiveStateMessage.Track("Song", "Artist", "Album", "0123456789abcdef"))

        assertEquals("0123456789abcdef", artworkStore.state.media.artworkId)
        assertEquals(ArtworkStatus.REQUESTED, artworkStore.state.artwork.status)
        assertEquals(1, requests.size)
    }

    // -- transport lifecycle --

    @Test
    fun disconnectClearsAllLiveState() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 50, memoryPercent = 60))
        store.apply(LiveStateMessage.Track(title = "Song"))
        store.apply(LiveStateMessage.Playback(isAvailable = true, isPlaying = true))
        val states = record()

        store.onDisconnected()

        assertEquals(DesktopState.EMPTY, store.state)
        assertNull(store.visibleState().metrics.cpuPercent)
        assertFalse(store.visibleState().media.isAvailable)
        assertEquals(DesktopState.EMPTY, states.last())
    }

    @Test
    fun connectingClearsAnyStateLeftOverFromAPreviousSession() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 99))

        store.onConnected()

        assertEquals(DesktopState.EMPTY, store.state)
    }

    @Test
    fun reconnectingAcceptsFreshStateAgain() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 99))
        store.onDisconnected()
        store.onConnected()

        store.apply(LiveStateMessage.Metrics(cpuPercent = 7, memoryPercent = 8))

        assertEquals(7, store.visibleState().metrics.cpuPercent)
        assertEquals(8, store.visibleState().metrics.memoryPercent)
    }

    @Test
    fun clearingWhenAlreadyEmptyDoesNotNotifyListeners() {
        val states = record()
        states.clear()

        store.onDisconnected()

        assertTrue(states.isEmpty())
    }

    @Test
    fun liveNotificationsArriveThroughTheTransportListener() {
        val json = "{\"type\":\"metrics\",\"cpu\":21,\"memory\":22}"

        store.onDataReceived(json.toByteArray(StandardCharsets.UTF_8))

        assertEquals(21, store.visibleState().metrics.cpuPercent)
    }

    @Test
    fun commandAcknowledgementsOnTheSameCharacteristicAreIgnored() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 30))

        store.onDataReceived("{\"type\":\"state\",\"action\":\"volume_up\",\"status\":\"ok\"}".toByteArray())

        // The ack changed nothing: it belongs to the command protocol.
        assertEquals(30, store.visibleState().metrics.cpuPercent)
    }

    @Test
    fun garbledNotificationsAreIgnoredWithoutDisturbingKnownState() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 30))

        store.onDataReceived("{\"type\":\"metrics\",\"cp".toByteArray())
        store.onDataReceived(byteArrayOf(0x00, 0x01, 0x02))

        assertEquals(30, store.visibleState().metrics.cpuPercent)
    }

    // -- freshness --

    @Test
    fun dataOlderThanTheStaleTimeoutStopsBeingPresentedAsLive() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 55))
        store.apply(LiveStateMessage.Playback(isAvailable = true, isPlaying = true))

        now += DesktopState.STALE_TIMEOUT_MS + 1

        assertNull(store.visibleState().metrics.cpuPercent)
        assertFalse(store.visibleState().media.isAvailable)
    }

    @Test
    fun dataInsideTheStaleTimeoutIsStillLive() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 55))

        now += DesktopState.STALE_TIMEOUT_MS - 1

        assertEquals(55, store.visibleState().metrics.cpuPercent)
    }

    @Test
    fun aStalenessCheckIsScheduledAndRepublishesUnavailableStateWhenItFires() {
        val states = record()
        store.apply(LiveStateMessage.Metrics(cpuPercent = 77))
        assertTrue(scheduler.hasPending)
        assertTrue(scheduler.lastDelayMs!! > DesktopState.STALE_TIMEOUT_MS)

        now += DesktopState.STALE_TIMEOUT_MS + 1
        scheduler.fire()

        assertNull(states.last().metrics.cpuPercent)
    }

    @Test
    fun aStalenessCheckThatFiresWhileDataIsStillFreshChangesNothing() {
        val states = record()
        store.apply(LiveStateMessage.Metrics(cpuPercent = 77))
        val countAfterUpdate = states.size

        scheduler.fire()

        assertEquals(countAfterUpdate, states.size)
        assertEquals(77, store.visibleState().metrics.cpuPercent)
    }

    @Test
    fun clearingCancelsAnyPendingStalenessCheck() {
        store.apply(LiveStateMessage.Metrics(cpuPercent = 77))

        store.onDisconnected()

        assertFalse(scheduler.hasPending)
    }

    // -- separation from persisted layout --

    @Test
    fun liveStateNeverLeaksIntoThePersistedDashboardLayout() {
        // Layout is saved configuration; telemetry is volatile. The two models
        // share nothing, and the serialized layout must contain neither.
        store.apply(LiveStateMessage.Metrics(cpuPercent = 73, memoryPercent = 64))
        store.apply(LiveStateMessage.Track(title = "Some Song", artist = "Some Artist"))

        val serialized = DashboardSerializer.serialize(DefaultDashboardFactory.create())

        assertFalse(serialized.contains("73"))
        assertFalse(serialized.contains("Some Song"))
        assertFalse(serialized.contains("metrics"))
        // And the layout still round-trips unchanged while live data exists.
        assertEquals(DefaultDashboardFactory.create(), DashboardSerializer.deserialize(serialized))
    }
}
