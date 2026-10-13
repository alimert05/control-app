package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.ble.AliControlCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped default dashboard is Windows-native: it uses Windows Search, Task
 * View, File Explorer and Task Manager, and none of the retired reference-platform
 * action ids. (There is no separate "profile" class per platform; the defaults are
 * data in [DefaultDashboardFactory], shared by every desktop.)
 */
class WindowsDefaultsTest {

    private val dashboard = DefaultDashboardFactory.create()

    private fun actionsOn(pageId: String): Set<String> =
        dashboard.page(pageId)!!.widgets.flatMap { it.referencedActionIds() }.toSet()

    @Test
    fun theDefaultDashboardIsValidAndHasTheThreeShippedPages() {
        assertTrue(dashboard.isValid())
        assertEquals(listOf("Dashboard", "Media", "Dev"), dashboard.orderedPages.map { it.name })
    }

    @Test
    fun noDefaultWidgetReferencesARetiredReferencePlatformAction() {
        for (page in dashboard.pages) for (widget in page.widgets) {
            for (id in widget.referencedActionIds()) {
                assertFalse("${page.id}/${widget.id} uses retired action $id", ActionIdMigration.isLegacy(id))
            }
        }
    }

    @Test
    fun everyDefaultWidgetIsSupportedOnWindows() {
        for (page in dashboard.pages) for (widget in page.widgets) {
            assertTrue("${page.id}/${widget.id}", widget.isSupportedOn(Platform.WINDOWS))
        }
    }

    @Test
    fun theDashboardPageHasWindowsSearchTaskViewAndExplorer() {
        val actions = actionsOn("page_dashboard")
        assertTrue(AliControlCommands.WINDOWS_SEARCH in actions)
        assertTrue(AliControlCommands.TASK_VIEW in actions)
        assertTrue(AliControlCommands.OPEN_EXPLORER in actions)
        assertTrue(AliControlCommands.OPEN_BROWSER in actions)
        assertTrue(AliControlCommands.OPEN_TERMINAL in actions)
    }

    @Test
    fun theDevPageHasTheWindowsDeveloperTools() {
        val actions = actionsOn("page_dev")
        for (expected in listOf(
            AliControlCommands.OPEN_VSCODE, AliControlCommands.OPEN_TERMINAL, AliControlCommands.OPEN_BROWSER,
            AliControlCommands.OPEN_EXPLORER, AliControlCommands.OPEN_TASK_MANAGER, AliControlCommands.WINDOWS_SEARCH,
            AliControlCommands.OPEN_SYSTEM_SETTINGS, AliControlCommands.SCREENSHOT
        )) {
            assertTrue("$expected missing from the Dev page", expected in actions)
        }
        val metrics = dashboard.page("page_dev")!!.widgets.map { it.config }
        assertTrue(metrics.any { it is WidgetConfig.Metric && it.label == "CPU" })
        assertTrue(metrics.any { it is WidgetConfig.Metric && it.label == "RAM" })
        assertTrue(metrics.any { it is WidgetConfig.Gauge && it.label == "GPU" })
    }

    @Test
    fun theMediaPageKeepsTheFullMediaWidgetAndSpotify() {
        val media = dashboard.page("page_media")!!
        assertTrue(media.widgets.any { it.type == WidgetType.MEDIA })
        assertTrue(AliControlCommands.OPEN_SPOTIFY in actionsOn("page_media"))
    }

    @Test
    fun theDashboardPageStillHasNoLargeMediaPlayerButKeepsCompactControls() {
        val page = dashboard.page("page_dashboard")!!
        assertFalse(page.widgets.any { it.type == WidgetType.MEDIA })
        assertTrue(page.widgets.any { it.type == WidgetType.ACTION_GROUP })
        assertTrue(page.widgets.any { it.type == WidgetType.DAILY_OVERVIEW })
    }

    @Test
    fun theDefaultsAreSavedAsTheCurrentSchemaAndRoundTrip() {
        val json = DashboardSerializer.serialize(dashboard)
        assertTrue(json.contains("\"schemaVersion\":${DashboardSerializer.SCHEMA_VERSION}"))
        assertEquals(dashboard, DashboardSerializer.deserialize(json))
    }

    @Test
    fun defaultWidgetIdsAreUniqueWithinEachPage() {
        for (page in dashboard.pages) {
            val ids = page.widgets.map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Test
    fun everyDefaultActionExistsInTheActionLibrary() {
        for (page in dashboard.pages) for (widget in page.widgets) for (id in widget.referencedActionIds()) {
            assertTrue("$id", ActionLibrary.find(id) != null)
        }
    }
}
