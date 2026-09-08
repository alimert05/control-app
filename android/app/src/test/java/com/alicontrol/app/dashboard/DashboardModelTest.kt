package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.DangerLevel
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.ble.AliControlCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardModelTest {

    private val dashboard = DefaultDashboardFactory.create()
    private val pages = dashboard.orderedPages

    @Test
    fun defaultDashboardHasExactlyThreeOrderedPages() {
        assertEquals(3, pages.size)
        assertEquals(listOf("page_dashboard", "page_media", "page_dev"), pages.map { it.id })
        assertEquals(listOf(0, 1, 2), pages.map { it.order })
    }

    @Test
    fun defaultPageIdsAreUnique() {
        val ids = pages.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun widgetIdsAreUniqueWithinEveryPage() {
        for (page in pages) {
            val ids = page.widgets.map { it.id }
            assertEquals("duplicate widget id on ${page.id}", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun everyWidgetPositionAndSizeStaysInsideTheGrid() {
        for (page in pages) {
            for (widget in page.widgets) {
                assertTrue("${page.id}/${widget.id} is outside the grid", widget.isWithinBounds())
            }
        }
    }

    @Test
    fun widgetsDoNotOverlapWithinAnyPage() {
        for (page in pages) {
            page.widgets.forEachIndexed { index, widget ->
                for (other in page.widgets.drop(index + 1)) {
                    assertFalse("${page.id}: ${widget.id} overlaps ${other.id}", widget.overlaps(other))
                }
            }
        }
    }

    @Test
    fun allReferencedActionsExistInTheActionLibrary() {
        for (page in pages) {
            for (widget in page.widgets) {
                for (actionId in widget.referencedActionIds()) {
                    assertNotNull("${page.id}/${widget.id} references unknown action $actionId", ActionLibrary.find(actionId))
                }
            }
        }
    }

    @Test
    fun unsupportedPlatformPolicyDisablesRemoteWidgetsButNotInformationalWidgets() {
        val macOnly = DashboardWidget(
            "spotlight", WidgetType.ACTION, 0, 0, 2, 2,
            WidgetConfig.Action(AliControlCommands.SPOTLIGHT)
        )
        val metric = DashboardWidget("cpu", WidgetType.METRIC, 0, 0, 2, 2, WidgetConfig.Metric("CPU"))

        assertTrue(macOnly.isSupportedOn(Platform.MACOS))
        assertFalse(macOnly.isSupportedOn(Platform.WINDOWS))
        assertTrue(metric.isSupportedOn(Platform.MACOS))
        assertTrue(metric.isSupportedOn(Platform.WINDOWS))
    }

    @Test
    fun actionReferencesRetainActionLibraryConfirmationMetadata() {
        val sleep = DashboardWidget(
            "sleep", WidgetType.ACTION, 0, 0, 2, 2,
            WidgetConfig.Action(AliControlCommands.SLEEP)
        )
        val definition = ActionLibrary.find(sleep.referencedActionIds().single())

        assertEquals(DangerLevel.CONFIRM, definition?.dangerLevel)
    }

    @Test
    fun dashboardPageContainsRequiredCoreActions() {
        assertPageContains(
            "page_dashboard",
            AliControlCommands.LOCK_SCREEN,
            AliControlCommands.SCREENSHOT,
            AliControlCommands.OPEN_TERMINAL,
            AliControlCommands.OPEN_VSCODE,
            AliControlCommands.SPOTLIGHT,
            AliControlCommands.MISSION_CONTROL,
            AliControlCommands.VOLUME_DOWN,
            AliControlCommands.VOLUME_UP,
            AliControlCommands.MUTE_TOGGLE,
            AliControlCommands.PREVIOUS_TRACK,
            AliControlCommands.PLAY_PAUSE,
            AliControlCommands.NEXT_TRACK
        )
    }

    @Test
    fun mediaPageContainsRequiredMediaActions() {
        assertPageContains(
            "page_media",
            AliControlCommands.PREVIOUS_TRACK,
            AliControlCommands.PLAY_PAUSE,
            AliControlCommands.NEXT_TRACK,
            AliControlCommands.VOLUME_DOWN,
            AliControlCommands.VOLUME_UP,
            AliControlCommands.MUTE_TOGGLE,
            AliControlCommands.OPEN_SPOTIFY
        )
    }

    @Test
    fun devPageContainsRequiredDevelopmentActions() {
        assertPageContains(
            "page_dev",
            AliControlCommands.OPEN_VSCODE,
            AliControlCommands.OPEN_TERMINAL,
            AliControlCommands.OPEN_BROWSER,
            AliControlCommands.OPEN_FINDER,
            AliControlCommands.SPOTLIGHT,
            AliControlCommands.OPEN_ACTIVITY_MONITOR,
            AliControlCommands.SCREENSHOT,
            AliControlCommands.OPEN_SYSTEM_SETTINGS
        )
    }

    @Test
    fun dashboardModelSupportsAnArbitraryPageCount() {
        val arbitrary = Dashboard(
            (0 until 7).reversed().map { index ->
                DashboardPage("page_$index", "Page $index", index, emptyList())
            }
        )

        assertEquals(7, arbitrary.pages.size)
        assertEquals((0 until 7).map { "page_$it" }, arbitrary.orderedPages.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun widgetTypeMustMatchItsConfiguration() {
        DashboardWidget("invalid", WidgetType.CLOCK, 0, 0, 2, 2, WidgetConfig.Metric("CPU"))
    }

    private fun assertPageContains(pageId: String, vararg expectedActionIds: String) {
        val actual = pages.single { it.id == pageId }
            .widgets
            .flatMap { it.referencedActionIds() }
            .toSet()
        for (expected in expectedActionIds) {
            assertTrue("$pageId is missing $expected", expected in actual)
        }
    }
}
