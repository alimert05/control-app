package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.dashboard.ActionIdMigration
import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.DashboardGrid
import com.alicontrol.app.dashboard.DashboardSerializer
import com.alicontrol.app.dashboard.DefaultDashboardFactory
import com.alicontrol.app.dashboard.ShortcutDestination
import com.alicontrol.app.dashboard.WidgetConfig
import com.alicontrol.app.dashboard.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four shipped templates: structurally valid on the 6x4 grid, Windows-only, and shaped as specified. */
class BuiltInProfilesTest {

    private fun template(id: String): Dashboard = BuiltInProfiles.dashboardFor(id)!!

    private fun actions(dashboard: Dashboard): Set<String> =
        dashboard.pages.flatMap { page -> page.widgets.flatMap { it.referencedActionIds() } }.toSet()

    private fun widgetConfigs(dashboard: Dashboard) = dashboard.pages.flatMap { page -> page.widgets.map { it.config } }

    private fun hasMetric(dashboard: Dashboard, label: String) = widgetConfigs(dashboard).any {
        (it is WidgetConfig.Metric && it.label == label) || (it is WidgetConfig.Gauge && it.label == label)
    }

    private fun hasDeviceShortcut(dashboard: Dashboard) = widgetConfigs(dashboard).any {
        it is WidgetConfig.Shortcut && it.destination == ShortcutDestination.DEVICE_SETTINGS
    }

    // -- validity of every template --

    @Test
    fun everyBuiltInProfileHasATemplate() {
        for (id in ProfileIds.BUILT_IN) assertTrue(id, BuiltInProfiles.dashboardFor(id) != null)
        assertNull(BuiltInProfiles.dashboardFor("custom_abc"))
        assertNull(BuiltInProfiles.dashboardFor("Dev"))
    }

    @Test
    fun everyTemplateIsAValidDashboard() {
        for (id in ProfileIds.BUILT_IN) {
            val dashboard = template(id)
            assertTrue("$id dashboard", dashboard.isValid())
            assertTrue("$id has pages", dashboard.pages.isNotEmpty())
            for (page in dashboard.pages) assertTrue("$id/${page.id}", page.isValid())
        }
    }

    @Test
    fun everyPlacementIsInsideTheSixByFourGrid() {
        for (id in ProfileIds.BUILT_IN) for (page in template(id).pages) for (widget in page.widgets) {
            val where = "$id/${page.id}/${widget.id}"
            assertTrue(where, widget.gridX >= 0 && widget.gridY >= 0)
            assertTrue(where, widget.gridRight <= DashboardGrid.COLUMNS)
            assertTrue(where, widget.gridBottom <= DashboardGrid.ROWS)
        }
    }

    @Test
    fun noTwoWidgetsOnAPageOverlap() {
        for (id in ProfileIds.BUILT_IN) for (page in template(id).pages) {
            val widgets = page.widgets
            for (i in widgets.indices) for (j in i + 1 until widgets.size) {
                assertFalse("$id/${page.id}: ${widgets[i].id} overlaps ${widgets[j].id}", widgets[i].overlaps(widgets[j]))
            }
        }
    }

    @Test
    fun everyWidgetRespectsItsTypesSizeLimits() {
        for (id in ProfileIds.BUILT_IN) for (page in template(id).pages) for (widget in page.widgets) {
            assertTrue("$id/${page.id}/${widget.id} ${widget.type} ${widget.gridWidth}x${widget.gridHeight}", widget.satisfiesSizeConstraints())
        }
    }

    @Test
    fun everyReferencedActionExistsInTheActionLibraryAndSupportsWindows() {
        for (id in ProfileIds.BUILT_IN) for (page in template(id).pages) for (widget in page.widgets) {
            for (actionId in widget.referencedActionIds()) {
                assertTrue("$id/${page.id}/${widget.id} -> $actionId", ActionLibrary.find(actionId) != null)
            }
            assertTrue("$id/${page.id}/${widget.id}", widget.isSupportedOn(Platform.WINDOWS))
        }
    }

    @Test
    fun noTemplateUsesARetiredReferencePlatformAction() {
        for (id in ProfileIds.BUILT_IN) for (actionId in actions(template(id))) {
            assertFalse("$id uses $actionId", ActionIdMigration.isLegacy(actionId))
        }
    }

    @Test
    fun pageOrderIsContiguousAndPageIdsAreUnique() {
        for (id in ProfileIds.BUILT_IN) {
            val pages = template(id).pages
            assertEquals("$id order", pages.indices.toList(), pages.map { it.order }.sorted())
            assertEquals("$id page ids", pages.size, pages.map { it.id }.toSet().size)
        }
    }

