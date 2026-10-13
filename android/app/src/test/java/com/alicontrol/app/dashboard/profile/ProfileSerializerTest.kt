package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.DashboardSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile document: round trips, versioning, and — above all — that damage to one profile
 * never costs the user another one.
 */
class ProfileSerializerTest {

    private val initial = BuiltInProfiles.initialCollection()

    /** A realistic stored state: customised Default and Dev, one custom profile, non-default selection. */
    private fun richCollection(): ProfileCollection {
        var collection = initial
            .editDashboard(ProfileIds.DEFAULT) { DashboardEditor.createPage(it, "Extra") }
            .editDashboard(ProfileIds.DEV) { DashboardEditor.createPage(it, "Notes") }
        collection = ProfileEditor.createProfile(collection, "Streaming").value().collection
        collection = ProfileEditor.moveProfile(collection, ProfileIds.MEDIA, -2).value()
        collection = ProfileEditor.setStartupDefault(collection, ProfileIds.GAME).value()
        return ProfileEditor.select(collection, ProfileIds.DEV).value()
    }

    private fun mutate(collection: ProfileCollection, edit: (JSONObject) -> Unit): String {
        val root = JSONObject(ProfileSerializer.serialize(collection))
        edit(root)
        return root.toString()
    }

    private fun JSONObject.entries(): List<JSONObject> =
        getJSONArray("profiles").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

    private fun JSONObject.entry(id: String): JSONObject = entries().first { it.getString("id") == id }

    private fun JSONObject.setEntries(entries: List<JSONObject>) {
        put("profiles", JSONArray(entries))
    }

    // -- round trip and format --

    @Test
    fun aCollectionRoundTripsExactly() {
        val original = richCollection()

        val restored = ProfileSerializer.deserialize(ProfileSerializer.serialize(original))

        assertEquals(original, restored)
    }

    @Test
    fun theInitialCollectionRoundTripsExactly() {
        assertEquals(initial, ProfileSerializer.deserialize(ProfileSerializer.serialize(initial)))
    }

    @Test
    fun theDocumentCarriesSchemaVersionThreeAndEverythingThatMustPersist() {
        val json = JSONObject(ProfileSerializer.serialize(richCollection()))

        assertEquals(3, json.getInt("schemaVersion"))
        assertEquals(3, ProfileSerializer.SCHEMA_VERSION)
        assertEquals("dev", json.getString("lastSelectedProfileId"))
        assertEquals("game", json.getString("defaultProfileId"))
        val first = json.getJSONArray("profiles").getJSONObject(0)
        for (key in listOf("id", "name", "builtIn", "order", "dashboard")) assertTrue(key, first.has(key))
        assertTrue(json.entry("default").getBoolean("builtIn"))
        assertFalse(json.entries().last().getBoolean("builtIn"))
    }

    @Test
    fun theSchemaFollowsTheRepositorysHistoryAfterTheDashboardSchema() {
        assertTrue(ProfileSerializer.SCHEMA_VERSION > DashboardSerializer.SCHEMA_VERSION)
        assertEquals(DashboardSerializer.SCHEMA_VERSION, 2)
    }

    @Test
    fun eachProfilesDashboardIsTheExistingDashboardFormat() {
        val json = JSONObject(ProfileSerializer.serialize(initial))
        val embedded = json.entry("dev").getJSONObject("dashboard")

        assertEquals(initial.profile(ProfileIds.DEV)!!.dashboard, DashboardSerializer.deserialize(embedded.toString()))
    }

    @Test
    fun profilesAreWrittenInDisplayOrder() {
        val reordered = ProfileEditor.moveProfile(initial, ProfileIds.MEDIA, -3).value()

        val ids = JSONObject(ProfileSerializer.serialize(reordered)).entries().map { it.getString("id") }

        assertEquals(listOf("media", "default", "dev", "game"), ids)
    }

    @Test
    fun serializingTheRestoredDocumentIsStable() {
        val once = ProfileSerializer.serialize(richCollection())
        val twice = ProfileSerializer.serialize(ProfileSerializer.deserialize(once)!!)

        assertEquals(once, twice)
    }

    // -- unreadable documents --

