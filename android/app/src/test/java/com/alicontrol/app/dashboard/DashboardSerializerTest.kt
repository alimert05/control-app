package com.alicontrol.app.dashboard

import com.alicontrol.app.ble.AliControlCommands
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence: a full round trip, and every way a stored layout can be wrong.
 *
 * The negative cases matter more than the positive one — a dashboard file is
 * user-writable state that survives app updates, so "fails safely" has to mean
 * *every* malformed shape returns null rather than a partially-built or
 * invalid dashboard.
 */
class DashboardSerializerTest {

    @Test
    fun defaultDashboardSurvivesARoundTripUnchanged() {
        val original = DefaultDashboardFactory.create()

        val restored = DashboardSerializer.deserialize(DashboardSerializer.serialize(original))

        assertEquals(original, restored)
    }

    @Test
    fun everyWidgetTypeAndConfigurationSurvivesARoundTrip() {
        val page = DashboardPage(
            "page_kitchen_sink", "Everything", 0,
            listOf(
                DashboardWidget("a", WidgetType.ACTION, 0, 0, 1, 1, WidgetConfig.Action(AliControlCommands.LOCK_SCREEN)),
                DashboardWidget(
                    "g", WidgetType.ACTION_GROUP, 1, 0, 2, 1,
                    WidgetConfig.ActionGroup(listOf(AliControlCommands.VOLUME_DOWN, AliControlCommands.VOLUME_UP))
                ),
                DashboardWidget("m", WidgetType.METRIC, 3, 0, 1, 1, WidgetConfig.Metric("CPU")),
                DashboardWidget("gg", WidgetType.GAUGE, 4, 0, 1, 1, WidgetConfig.Gauge("GPU")),
                DashboardWidget("c", WidgetType.CLOCK, 0, 1, 2, 1, WidgetConfig.Clock(showDate = false)),
                DashboardWidget("d", WidgetType.DAILY_OVERVIEW, 0, 2, 3, 2, WidgetConfig.DailyOverview("Edinburgh")),
                DashboardWidget(
                    "med", WidgetType.MEDIA, 3, 1, 3, 2,
                    WidgetConfig.Media(
                        AliControlCommands.PREVIOUS_TRACK,
                        AliControlCommands.PLAY_PAUSE,
                        AliControlCommands.NEXT_TRACK
                    )
                ),
                DashboardWidget("s", WidgetType.SLIDER, 3, 3, 3, 1, WidgetConfig.Slider("Volume")),
                DashboardWidget(
                    "sc", WidgetType.SHORTCUT, 2, 1, 1, 1,
                    WidgetConfig.Shortcut("Device", ShortcutDestination.DEVICE_SETTINGS)
                )
            )
        )
        val original = Dashboard(listOf(page))

        val restored = DashboardSerializer.deserialize(DashboardSerializer.serialize(original))

        assertEquals(original, restored)
    }

    @Test
    fun aUserEditedLayoutSurvivesARoundTrip() {
        val edited = (
            DashboardEditor.createPage(DefaultDashboardFactory.create(), "Gaming") as EditResult.Success
            ).value
        val withWidget = (
            DashboardEditor.addActionWidget(edited.orderedPages.last(), AliControlCommands.OPEN_SPOTIFY)
                as EditResult.Success
            ).value
        val original = edited.withPage(withWidget)

        assertEquals(original, DashboardSerializer.deserialize(DashboardSerializer.serialize(original)))
    }

    @Test
    fun serializedOutputCarriesTheSchemaVersion() {
        val json = JSONObject(DashboardSerializer.serialize(DefaultDashboardFactory.create()))

        assertEquals(DashboardSerializer.SCHEMA_VERSION, json.getInt("schemaVersion"))
    }

    @Test
    fun aFutureSchemaVersionIsRefusedRatherThanGuessedAt() {
        val json = JSONObject(DashboardSerializer.serialize(DefaultDashboardFactory.create()))
            .put("schemaVersion", DashboardSerializer.SCHEMA_VERSION + 1)
            .toString()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aMissingSchemaVersionIsRefused() {
        val json = JSONObject(DashboardSerializer.serialize(DefaultDashboardFactory.create()))
            .apply { remove("schemaVersion") }
            .toString()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun corruptedOrEmptyInputFailsSafely() {
        assertNull(DashboardSerializer.deserialize(null))
        assertNull(DashboardSerializer.deserialize(""))
        assertNull(DashboardSerializer.deserialize("   "))
        assertNull(DashboardSerializer.deserialize("not json at all"))
        assertNull(DashboardSerializer.deserialize("{\"schemaVersion\":1"))
        assertNull(DashboardSerializer.deserialize("[]"))
        assertNull(DashboardSerializer.deserialize("{\"schemaVersion\":1}"))
        assertNull(DashboardSerializer.deserialize("{\"schemaVersion\":1,\"pages\":[]}"))
    }

    @Test
    fun aLayoutWithOverlappingWidgetsIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":0,"y":0,"w":2,"h":2,"config":{"actionId":"lock_screen"}},
              {"id":"b","type":"ACTION","x":1,"y":1,"w":1,"h":1,"config":{"actionId":"screenshot"}}
            ]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aLayoutWithAWidgetOutsideTheGridIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":6,"y":0,"w":1,"h":1,"config":{"actionId":"lock_screen"}}
            ]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aLayoutBreakingAWidgetTypesSizeConstraintsIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":0,"y":0,"w":4,"h":1,"config":{"actionId":"lock_screen"}}
            ]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aLayoutWithDuplicateWidgetIdsIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":0,"y":0,"w":1,"h":1,"config":{"actionId":"lock_screen"}},
              {"id":"a","type":"ACTION","x":1,"y":0,"w":1,"h":1,"config":{"actionId":"screenshot"}}
            ]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aLayoutReferencingAnUnknownActionIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":0,"y":0,"w":1,"h":1,"config":{"actionId":"format_disk"}}
            ]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aLayoutWithAnUnknownWidgetTypeIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"TELEPORTER","x":0,"y":0,"w":1,"h":1,"config":{}}
            ]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aLayoutWithAMissingConfigFieldIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":0,"y":0,"w":1,"h":1,"config":{}}
            ]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun aPageWithABlankNameIsRefused() {
        val json = """
            {"schemaVersion":1,"pages":[{"id":"p","name":"  ","order":0,"widgets":[]}]}
        """.trimIndent()

        assertNull(DashboardSerializer.deserialize(json))
    }

    @Test
    fun anEmptyPageIsValidAndRoundTrips() {
        val original = Dashboard(listOf(DashboardPage("p", "Empty", 0, emptyList())))

        val restored = DashboardSerializer.deserialize(DashboardSerializer.serialize(original))

        assertNotNull(restored)
        assertTrue(restored!!.orderedPages.single().widgets.isEmpty())
    }

    @Test
    fun pageOrderIsRestoredDeterministicallyRegardlessOfStoredOrderValues() {
        val json = """
            {"schemaVersion":1,"pages":[
              {"id":"b","name":"B","order":40,"widgets":[]},
              {"id":"a","name":"A","order":10,"widgets":[]}
            ]}
        """.trimIndent()

        val restored = DashboardSerializer.deserialize(json)!!

        assertEquals(listOf("a", "b"), restored.orderedPages.map { it.id })
        assertEquals(listOf(0, 1), restored.orderedPages.map { it.order })
    }
}