    @Test
    fun widgetIdsAreUniqueWithinEachPageAndAcrossEachProfile() {
        for (id in ProfileIds.BUILT_IN) {
            val all = template(id).pages.flatMap { page -> page.widgets.map { it.id } }
            assertEquals("$id widget ids", all.size, all.toSet().size)
        }
    }

    @Test
    fun templatesRoundTripThroughTheSerializer() {
        for (id in ProfileIds.BUILT_IN) {
            val dashboard = template(id)
            assertEquals(id, dashboard, DashboardSerializer.deserialize(DashboardSerializer.serialize(dashboard)))
        }
    }

    @Test
    fun eachCallBuildsAnEqualButIndependentDashboard() {
        for (id in ProfileIds.BUILT_IN) {
            val a = template(id)
            val b = template(id)
            assertEquals(a, b)
            assertNotSame(a, b)
            assertNotSame(a.pages, b.pages)
        }
    }

    // -- the profiles are distinct, and use only Windows actions --

    @Test
    fun theFourTemplatesAreDifferentLayouts() {
        val dashboards = ProfileIds.BUILT_IN.map(::template)
        assertEquals(4, dashboards.toSet().size)
    }

    // -- Default --

    @Test
    fun defaultIsExactlyTheExistingShippedLayout() {
        assertEquals(DefaultDashboardFactory.create(), template(ProfileIds.DEFAULT))
        assertEquals(listOf("Dashboard", "Media", "Dev"), template(ProfileIds.DEFAULT).orderedPages.map { it.name })
    }

    @Test
    fun defaultKeepsTheGeneralPurposeDashboardContent() {
        val dashboard = template(ProfileIds.DEFAULT)
        val first = dashboard.orderedPages.first()
        assertTrue(first.widgets.any { it.type == WidgetType.DAILY_OVERVIEW })
        for (metric in listOf("CPU", "RAM", "GPU")) assertTrue(metric, hasMetric(dashboard, metric))
        for (action in listOf(
            AliControlCommands.WINDOWS_SEARCH, AliControlCommands.TASK_VIEW, AliControlCommands.OPEN_EXPLORER,
            AliControlCommands.OPEN_BROWSER, AliControlCommands.LOCK_SCREEN, AliControlCommands.SCREENSHOT,
            AliControlCommands.PLAY_PAUSE, AliControlCommands.VOLUME_UP, AliControlCommands.VOLUME_DOWN
        )) assertTrue(action, action in actions(dashboard))
        assertTrue(hasDeviceShortcut(dashboard))
    }

    // -- Dev --

    @Test
    fun devHasTheDevelopmentToolsAndSystemReadings() {
        val dashboard = template(ProfileIds.DEV)
        assertEquals(listOf("Dev", "Tools", "System"), dashboard.orderedPages.map { it.name })
        for (action in listOf(
            AliControlCommands.OPEN_VSCODE, AliControlCommands.OPEN_TERMINAL, AliControlCommands.OPEN_BROWSER,
            AliControlCommands.OPEN_EXPLORER, AliControlCommands.OPEN_TASK_MANAGER, AliControlCommands.WINDOWS_SEARCH,
            AliControlCommands.SCREENSHOT
        )) assertTrue(action, action in actions(dashboard))
        for (metric in listOf("CPU", "RAM", "GPU")) assertTrue(metric, hasMetric(dashboard, metric))
        assertTrue(hasDeviceShortcut(dashboard))
    }

    @Test
    fun devExposesExactlyTheExistingWindowsActionsItNeeds() {
        // The whole set, spelled out: anything beyond the shipped Windows launchers and edit helpers
        // (AI-assistant, Git and build/test workflows are planned for a later release) would show up here as a failure.
        assertEquals(
            setOf(
                AliControlCommands.OPEN_VSCODE, AliControlCommands.OPEN_TERMINAL, AliControlCommands.OPEN_BROWSER,
                AliControlCommands.OPEN_EXPLORER, AliControlCommands.WINDOWS_SEARCH, AliControlCommands.OPEN_TASK_MANAGER,
                AliControlCommands.SCREENSHOT, AliControlCommands.SHOW_DESKTOP, AliControlCommands.TASK_VIEW,
                AliControlCommands.COPY, AliControlCommands.PASTE, AliControlCommands.UNDO, AliControlCommands.REDO,
                AliControlCommands.MINIMIZE_WINDOW, AliControlCommands.FULLSCREEN_TOGGLE,
                AliControlCommands.OPEN_SYSTEM_SETTINGS, AliControlCommands.LOCK_SCREEN
            ),
            actions(template(ProfileIds.DEV))
        )
    }

    // -- Game --