    @Test
    fun anUnreadableDocumentReturnsNull() {
        assertNull(ProfileSerializer.deserialize(null))
        assertNull(ProfileSerializer.deserialize(""))
        assertNull(ProfileSerializer.deserialize("   "))
        assertNull(ProfileSerializer.deserialize("not json at all"))
        assertNull(ProfileSerializer.deserialize("{\"schemaVersion\":3,\"profiles\":"))
        assertNull(ProfileSerializer.deserialize("[]"))
        assertNull(ProfileSerializer.deserialize("{}"))
    }

    @Test
    fun aDocumentWithoutAProfilesArrayReturnsNull() {
        assertNull(ProfileSerializer.deserialize("{\"schemaVersion\":3}"))
        assertNull(ProfileSerializer.deserialize("{\"schemaVersion\":3,\"profiles\":\"oops\"}"))
        assertNull(ProfileSerializer.deserialize("{\"schemaVersion\":3,\"profiles\":{}}"))
    }

    @Test
    fun aSingleDashboardDocumentIsNotAProfileDocument() {
        // The pre-profile documents (schema 1 and 2) live under a different key and are migrated by ProfileStore.
        assertNull(ProfileSerializer.deserialize(DashboardSerializer.serialize(initial.profile(ProfileIds.DEFAULT)!!.dashboard)))
        assertNull(ProfileSerializer.deserialize(MAC_OS_ERA_LAYOUT_JSON))
    }

    @Test
    fun aDocumentFromANewerBuildIsRefusedRatherThanGuessedAt() {
        val newer = mutate(initial) { it.put("schemaVersion", ProfileSerializer.SCHEMA_VERSION + 1) }
        val older = mutate(initial) { it.put("schemaVersion", 2) }
        val missing = mutate(initial) { it.remove("schemaVersion") }

        assertNull(ProfileSerializer.deserialize(newer))
        assertNull(ProfileSerializer.deserialize(older))
        assertNull(ProfileSerializer.deserialize(missing))
    }

    @Test
    fun unknownExtraKeysAreIgnoredSoLaterBuildsCanAddFields() {
        val original = richCollection()
        val json = mutate(original) { root ->
            root.put("futureSetting", true)
            root.entries().forEach { it.put("themeId", "midnight") }
        }

        assertEquals(original, ProfileSerializer.deserialize(json))
    }

    // -- per-profile recovery --

    @Test
    fun anEmptyProfilesArrayRecoversTheShippedTemplates() {
        val restored = ProfileSerializer.deserialize(mutate(initial) { it.setEntries(emptyList()) })

        assertEquals(initial, restored)
    }

