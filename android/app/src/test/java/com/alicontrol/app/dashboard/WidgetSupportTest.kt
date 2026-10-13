package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.Platform
import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.live.DesktopCapability
import com.alicontrol.app.live.DesktopInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the saved, platform-neutral dashboard is enabled or disabled against the
 * desktop that is actually connected — and the guarantee that availability only
 * ever changes how a widget is drawn, never what is saved.
 */
class WidgetSupportTest {

    private fun action(id: String) =
        DashboardWidget("w_$id", WidgetType.ACTION, 0, 0, 1, 1, WidgetConfig.Action(id))

    private fun metric(label: String) =
        DashboardWidget("m_$label", WidgetType.METRIC, 0, 0, 1, 1, WidgetConfig.Metric(label))

    private val windowsFull = DesktopInfo(
        hasIdentity = true, platform = Platform.WINDOWS, platformId = "windows", name = "ALI-PC", protocolVersion = 2,
        capabilities = setOf(
            DesktopCapability.ACTIONS, DesktopCapability.TELEMETRY_CPU, DesktopCapability.TELEMETRY_MEMORY,
            DesktopCapability.TELEMETRY_GPU, DesktopCapability.MEDIA, DesktopCapability.MEDIA_ARTWORK
        )
    )

    // -- before the desktop has introduced itself --

