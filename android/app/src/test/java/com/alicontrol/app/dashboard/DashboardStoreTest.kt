package com.alicontrol.app.dashboard

import com.alicontrol.app.ble.AliControlCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Load/save/reset behavior, driven through an in-memory [LayoutPersistence]
 * so the rules can be tested without SharedPreferences or a Context.
 */
class DashboardStoreTest {

    private class FakePersistence(var stored: String? = null) : LayoutPersistence {
        var writes = 0
        override fun read(): String? = stored
        override fun write(json: String) {
            stored = json
            writes++
        }
        override fun clear() {
            stored = null
        }
    }

    private fun customDashboard(): Dashboard {
        val dashboard = (DashboardEditor.createPage(DefaultDashboardFactory.create(), "Gaming") as EditResult.Success).value
        val page = (
            DashboardEditor.addActionWidget(dashboard.orderedPages.last(), AliControlCommands.OPEN_SPOTIFY)
                as EditResult.Success
            ).value
        return dashboard.withPage(page)
    }

    @Test
    fun aFreshInstallGetsTheShippedDefaultLayout() {
        val store = DashboardStore(FakePersistence())

        assertEquals(DefaultDashboardFactory.create(), store.load())
        assertFalse(store.hasStoredLayout())
    }

    @Test
    fun aSavedLayoutIsWhatComesBack() {
        val persistence = FakePersistence()
        val store = DashboardStore(persistence)
        val custom = customDashboard()

        store.save(custom)

        assertEquals(custom, store.load())
        assertTrue(store.hasStoredLayout())
        assertNotEquals(DefaultDashboardFactory.create(), store.load())
    }

    @Test
    fun aSavedLayoutIsNotSilentlyReplacedByAChangedDefault() {
        val persistence = FakePersistence()
        val custom = customDashboard()
        DashboardStore(persistence).save(custom)

        // A later build ships a different default layout; the user's saved
        // layout must still win.
        val newDefault = Dashboard(listOf(DashboardPage("brand_new", "Brand New", 0, emptyList())))
        val store = DashboardStore(persistence) { newDefault }

        assertEquals(custom, store.load())
    }

    @Test
    fun corruptedStorageFallsBackToTheDefaultLayout() {
        val store = DashboardStore(FakePersistence("{\"schemaVersion\":1,\"pages\":"))

        assertEquals(DefaultDashboardFactory.create(), store.load())
        assertFalse(store.hasStoredLayout())
    }

    @Test
    fun structurallyInvalidStorageFallsBackToTheDefaultLayout() {
        val overlapping = """
            {"schemaVersion":2,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":0,"y":0,"w":2,"h":2,"config":{"actionId":"lock_screen"}},
              {"id":"b","type":"ACTION","x":0,"y":0,"w":1,"h":1,"config":{"actionId":"screenshot"}}
            ]}]}
        """.trimIndent()
        val store = DashboardStore(FakePersistence(overlapping))

        assertEquals(DefaultDashboardFactory.create(), store.load())
    }

    @Test
    fun resetClearsOnlyTheStoredLayoutAndReturnsTheDefault() {
        val persistence = FakePersistence()
        val store = DashboardStore(persistence)
        store.save(customDashboard())

        val afterReset = store.resetToDefault()

        assertNull(persistence.stored)
        assertEquals(DefaultDashboardFactory.create(), afterReset)
        assertEquals(DefaultDashboardFactory.create(), store.load())
    }

    @Test
    fun everySaveOverwritesTheSingleStoredLayout() {
        val persistence = FakePersistence()
        val store = DashboardStore(persistence)

        store.save(DefaultDashboardFactory.create())
        store.save(customDashboard())

        assertEquals(2, persistence.writes)
        assertEquals(customDashboard(), store.load())
    }

    // -- saved-layout migration (macOS-era schema 1 -> Windows schema 2) --

    private val macOsEraJson = """
        {"schemaVersion":1,"pages":[{"id":"p","name":"Mine","order":0,"widgets":[
          {"id":"a","type":"ACTION","x":0,"y":0,"w":1,"h":1,"config":{"actionId":"spotlight"}},
          {"id":"b","type":"ACTION","x":1,"y":0,"w":2,"h":1,"config":{"actionId":"open_activity_monitor"}},
          {"id":"c","type":"ACTION","x":0,"y":1,"w":1,"h":1,"config":{"actionId":"lock_screen"}}
        ]}]}
    """.trimIndent()

    @Test
    fun aMacOsEraLayoutLoadsMigratedInsteadOfFallingBackToTheDefaults() {
        val store = DashboardStore(FakePersistence(macOsEraJson))

        val loaded = store.load()

        assertNotEquals(DefaultDashboardFactory.create(), loaded)
        assertEquals(listOf("p"), loaded.pages.map { it.id })
        val widgets = loaded.page("p")!!.widgets
        assertEquals(listOf("windows_search"), widgets.first { it.id == "a" }.referencedActionIds())
        assertEquals(listOf("open_task_manager"), widgets.first { it.id == "b" }.referencedActionIds())
        assertEquals(listOf("lock_screen"), widgets.first { it.id == "c" }.referencedActionIds())
        assertEquals(3, widgets.size)
    }

    @Test
    fun theUpgradedLayoutIsWrittenBackSoTheMigrationRunsOnce() {
        val persistence = FakePersistence(macOsEraJson)
        val store = DashboardStore(persistence)

        val first = store.load()

        assertEquals(1, persistence.writes)
        assertTrue(persistence.stored!!.contains("\"schemaVersion\":2"))
        assertFalse(persistence.stored!!.contains("spotlight"))
        assertFalse(persistence.stored!!.contains("open_activity_monitor"))

        val second = store.load()
        assertEquals(first, second)
        assertEquals("a current-schema layout must not be rewritten on every load", 1, persistence.writes)
    }

    @Test
    fun aMigratedLayoutCountsAsAStoredUserLayout() {
        assertTrue(DashboardStore(FakePersistence(macOsEraJson)).hasStoredLayout())
    }

    @Test
    fun migrationNeverReplacesTheUsersLayoutWithAChangedDefault() {
        val newDefault = Dashboard(listOf(DashboardPage("brand_new", "Brand New", 0, emptyList())))
        val loaded = DashboardStore(FakePersistence(macOsEraJson)) { newDefault }.load()
        assertEquals(listOf("p"), loaded.pages.map { it.id })
    }

    @Test
    fun anUnmigratableLayoutStillFallsBackSafely() {
        val unknown = macOsEraJson.replace("lock_screen", "run_shell_command")
        val persistence = FakePersistence(unknown)
        val store = DashboardStore(persistence)

        assertEquals(DefaultDashboardFactory.create(), store.load())
        assertEquals("nothing is rewritten when the layout was not understood", 0, persistence.writes)
    }
}