    @Test
    fun duplicateProfileIdsKeepTheFirstEntry() {
        val json = mutate(initial) { root ->
            val dupe = JSONObject(root.entry("dev").toString()).put("name", "Impostor").put("order", 9)
            root.setEntries(root.entries() + dupe)
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertEquals(4, restored.profiles.size)
        assertEquals("Dev", restored.profile(ProfileIds.DEV)!!.name)
    }

    @Test
    fun aProfileWithAnUnknownIdSurvivesAsACustomProfile() {
        val (withCustom, custom) = ProfileEditor.createProfile(initial, "Mine").value().let { it.collection to it.profile }
        val json = mutate(withCustom) { root ->
            root.entry(custom.id).put("id", "someone_elses_id")
        }

        val restored = ProfileSerializer.deserialize(json)!!

        val survivor = restored.profile("someone_elses_id")!!
        assertEquals("Mine", survivor.name)
        assertFalse(survivor.builtIn)
        assertTrue(restored.isValid())
    }

    @Test
    fun theStoredBuiltInFlagIsAdvisoryAndRecomputedFromTheId() {
        val (withCustom, custom) = ProfileEditor.createProfile(initial, "Mine").value().let { it.collection to it.profile }
        val json = mutate(withCustom) { root ->
            root.entry(custom.id).put("builtIn", true)   // a custom profile claiming protection
            root.entry("dev").put("builtIn", false)      // a built-in claiming it is deletable
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertFalse(restored.profile(custom.id)!!.builtIn)
        assertTrue(restored.profile(ProfileIds.DEV)!!.builtIn)
    }

    @Test
    fun aMissingDashboardDropsACustomProfileAndRestoresABuiltInFromItsTemplate() {
        val (withCustom, custom) = ProfileEditor.createProfile(initial, "Mine").value().let { it.collection to it.profile }
        val customised = withCustom.editDashboard(ProfileIds.DEV) { DashboardEditor.createPage(it, "Mine") }
        val json = mutate(customised) { root ->
            root.entry(custom.id).remove("dashboard")
            root.entry("dev").remove("dashboard")
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertNull("the custom profile cannot be trusted without its layout", restored.profile(custom.id))
        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEV), restored.profile(ProfileIds.DEV)!!.dashboard)
        assertEquals("Dev", restored.profile(ProfileIds.DEV)!!.name)
        for (id in listOf(ProfileIds.DEFAULT, ProfileIds.GAME, ProfileIds.MEDIA)) {
            assertEquals(customised.profile(id), restored.profile(id))
        }
    }

    @Test
    fun anInvalidPlacementInOneProfileDoesNotCostTheOthers() {
        val custom = richCollection()
        val customId = custom.orderedProfiles.last().id
        val customised = custom.editDashboard(customId) { DashboardEditor.createPage(it, "Keep Me") }
        val json = mutate(customised) { root ->
            // Push one Game widget off the 6x4 grid.
            val widget = root.entry("game").getJSONObject("dashboard")
                .getJSONArray("pages").getJSONObject(0).getJSONArray("widgets").getJSONObject(0)
            widget.put("x", 5).put("w", 3)
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertEquals("the damaged built-in comes back from its template", BuiltInProfiles.dashboardFor(ProfileIds.GAME), restored.profile(ProfileIds.GAME)!!.dashboard)
        assertEquals(customised.profile(customId), restored.profile(customId))
        assertEquals(customised.profile(ProfileIds.DEV), restored.profile(ProfileIds.DEV))
        assertEquals(customised.profile(ProfileIds.DEFAULT), restored.profile(ProfileIds.DEFAULT))
    }

    @Test
    fun overlappingWidgetsInACustomProfileDropOnlyThatProfile() {
        val (withTwo, ids) = ProfileEditor.createProfile(initial, "One").value().let { first ->
            val second = ProfileEditor.createProfile(first.collection, "Two").value()
            second.collection to (first.profile.id to second.profile.id)
        }
        val withWidget = withTwo.editDashboard(ids.first) { d ->
            d.editPage("page_custom_1") { DashboardEditor.addActionWidget(it, "lock_screen") }
        }.editDashboard(ids.first) { d ->
            d.editPage("page_custom_1") { DashboardEditor.addActionWidget(it, "screenshot") }
        }
        val json = mutate(withWidget) { root ->
            val widgets = root.entry(ids.first).getJSONObject("dashboard")
                .getJSONArray("pages").getJSONObject(0).getJSONArray("widgets")
            widgets.getJSONObject(1).put("x", widgets.getJSONObject(0).getInt("x"))
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertNull(restored.profile(ids.first))
        // Dropping "One" renumbers the display order; everything else about "Two" is exactly as stored.
        assertEquals(withWidget.profile(ids.second)!!.copy(order = 4), restored.profile(ids.second))
        assertEquals(4 + 1, restored.profiles.size)
    }

    @Test
    fun anUnknownActionIdIsRejectedAndNeverSmuggledIn() {
        val json = mutate(initial) { root ->
            val widgets = root.entry("dev").getJSONObject("dashboard")
                .getJSONArray("pages").getJSONObject(0).getJSONArray("widgets")
            widgets.getJSONObject(0).getJSONObject("config").put("actionId", "powershell -Command rm -rf")
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEV), restored.profile(ProfileIds.DEV)!!.dashboard)
        assertFalse(ProfileSerializer.serialize(restored).contains("powershell"))
    }

    @Test
    fun anUnknownWidgetTypeIsRejected() {
        val json = mutate(initial) { root ->
            val widgets = root.entry("media").getJSONObject("dashboard")
                .getJSONArray("pages").getJSONObject(0).getJSONArray("widgets")
            widgets.getJSONObject(0).put("type", "RUN_SCRIPT")
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.MEDIA), restored.profile(ProfileIds.MEDIA)!!.dashboard)
    }

    @Test
    fun entriesThatCannotBeSalvagedAreDroppedWithoutAffectingTheRest() {
        val json = mutate(initial) { root ->
            val garbage = listOf(JSONObject(), JSONObject().put("id", 7), JSONObject().put("id", "  "), JSONObject().put("name", "No Id"))
            root.setEntries(root.entries() + garbage)
        }
        val withNonObject = JSONObject(json).also { it.getJSONArray("profiles").put("just a string") }.toString()

        assertEquals(initial, ProfileSerializer.deserialize(json))
        assertEquals(initial, ProfileSerializer.deserialize(withNonObject))
    }

    @Test
    fun aBlankOrMissingNameFallsBackSensibly() {
        val (withCustom, custom) = ProfileEditor.createProfile(initial, "Mine").value().let { it.collection to it.profile }
        val json = mutate(withCustom) { root ->
            root.entry("dev").put("name", "   ")
            root.entry("game").remove("name")
            root.entry(custom.id).put("name", "")
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertEquals("Dev", restored.profile(ProfileIds.DEV)!!.name)
        assertEquals("Game", restored.profile(ProfileIds.GAME)!!.name)
        assertEquals("Profile", restored.profile(custom.id)!!.name)
    }

    @Test
    fun aMissingBuiltInProfileIsAddedFromItsTemplate() {
        val customised = initial.editDashboard(ProfileIds.DEFAULT) { DashboardEditor.createPage(it, "Mine") }
        val json = mutate(customised) { root ->
            root.setEntries(root.entries().filter { it.getString("id") == "default" })
        }

        val restored = ProfileSerializer.deserialize(json)!!

        assertEquals(ProfileIds.BUILT_IN.toSet(), restored.profiles.map { it.id }.toSet())
        assertEquals(customised.profile(ProfileIds.DEFAULT), restored.profile(ProfileIds.DEFAULT))
        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.MEDIA), restored.profile(ProfileIds.MEDIA)!!.dashboard)
        assertEquals(listOf(0, 1, 2, 3), restored.orderedProfiles.map { it.order })
    }