    @Test
    fun nothingIsGatedBeforeTheDesktopIdentifiesItself() {
        val unknown = DesktopInfo.UNKNOWN
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(action(AliControlCommands.LOCK_SCREEN), unknown))
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(metric("GPU"), unknown))
        assertTrue(WidgetSupport.canSend(AliControlCommands.LOCK_SCREEN, unknown))
    }

    @Test
    fun anUnknownActionIdCanNeverBeSentWhateverTheDesktopSays() {
        assertFalse(WidgetSupport.canSend("run_shell_command", DesktopInfo.UNKNOWN))
        assertFalse(WidgetSupport.canSend("run_shell_command", windowsFull))
        assertEquals(WidgetAvailability.UNSUPPORTED_ACTION, WidgetSupport.availability(action("run_shell_command"), windowsFull))
    }

    // -- platform --

    @Test
    fun aWindowsDesktopEnablesEveryLibraryAction() {
        for (definition in com.alicontrol.app.actions.ActionLibrary.all) {
            assertEquals(
                "${definition.id} should be available on Windows",
                WidgetAvailability.AVAILABLE, WidgetSupport.availability(action(definition.id), windowsFull)
            )
            assertTrue(WidgetSupport.canSend(definition.id, windowsFull))
        }
    }

    @Test
    fun aDesktopOfAnUnknownPlatformDisablesRemoteWidgetsButKeepsThem() {
        val unknownPlatform = DesktopInfo(hasIdentity = true, platform = null, platformId = "plan9", capabilities = null)
        val widget = action(AliControlCommands.LOCK_SCREEN)

        assertEquals(WidgetAvailability.UNSUPPORTED_ACTION, WidgetSupport.availability(widget, unknownPlatform))
        assertFalse(WidgetSupport.canSend(AliControlCommands.LOCK_SCREEN, unknownPlatform))
        // Informational widgets are unaffected.
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(
            DashboardWidget("c", WidgetType.CLOCK, 0, 0, 2, 1, WidgetConfig.Clock()), unknownPlatform
        ))
    }

    // -- capabilities --

    @Test
    fun actionWidgetsNeedTheActionsCapabilityOnceCapabilitiesAreKnown() {
        val noActions = windowsFull.copy(capabilities = setOf(DesktopCapability.MEDIA))
        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(action(AliControlCommands.LOCK_SCREEN), noActions))
        assertFalse(WidgetSupport.canSend(AliControlCommands.LOCK_SCREEN, noActions))
    }

    @Test
    fun capabilitiesNotYetAnnouncedNeverDisableAnything() {
        val introducedButNoCapabilitiesYet = windowsFull.copy(capabilities = null)
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(action(AliControlCommands.LOCK_SCREEN), introducedButNoCapabilitiesYet))
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(metric("GPU"), introducedButNoCapabilitiesYet))
    }

    @Test
    fun metricsNeedTheirMatchingTelemetryCapability() {
        val noGpu = windowsFull.copy(capabilities = windowsFull.capabilities!! - DesktopCapability.TELEMETRY_GPU)

        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(metric("GPU"), noGpu))
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(metric("CPU"), noGpu))
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(metric("RAM"), noGpu))
    }

    @Test
    fun gaugesFollowTheSameRuleAsMetrics() {
        val noGpu = windowsFull.copy(capabilities = windowsFull.capabilities!! - DesktopCapability.TELEMETRY_GPU)
        val gauge = DashboardWidget("g", WidgetType.GAUGE, 0, 0, 2, 1, WidgetConfig.Gauge("GPU"))
        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(gauge, noGpu))
    }

    @Test
    fun temperatureWidgetsAreUnavailableOnAnAgentThatDoesNotAnnounceThem() {
        // The Windows agent never announces telemetry.temperature.* (no reliable user-mode source).
        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(metric("CPU TEMP"), windowsFull))
        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(metric("GPU TEMP"), windowsFull))
    }

    @Test
    fun aLabelWithNoLiveSourceNeedsNoCapability() {
        assertTrue(WidgetSupport.requiredCapabilities(metric("FAN")).isEmpty())
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(metric("FAN"), windowsFull))
    }

    @Test
    fun theMediaWidgetsTransportButtonsFollowTheActionsCapabilityNotTheMediaOne() {
        val media = DashboardWidget(
            "media", WidgetType.MEDIA, 0, 0, 3, 2,
            WidgetConfig.Media(AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK)
        )
        val noMediaSessions = windowsFull.copy(capabilities = setOf(DesktopCapability.ACTIONS))

        // Without the `media` capability there is simply no metadata ("Media unavailable") —
        // the play/pause buttons still work through the system media keys.
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(media, noMediaSessions))
        assertEquals(setOf(DesktopCapability.ACTIONS), WidgetSupport.requiredCapabilities(media))
    }

    @Test
    fun localAndInformationalWidgetsNeverNeedACapability() {
        val clock = DashboardWidget("c", WidgetType.CLOCK, 0, 0, 2, 1, WidgetConfig.Clock())
        val overview = DashboardWidget("o", WidgetType.DAILY_OVERVIEW, 0, 0, 3, 2, WidgetConfig.DailyOverview())
        val shortcut = DashboardWidget("s", WidgetType.SHORTCUT, 0, 0, 1, 1, WidgetConfig.Shortcut("Device", ShortcutDestination.DEVICE_SETTINGS))
        val nothing = windowsFull.copy(capabilities = emptySet())

        for (widget in listOf(clock, overview, shortcut)) {
            assertTrue(WidgetSupport.requiredCapabilities(widget).isEmpty())
            assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(widget, nothing))
        }
    }

    // -- the saved layout is never touched --

    @Test
    fun availabilityNeverChangesTheSavedLayout() {
        val dashboard = DefaultDashboardFactory.create()
        val before = DashboardSerializer.serialize(dashboard)

        val barren = DesktopInfo(hasIdentity = true, platform = null, platformId = "linux", capabilities = emptySet())
        for (page in dashboard.pages) for (widget in page.widgets) WidgetSupport.availability(widget, barren)

        assertEquals(before, DashboardSerializer.serialize(dashboard))
    }

    @Test
    fun aCapabilityThatIsTemporarilyAbsentAndLaterAnnouncedRestoresTheWidget() {
        val gpu = metric("GPU")
        val without = windowsFull.copy(capabilities = windowsFull.capabilities!! - DesktopCapability.TELEMETRY_GPU)

        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(gpu, without))
        assertEquals(WidgetAvailability.AVAILABLE, WidgetSupport.availability(gpu, windowsFull))
    }

    @Test
    fun everyDefaultWidgetIsAvailableOnAFullyCapableWindowsDesktop() {
        for (page in DefaultDashboardFactory.create().pages) {
            for (widget in page.widgets) {
                assertEquals(
                    "${page.id}/${widget.id}",
                    WidgetAvailability.AVAILABLE, WidgetSupport.availability(widget, windowsFull)
                )
            }
        }
    }

    @Test
    fun theTemperatureLabelsMapToTheirCapabilities() {
        assertEquals(setOf(DesktopCapability.TELEMETRY_TEMPERATURE_CPU), WidgetSupport.requiredCapabilities(metric("CPU TEMP")))
        assertEquals(setOf(DesktopCapability.TELEMETRY_TEMPERATURE_GPU), WidgetSupport.requiredCapabilities(metric("GPU TEMPERATURE")))
    }
}
