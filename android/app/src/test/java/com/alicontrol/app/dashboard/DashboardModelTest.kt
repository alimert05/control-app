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
    fun platformPolicyDisablesRemoteWidgetsWithUnknownActionsButNotInformationalWidgets() {
        val search = DashboardWidget(
            "search", WidgetType.ACTION, 0, 0, 2, 2,
            WidgetConfig.Action(AliControlCommands.WINDOWS_SEARCH)
        )
        // An action id this build does not know can never be served by any platform.
        val unknown = DashboardWidget("unknown", WidgetType.ACTION, 0, 0, 2, 2, WidgetConfig.Action("not_an_action"))
        val metric = DashboardWidget("cpu", WidgetType.METRIC, 0, 0, 2, 2, WidgetConfig.Metric("CPU"))

        assertTrue(search.isSupportedOn(Platform.WINDOWS))
        assertFalse(unknown.isSupportedOn(Platform.WINDOWS))
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
            AliControlCommands.WINDOWS_SEARCH,
            AliControlCommands.TASK_VIEW,
            AliControlCommands.OPEN_EXPLORER,
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
            AliControlCommands.OPEN_EXPLORER,
            AliControlCommands.WINDOWS_SEARCH,
            AliControlCommands.OPEN_TASK_MANAGER,
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

    // -- TASK-008: refined defaults on the 6x4 grid --

    @Test
    fun theShippedDefaultLayoutIsStructurallyValid() {
        assertTrue(dashboard.isValid())
        for (page in pages) {
            assertTrue("${page.id} is not a valid page", page.isValid())
        }
    }

    @Test
    fun everyDefaultWidgetRespectsItsTypeSizeConstraints() {
        for (page in pages) {
            for (widget in page.widgets) {
                assertTrue(
                    "${page.id}/${widget.id} is ${widget.gridWidth}x${widget.gridHeight}, " +
                        "outside ${widget.type} limits",
                    widget.satisfiesSizeConstraints()
                )
            }
        }
    }

    @Test
    fun theDashboardPageLeadsWithADailyOverviewWidget() {
        val overview = page("page_dashboard").widgets.filter { it.type == WidgetType.DAILY_OVERVIEW }

        assertEquals(1, overview.size)
        assertTrue(overview.single().config is WidgetConfig.DailyOverview)
    }

    @Test
    fun theLargeMediaWidgetIsGoneFromTheDashboardPage() {
        assertTrue(page("page_dashboard").widgets.none { it.type == WidgetType.MEDIA })
    }

    @Test
    fun theDashboardPageKeepsCompactMediaControls() {
        val transport = page("page_dashboard").widgets
            .filter { it.type == WidgetType.ACTION_GROUP }
            .firstOrNull { widget ->
                widget.referencedActionIds().containsAll(
                    listOf(
                        AliControlCommands.PREVIOUS_TRACK,
                        AliControlCommands.PLAY_PAUSE,
                        AliControlCommands.NEXT_TRACK
                    )
                )
            }

        assertNotNull("dashboard page has no compact transport controls", transport)
        // Compact means exactly that: a single row, not a full-height player.
        assertEquals(1, transport!!.gridHeight)
    }

    @Test
    fun theMediaPageStillHostsTheFullMediaWidget() {
        val media = page("page_media").widgets.filter { it.type == WidgetType.MEDIA }

        assertEquals(1, media.size)
        assertTrue("the media page's player should be large", media.single().gridWidth >= 3)
    }

    @Test
    fun theDefaultLayoutKeepsDeviceSettingsAndAllActionsReachableFromWidgets() {
        val destinations = pages
            .flatMap { it.widgets }
            .mapNotNull { (it.config as? WidgetConfig.Shortcut)?.destination }
            .toSet()

        assertTrue(ShortcutDestination.DEVICE_SETTINGS in destinations)
        assertTrue(ShortcutDestination.ALL_ACTIONS in destinations)
    }

    @Test
    fun theDashboardPageUsesCompactSingleCellActionsForItsAppShortcuts() {
        // Six 1x1 actions in one row is the point of the refined layout — it
        // is what stops single actions from eating a quarter of the screen.
        val compactActions = page("page_dashboard").widgets
            .filter { it.type == WidgetType.ACTION && it.gridWidth == 1 && it.gridHeight == 1 }

        assertTrue("expected several 1x1 actions, found ${compactActions.size}", compactActions.size >= 6)
    }

    private fun page(pageId: String): DashboardPage = pages.single { it.id == pageId }

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