    @Test
    fun gameHasSystemReadingsMediaControlsAndQuickActions() {
        val dashboard = template(ProfileIds.GAME)
        assertEquals(listOf("Game", "Media", "System"), dashboard.orderedPages.map { it.name })
        for (metric in listOf("CPU", "RAM", "GPU")) assertTrue(metric, hasMetric(dashboard, metric))
        for (action in listOf(
            AliControlCommands.PLAY_PAUSE, AliControlCommands.PREVIOUS_TRACK, AliControlCommands.NEXT_TRACK,
            AliControlCommands.VOLUME_UP, AliControlCommands.VOLUME_DOWN, AliControlCommands.MUTE_TOGGLE,
            AliControlCommands.OPEN_SPOTIFY, AliControlCommands.OPEN_TASK_MANAGER,
            AliControlCommands.SHOW_DESKTOP, AliControlCommands.LOCK_SCREEN
        )) assertTrue(action, action in actions(dashboard))
        assertTrue(hasDeviceShortcut(dashboard))
        assertTrue("the Media page has the full media widget", dashboard.page("page_game_media")!!.widgets.any { it.type == WidgetType.MEDIA })
    }

    @Test
    fun gameExposesExactlyTheExistingWindowsActionsItNeeds() {
        // No Steam, Discord, fan, temperature or game-detection actions exist, so none may appear.
        assertEquals(
            setOf(
                AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK,
                AliControlCommands.VOLUME_DOWN, AliControlCommands.VOLUME_UP, AliControlCommands.MUTE_TOGGLE,
                AliControlCommands.OPEN_SPOTIFY, AliControlCommands.OPEN_TASK_MANAGER, AliControlCommands.SHOW_DESKTOP,
                AliControlCommands.LOCK_SCREEN, AliControlCommands.SCREENSHOT, AliControlCommands.TASK_VIEW,
                AliControlCommands.WINDOWS_SEARCH, AliControlCommands.OPEN_SYSTEM_SETTINGS
            ),
            actions(template(ProfileIds.GAME))
        )
    }

    // -- Media --

    @Test
    fun mediaHasNowPlayingControlsAndAppsPages() {
        val dashboard = template(ProfileIds.MEDIA)
        assertEquals(listOf("Now Playing", "Controls", "Apps"), dashboard.orderedPages.map { it.name })
    }

    @Test
    fun mediaPrimaryPageUsesTheExistingMediaWidgetLarge() {
        val primary = template(ProfileIds.MEDIA).orderedPages.first()
        val media = primary.widgets.single { it.type == WidgetType.MEDIA }

        assertTrue("at least 6x3", media.gridWidth == 6 && media.gridHeight >= 3)
        assertEquals(
            WidgetConfig.Media(AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK),
            media.config
        )
    }

    @Test
    fun mediaHasTransportVolumeMuteAndTheSpotifyLauncher() {
        val actions = actions(template(ProfileIds.MEDIA))
        for (action in listOf(
            AliControlCommands.PLAY_PAUSE, AliControlCommands.PREVIOUS_TRACK, AliControlCommands.NEXT_TRACK,
            AliControlCommands.VOLUME_UP, AliControlCommands.VOLUME_DOWN, AliControlCommands.MUTE_TOGGLE,
            AliControlCommands.OPEN_SPOTIFY
        )) assertTrue(action, action in actions)
    }

    // -- the initial collection --

    @Test
    fun theInitialCollectionHoldsTheFourTemplatesWithDefaultSelected() {
        val collection = BuiltInProfiles.initialCollection()

        assertTrue(collection.isValid())
        assertEquals(ProfileIds.DEFAULT, collection.lastSelectedProfileId)
        assertEquals(ProfileIds.DEFAULT, collection.defaultProfileId)
        for (profile in collection.profiles) {
            assertTrue(profile.builtIn)
            assertEquals(template(profile.id), profile.dashboard)
        }
    }

    @Test
    fun completeAddsOnlyTheMissingBuiltInProfilesAndKeepsTheRest() {
        val customDefault = customLegacyDashboard()
        val partial = BuiltInProfiles.initialCollection(customDefault).let {
            it.copy(profiles = it.profiles.filter { p -> p.id == ProfileIds.DEFAULT || p.id == ProfileIds.GAME })
        }

        val completed = BuiltInProfiles.complete(partial)

        assertEquals(ProfileIds.BUILT_IN.toSet(), completed.profiles.map { it.id }.toSet())
        assertEquals("the user's layout is untouched", customDefault, completed.profile(ProfileIds.DEFAULT)!!.dashboard)
        assertEquals(template(ProfileIds.DEV), completed.profile(ProfileIds.DEV)!!.dashboard)
        assertEquals(listOf(0, 1, 2, 3), completed.orderedProfiles.map { it.order })
    }
}
