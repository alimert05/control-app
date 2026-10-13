package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.ble.AliControlCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved-layout migration: a dashboard stored by a macOS-era build (schema 1) must
 * load on the Windows build with every widget kept in place, its retired action
 * ids mapped to their Windows equivalents — and the upgrade written back once.
 */
class ActionIdMigrationTest {

    // -- the mapping itself --

    @Test
    fun exactlyTheFourDocumentedIdsAreMigrated() {
        assertEquals(
            mapOf(
                "spotlight" to "windows_search",
                "mission_control" to "task_view",
                "open_finder" to "open_explorer",
                "open_activity_monitor" to "open_task_manager"
            ),
            ActionIdMigration.LEGACY_MACOS_ACTIONS
        )
    }

    @Test
    fun everyMigrationTargetIsARealWindowsAction() {
        for ((old, new) in ActionIdMigration.LEGACY_MACOS_ACTIONS) {
            assertNotNull("$old -> $new: target must exist", ActionLibrary.find(new))
            assertNull("$old must no longer be an action", ActionLibrary.find(old))
        }
    }

    @Test
    fun otherIdsAreLeftUntouched() {
        for (action in ActionLibrary.all) {
            assertEquals(action.id, ActionIdMigration.migrate(action.id))
            assertFalse(ActionIdMigration.isLegacy(action.id))
        }
        assertEquals("something_unknown", ActionIdMigration.migrate("something_unknown"))
    }

    @Test
    fun legacyIdsAreRecognised() {
        assertTrue(ActionIdMigration.isLegacy("spotlight"))
        assertEquals(AliControlCommands.TASK_VIEW, ActionIdMigration.migrate("mission_control"))
    }

    // -- schema-1 documents --

    private fun widget(id: String, type: String, x: Int, y: Int, w: Int, h: Int, config: JSONObject) =
        JSONObject().put("id", id).put("type", type).put("x", x).put("y", y).put("w", w).put("h", h).put("config", config)

    private fun document(version: Int, vararg pages: JSONObject) =
        JSONObject().put("schemaVersion", version).put("pages", JSONArray(pages.toList())).toString()

    private fun page(id: String, name: String, order: Int, vararg widgets: JSONObject) =
        JSONObject().put("id", id).put("name", name).put("order", order).put("widgets", JSONArray(widgets.toList()))

    private fun action(id: String) = JSONObject().put("actionId", id)

    private val macOsEraLayout = document(
        1,
        page(
            "page_dashboard", "Dashboard", 0,
            widget("dash_spotlight", "ACTION", 2, 2, 1, 1, action("spotlight")),
            widget("dash_mission_control", "ACTION", 3, 2, 1, 1, action("mission_control")),
            widget("dash_finder", "ACTION", 4, 2, 1, 1, action("open_finder")),
            widget("dash_lock", "ACTION", 0, 0, 1, 1, action("lock_screen")),
            widget(
                "dash_group", "ACTION_GROUP", 0, 3, 3, 1,
                JSONObject().put("actionIds", JSONArray(listOf("previous_track", "play_pause", "next_track")))
            )
        ),
        page(
            "page_custom", "My Page", 1,
            widget("custom_am", "ACTION", 0, 0, 2, 1, action("open_activity_monitor")),
            widget(
                "custom_slider", "SLIDER", 0, 1, 2, 1,
                JSONObject().put("label", "Mix").put("actionIds", JSONArray(listOf("spotlight", "volume_up")))
            ),
            widget("custom_cpu", "METRIC", 4, 0, 1, 1, JSONObject().put("label", "CPU"))
        )
    )

    @Test
    fun aMacOsEraLayoutLoadsWithEveryWidgetKept() {
        val loaded = DashboardSerializer.deserializeWithInfo(macOsEraLayout)

        assertNotNull("a schema-1 layout must load, not fall back to defaults", loaded)
        val dashboard = loaded!!.dashboard
        assertEquals(listOf("page_dashboard", "page_custom"), dashboard.orderedPages.map { it.id })
        assertEquals(5, dashboard.page("page_dashboard")!!.widgets.size)
        assertEquals(3, dashboard.page("page_custom")!!.widgets.size)
    }

    @Test
    fun retiredIdsBecomeTheirWindowsEquivalentsInEveryWidgetKind() {
        val dashboard = DashboardSerializer.deserialize(macOsEraLayout)!!
        val dash = dashboard.page("page_dashboard")!!
        val custom = dashboard.page("page_custom")!!

        assertEquals(listOf("windows_search"), dash.widget("dash_spotlight")!!.referencedActionIds())
        assertEquals(listOf("task_view"), dash.widget("dash_mission_control")!!.referencedActionIds())
        assertEquals(listOf("open_explorer"), dash.widget("dash_finder")!!.referencedActionIds())
        assertEquals(listOf("open_task_manager"), custom.widget("custom_am")!!.referencedActionIds())
        assertEquals(listOf("windows_search", "volume_up"), custom.widget("custom_slider")!!.referencedActionIds())
    }