    @Test
    fun anUnknownOrMissingSelectionResolvesThroughTheStartupPolicy() {
        val withDefault = ProfileEditor.setStartupDefault(initial, ProfileIds.MEDIA).value()

        val unknownLast = ProfileSerializer.deserialize(mutate(withDefault) { it.put("lastSelectedProfileId", "ghost") })!!
        val missingLast = ProfileSerializer.deserialize(mutate(withDefault) { it.remove("lastSelectedProfileId") })!!
        val bothBad = ProfileSerializer.deserialize(
            mutate(withDefault) { it.put("lastSelectedProfileId", "ghost").put("defaultProfileId", "phantom") }
        )!!

        assertEquals(ProfileIds.MEDIA, unknownLast.lastSelectedProfileId)
        assertEquals(ProfileIds.MEDIA, missingLast.lastSelectedProfileId)
        assertEquals(ProfileIds.DEFAULT, bothBad.lastSelectedProfileId)
        assertEquals(ProfileIds.DEFAULT, bothBad.defaultProfileId)
        assertTrue(listOf(unknownLast, missingLast, bothBad).all { it.isValid() })
    }

    @Test
    fun anEmbeddedSchemaOneDashboardStillGetsItsRetiredActionIdsMigrated() {
        // Action-ID compatibility survives the move into profiles: the embedded dashboard goes through the
        // same strict reader, which migrates a schema-1 document's retired ids before validating.
        val json = mutate(initial) { root ->
            root.entry("default").put("dashboard", JSONObject(MAC_OS_ERA_LAYOUT_JSON))
        }

        val restored = ProfileSerializer.deserialize(json)!!

        val widgets = restored.profile(ProfileIds.DEFAULT)!!.dashboard.page("p")!!.widgets
        assertEquals(listOf("windows_search"), widgets.first { it.id == "a" }.referencedActionIds())
        assertEquals(listOf("open_task_manager"), widgets.first { it.id == "b" }.referencedActionIds())
        assertEquals(listOf("task_view"), widgets.first { it.id == "c" }.referencedActionIds())
        assertEquals(listOf("open_explorer"), widgets.first { it.id == "d" }.referencedActionIds())
        assertNotNull(restored.profile(ProfileIds.DEV))
    }

    @Test
    fun theStoredLastSelectedProfileComesBack() {
        val restored = ProfileSerializer.deserialize(ProfileSerializer.serialize(richCollection()))!!

        assertEquals(ProfileIds.DEV, restored.lastSelectedProfileId)
        assertEquals(ProfileIds.DEV, restored.activeProfile.id)
        assertEquals(ProfileIds.GAME, restored.defaultProfileId)
    }
}
