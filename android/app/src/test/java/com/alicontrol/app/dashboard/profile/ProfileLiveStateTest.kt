package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.WidgetAvailability
import com.alicontrol.app.dashboard.WidgetConfig
import com.alicontrol.app.dashboard.WidgetSupport
import com.alicontrol.app.live.DesktopCapability
import com.alicontrol.app.live.DesktopState
import com.alicontrol.app.live.DesktopStateStore
import com.alicontrol.app.live.LiveStateMessage
import com.alicontrol.app.live.StalenessScheduler
import com.alicontrol.app.live.artwork.ArtworkCache
import com.alicontrol.app.live.artwork.ArtworkImage
import com.alicontrol.app.live.artwork.ArtworkStatus
import com.alicontrol.app.live.artwork.ArtworkTransferController
import java.io.File
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profiles are presentation only. Switching, editing and managing them must leave every piece of
 * application-scoped live state exactly as it was: the desktop's identity and capabilities,
 * telemetry, media, artwork and the artwork cache, and the BLE session behind them.
 *
 * The first half drives a real [DesktopStateStore] through a full profile workout and compares
 * before and after. The second half pins down *why* that holds — the profile layer has no way to
 * reach live state or the transport — so it stays true as the code changes.
 */
class ProfileLiveStateTest {