    @Test
    fun migratedWidgetsKeepTheirPlacementSizeAndId() {
        val dashboard = DashboardSerializer.deserialize(macOsEraLayout)!!
        val finder = dashboard.page("page_dashboard")!!.widget("dash_finder")!!

        assertEquals(WidgetPlacement(4, 2, 1, 1), finder.placement)
        assertEquals(WidgetType.ACTION, finder.type)
    }

    @Test
    fun idsThatNeverChangedAreUntouched() {
        val dash = DashboardSerializer.deserialize(macOsEraLayout)!!.page("page_dashboard")!!

        assertEquals(listOf("lock_screen"), dash.widget("dash_lock")!!.referencedActionIds())
        assertEquals(
            listOf("previous_track", "play_pause", "next_track"),
            dash.widget("dash_group")!!.referencedActionIds()
        )
    }

    @Test
    fun theMigrationIsReportedSoTheCallerCanPersistTheUpgrade() {
        val loaded = DashboardSerializer.deserializeWithInfo(macOsEraLayout)!!
        assertTrue(loaded.wasMigrated)
        assertEquals(1, loaded.migratedFromVersion)
    }

    @Test
    fun aSchemaOneLayoutWithOnlyUnchangedIdsStillUpgradesToSchemaTwo() {
        val json = document(1, page("p", "P", 0, widget("w", "ACTION", 0, 0, 1, 1, action("lock_screen"))))
        val loaded = DashboardSerializer.deserializeWithInfo(json)!!
        assertTrue(loaded.wasMigrated)
        assertEquals(2, JSONObject(DashboardSerializer.serialize(loaded.dashboard)).getInt("schemaVersion"))
    }

    @Test
    fun currentSchemaDocumentsAreNotMigrated() {
        val json = DashboardSerializer.serialize(DefaultDashboardFactory.create())
        val loaded = DashboardSerializer.deserializeWithInfo(json)!!
        assertFalse(loaded.wasMigrated)
        assertNull(loaded.migratedFromVersion)
    }

    @Test
    fun serialisingAMigratedLayoutProducesTheCurrentSchemaWithNoRetiredIds() {
        val migrated = DashboardSerializer.deserialize(macOsEraLayout)!!
        val rewritten = DashboardSerializer.serialize(migrated)

        assertEquals(DashboardSerializer.SCHEMA_VERSION, JSONObject(rewritten).getInt("schemaVersion"))
        for (retired in ActionIdMigration.LEGACY_MACOS_ACTIONS.keys) {
            assertFalse("$retired still present after migration", rewritten.contains("\"$retired\""))
        }
        // and it round-trips as a current document
        assertEquals(migrated, DashboardSerializer.deserialize(rewritten))
    }

    // -- strictness is preserved --

    @Test
    fun aCurrentSchemaDocumentStillRejectsARetiredId() {
        // Migration is for old documents only: a schema-2 file that names a retired
        // id is not something this build wrote, so it must not be trusted.
        val json = document(2, page("p", "P", 0, widget("w", "ACTION", 0, 0, 1, 1, action("spotlight"))))
        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aSchemaOneDocumentWithAnUnknownIdIsStillRejectedNotGuessedAt() {
        val json = document(1, page("p", "P", 0, widget("w", "ACTION", 0, 0, 1, 1, action("run_shell_command"))))
        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aFutureSchemaIsRefused() {
        val json = document(3, page("p", "P", 0, widget("w", "ACTION", 0, 0, 1, 1, action("lock_screen"))))
        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun schemaZeroAndNonsenseVersionsAreRefused() {
        val widgetJson = widget("w", "ACTION", 0, 0, 1, 1, action("lock_screen"))
        assertNull(DashboardSerializer.deserialize(document(0, page("p", "P", 0, widgetJson))))
        assertNull(DashboardSerializer.deserialize(document(-1, page("p", "P", 0, widgetJson))))
    }

    @Test
    fun aMigratedWidgetStillHonoursItsStructuralRules() {
        // A spotlight widget sized 3x3 breaks ACTION's 2x2 maximum: still rejected after migration.
        val json = document(1, page("p", "P", 0, widget("w", "ACTION", 0, 0, 3, 3, action("spotlight"))))
        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun migrationNeverDropsOrDuplicatesWidgets() {
        val before = JSONObject(macOsEraLayout).getJSONArray("pages")
        val expected = (0 until before.length()).sumOf { before.getJSONObject(it).getJSONArray("widgets").length() }
        val after = DashboardSerializer.deserialize(macOsEraLayout)!!.pages.sumOf { it.widgets.size }
        assertEquals(expected, after)
    }
}