    private class NoopScheduler : StalenessScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit) = Unit
        override fun cancel() = Unit
    }

    private data class FakeImage(override val byteCount: Int = 100) : ArtworkImage {
        override val width = 160
        override val height = 160
    }

    private val artworkId = "0123456789abcdef"
    private val cache = ArtworkCache()
    private val artworkRequests = mutableListOf<ByteArray>()
    private val live = DesktopStateStore(
        clock = { 1_000L },
        scheduler = NoopScheduler(),
        artwork = ArtworkTransferController(cache, decoder = { _, _ -> null }, sendRequest = { artworkRequests += it })
    )
    private val notifications = mutableListOf<DesktopState>()

    private val persistence = InMemoryLayoutPersistence()
    private val session = ProfileSession(ProfileStore(persistence))

    /** A connected Windows desktop with the full set of readings, a playing track and cached artwork. */
    private fun connectDesktopWithEverything() {
        cache.put(artworkId, FakeImage())
        live.apply(LiveStateMessage.Desktop(platformId = "windows", protocolVersion = 1, name = "ALI-PC"))
        live.apply(
            LiveStateMessage.Capabilities(
                listOf("actions", "telemetry.cpu", "telemetry.memory", "telemetry.gpu", "media", "media.artwork")
            )
        )
        live.apply(LiveStateMessage.Metrics(cpuPercent = 31, memoryPercent = 52, gpuPercent = 18))
        live.apply(
            LiveStateMessage.Playback(isAvailable = true, source = "Spotify", isPlaying = true, positionSeconds = 42, durationSeconds = 215)
        )
        live.apply(LiveStateMessage.Track(title = "Song", artist = "Artist", album = "Album", artworkId = artworkId))
        live.addListener { notifications += it }
    }

    /** Every profile operation the UI offers, in a row. */
    private fun workOutTheProfileLayer() {
        for (id in listOf(ProfileIds.DEV, ProfileIds.GAME, ProfileIds.MEDIA, ProfileIds.DEFAULT)) session.switchTo(id)

        session.switchTo(ProfileIds.DEV)
        session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "Edited").value())
        val created = session.createProfile("Streaming").value()
        val copy = session.duplicateProfile(ProfileIds.MEDIA).value()
        session.renameProfile(copy.id, "Media 2")
        session.moveProfile(copy.id, -2)
        session.setStartupDefault(ProfileIds.GAME)
        session.switchTo(created.id)
        session.resetProfile(ProfileIds.DEV)
        session.deleteProfile(created.id)
        session.deleteProfile(copy.id)
        session.switchTo(ProfileIds.MEDIA)
    }

    // -- live state survives --

    @Test
    fun theLiveStateIsExactlyAsItWasAfterAFullProfileWorkout() {
        connectDesktopWithEverything()
        val before = live.state
        val visibleBefore = live.visibleState()
        val notificationsBefore = notifications.size

        workOutTheProfileLayer()

        assertSame("the state object itself is untouched", before, live.state)
        assertEquals(visibleBefore, live.visibleState())
        assertEquals("no listener was told anything changed", notificationsBefore, notifications.size)
        assertFalse("nothing was cleared", live.state == DesktopState.EMPTY)
    }

    @Test
    fun theDesktopNameAndCapabilitiesSurviveSwitching() {
        connectDesktopWithEverything()

        workOutTheProfileLayer()

        assertEquals("ALI-PC", live.state.desktop.name)
        assertTrue(live.state.desktop.hasIdentity)
        assertEquals(
            setOf(
                DesktopCapability.ACTIONS, DesktopCapability.TELEMETRY_CPU, DesktopCapability.TELEMETRY_MEMORY,
                DesktopCapability.TELEMETRY_GPU, DesktopCapability.MEDIA, DesktopCapability.MEDIA_ARTWORK
            ),
            live.state.desktop.capabilities
        )
    }

    @Test
    fun cpuRamAndGpuReadingsSurviveSwitching() {
        connectDesktopWithEverything()

        workOutTheProfileLayer()

        val metrics = live.visibleState().metrics
        assertEquals(31, metrics.cpuPercent)
        assertEquals(52, metrics.memoryPercent)
        assertEquals(18, metrics.gpuPercent)
    }

    @Test
    fun mediaMetadataPlaybackAndProgressSurviveSwitching() {
        connectDesktopWithEverything()

        workOutTheProfileLayer()

        val media = live.visibleState().media
        assertEquals("Song", media.title)
        assertEquals("Artist", media.artist)
        assertEquals("Album", media.album)
        assertTrue(media.isPlaying)
        assertEquals(42, media.positionSeconds)
        assertEquals(215, media.durationSeconds)
    }

    @Test
    fun cachedArtworkIsShownImmediatelyAndNeverRequestedAgain() {
        connectDesktopWithEverything()
        assertEquals("cache hit at track time: nothing was ever requested", 0, artworkRequests.size)
        assertEquals(ArtworkStatus.READY, live.state.artwork.status)
        val image = live.state.artwork.imageFor(artworkId)
        assertNotNull(image)

        session.switchTo(ProfileIds.MEDIA) // Default -> Media: the artwork-heavy profile

        assertSame("the same cached bitmap is what the Media profile would render", image, live.state.artwork.imageFor(artworkId))
        assertEquals(0, artworkRequests.size)
        assertEquals(1, cache.size())
        assertTrue(cache.contains(artworkId))
    }

    @Test
    fun anArtworkTransferInFlightIsNotRestartedOrDuplicatedBySwitching() {
        live.apply(LiveStateMessage.Track(title = "Song", artist = "A", album = "B", artworkId = "fedcba9876543210"))
        assertEquals(1, artworkRequests.size)
        assertEquals(ArtworkStatus.REQUESTED, live.state.artwork.status)

        workOutTheProfileLayer()

        assertEquals("exactly the one request made when the track changed", 1, artworkRequests.size)
        assertEquals(ArtworkStatus.REQUESTED, live.state.artwork.status)
        assertEquals(0, cache.size())
    }

    @Test
    fun theBleSessionIsNeverReconnectedOrCleared() {
        connectDesktopWithEverything()
        var clears = 0
        live.addListener { if (it == DesktopState.EMPTY) clears++ } // a reconnect or disconnect clears to EMPTY

        workOutTheProfileLayer()

        assertEquals(0, clears)
        assertEquals("ALI-PC", live.state.desktop.name)
    }

    // -- capability awareness is runtime-only --

    @Test
    fun anUnavailableCapabilityDisablesTheWidgetButNeverRemovesItFromAnyProfile() {
        val withoutGpu = setOf(DesktopCapability.ACTIONS, DesktopCapability.TELEMETRY_CPU, DesktopCapability.TELEMETRY_MEMORY)
        live.apply(LiveStateMessage.Desktop("windows", 1, "ALI-PC"))
        live.apply(LiveStateMessage.Capabilities(withoutGpu.map { it.wireId }))
        val desktop = live.state.desktop

        for (id in listOf(ProfileIds.DEV, ProfileIds.GAME)) {
            session.switchTo(id)
            val gpuWidgets = session.dashboard.pages.flatMap { it.widgets }.filter {
                (it.config as? WidgetConfig.Gauge)?.label == "GPU" || (it.config as? WidgetConfig.Metric)?.label == "GPU"
            }
            assertTrue("$id keeps its GPU widget", gpuWidgets.isNotEmpty())
            for (widget in gpuWidgets) {
                assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(widget, desktop))
            }
        }
    }

    @Test
    fun capabilitiesChangingNeverRewritesAnyStoredLayout() {
        val before = session.collection
        val storedBefore = persistence.stored

        live.apply(LiveStateMessage.Desktop("windows", 1, "ALI-PC"))
        live.apply(LiveStateMessage.Capabilities(emptyList()))
        live.apply(LiveStateMessage.Capabilities(listOf("telemetry.gpu")))
        live.clear()

        assertEquals(before, session.collection)
        assertEquals(storedBefore, persistence.stored)
        assertEquals(0, persistence.writes)
    }

    @Test
    fun theSameWidgetsAreAvailableAgainOnceTheCapabilityReturns() {
        live.apply(LiveStateMessage.Desktop("windows", 1, "ALI-PC"))
        live.apply(LiveStateMessage.Capabilities(listOf("actions")))
        val gpu = session.collection.profile(ProfileIds.GAME)!!.dashboard.pages.flatMap { it.widgets }
            .first { (it.config as? WidgetConfig.Gauge)?.label == "GPU" }
        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(gpu, live.state.desktop))

        live.apply(LiveStateMessage.Capabilities(listOf("telemetry.gpu")))

        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(gpu, live.state.desktop))
    }

    // -- the structural reason --

    private val profileClasses: List<Class<*>> = listOf(
        DashboardProfile::class.java, ProfileIds::class.java, ProfileCollection::class.java, BuiltInProfiles::class.java,
        ProfileEditor::class.java, ProfileEditor.Added::class.java, ProfileSerializer::class.java, ProfileStore::class.java,
        ProfileStore.Loaded::class.java, ProfileStore.Source::class.java, ProfileSession::class.java
    )

    private fun referencedTypes(type: Class<*>): Set<Class<*>> {
        val found = mutableSetOf<Class<*>>()
        type.declaredFields.forEach { found += it.type }
        type.declaredConstructors.forEach { found += it.parameterTypes }
        type.declaredMethods.forEach { method: Method ->
            found += method.returnType
            found += method.parameterTypes
        }
        return found
    }

    @Test
    fun noProfileClassExposesLiveStateTransportOrUiTypes() {
        val forbidden = listOf("com.alicontrol.app.live", "com.alicontrol.app.transport", "com.alicontrol.app.ui", "android.")
        for (profileClass in profileClasses) for (type in referencedTypes(profileClass)) {
            val name = type.name
            assertTrue("${profileClass.simpleName} exposes $name", forbidden.none { name.startsWith(it) })
        }
    }

    @Test
    fun theProfilePackageSourcesImportNoLiveStateTransportOrAndroidTypes() {
        val directory = File("src/main/java/com/alicontrol/app/dashboard/profile")
        assertTrue("source directory not found from ${File(".").absolutePath}", directory.isDirectory)

        val sources = directory.listFiles { file -> file.extension == "kt" }!!
        assertTrue(sources.isNotEmpty())
        for (source in sources) {
            val imports = source.readLines().filter { it.trimStart().startsWith("import ") }
            for (line in imports) {
                val imported = line.trim().removePrefix("import ")
                assertFalse("${source.name}: $imported", imported.startsWith("com.alicontrol.app.live"))
                assertFalse("${source.name}: $imported", imported.startsWith("com.alicontrol.app.transport"))
                assertFalse("${source.name}: $imported", imported.startsWith("com.alicontrol.app.ui"))
                assertFalse("${source.name}: $imported", imported.startsWith("android."))
            }
            // The one BLE type allowed is the action-id string constants the templates are written with.
            val bleImports = imports.map { it.trim().removePrefix("import ") }.filter { it.startsWith("com.alicontrol.app.ble") }
            assertTrue("${source.name}: $bleImports", bleImports.all { it == "com.alicontrol.app.ble.AliControlCommands" })
        }
    }
}
