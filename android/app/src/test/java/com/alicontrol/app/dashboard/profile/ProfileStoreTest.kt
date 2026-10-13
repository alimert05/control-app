package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.ActionIdMigration
import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.dashboard.DashboardSerializer
import com.alicontrol.app.dashboard.DashboardStore
import com.alicontrol.app.dashboard.DefaultDashboardFactory
import com.alicontrol.app.dashboard.profile.ProfileStore.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loading and saving profiles, the one-time migration of the pre-profile layout into Default,
 * the startup policy, and recovery from damaged storage.
 */
class ProfileStoreTest {

    private val legacy = InMemoryLayoutPersistence()
    private val profiles = InMemoryLayoutPersistence()

    private fun store() = ProfileStore(profiles, DashboardStore(legacy))

    private fun legacyDashboardJson(dashboard: Dashboard = customLegacyDashboard()) = DashboardSerializer.serialize(dashboard)

    /** Stored profile document with exactly one profile's first widget made unreadable (an unknown widget type). */
    private fun documentWithDamagedProfile(collection: ProfileCollection, profileId: String): String {
        val root = JSONObject(ProfileSerializer.serialize(collection))
        val entries = root.getJSONArray("profiles")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("id") != profileId) continue
            entry.getJSONObject("dashboard").getJSONArray("pages").getJSONObject(0)
                .getJSONArray("widgets").getJSONObject(0).put("type", "NOT_A_WIDGET")
        }
        return root.toString()
    }

    // -- fresh install --

    @Test
    fun aFreshInstallGetsTheShippedTemplatesAndWritesNothing() {
        val loaded = store().loadWithSource()

        assertEquals(Source.SHIPPED_DEFAULTS, loaded.source)
        assertEquals(BuiltInProfiles.initialCollection(), loaded.collection)
        assertEquals(ProfileIds.DEFAULT, loaded.collection.activeProfile.id)
        assertEquals(0, profiles.writes)
        assertNull(profiles.stored)
        assertFalse(store().hasStoredProfiles())
    }

    // -- migration of the existing single dashboard --

    @Test
    fun anExistingDashboardBecomesTheDefaultProfile() {
        val custom = customLegacyDashboard()
        legacy.stored = legacyDashboardJson(custom)

        val loaded = store().loadWithSource()

        assertEquals(Source.MIGRATED_FROM_LEGACY_LAYOUT, loaded.source)
        assertEquals(custom, loaded.collection.profile(ProfileIds.DEFAULT)!!.dashboard)
        assertEquals("Default", loaded.collection.profile(ProfileIds.DEFAULT)!!.name)
        assertTrue(loaded.collection.profile(ProfileIds.DEFAULT)!!.builtIn)
    }

    @Test
    fun theCustomLegacyLayoutIsPreservedWidgetForWidget() {
        val custom = customLegacyDashboard()
        legacy.stored = legacyDashboardJson(custom)

        val migrated = store().load().profile(ProfileIds.DEFAULT)!!.dashboard

        assertEquals(custom.pages.size, migrated.pages.size)
        assertEquals("the extra page the user made is still there", "Gaming", migrated.orderedPages.last().name)
        assertEquals(custom.pages.flatMap { it.widgets }, migrated.pages.flatMap { it.widgets })
        assertEquals(custom.orderedPages.map { it.id }, migrated.orderedPages.map { it.id })
    }

    @Test
    fun theShippedDevGameAndMediaTemplatesAreAddedAfterMigration() {
        legacy.stored = legacyDashboardJson()

        val collection = store().load()

        assertEquals(ProfileIds.BUILT_IN, collection.orderedProfiles.map { it.id })
        for (id in listOf(ProfileIds.DEV, ProfileIds.GAME, ProfileIds.MEDIA)) {
            assertEquals(BuiltInProfiles.dashboardFor(id), collection.profile(id)!!.dashboard)
        }
        assertEquals(ProfileIds.DEFAULT, collection.lastSelectedProfileId)
        assertEquals(ProfileIds.DEFAULT, collection.defaultProfileId)
        assertTrue(collection.isValid())
    }

    @Test
    fun migrationIsWrittenImmediatelyAndRunsOnce() {
        legacy.stored = legacyDashboardJson()

        val first = store().loadWithSource()
        assertEquals(1, profiles.writes)
        assertNotNull(profiles.stored)

        val second = store().loadWithSource()
        assertEquals(Source.STORED, second.source)
        assertEquals(first.collection, second.collection)
        assertEquals("a migrated document is not rewritten on every load", 1, profiles.writes)
    }

    @Test
    fun migrationDoesNotRepeatEvenIfTheLegacyValueIsLaterReplaced() {
        legacy.stored = legacyDashboardJson()
        val first = store().load()
        legacy.stored = legacyDashboardJson(DefaultDashboardFactory.create()) // an unrelated change to the old key

        val second = store().load()

        assertEquals(first, second)
        assertEquals(1, profiles.writes)
    }

    @Test
    fun migrationIsRestartSafeAfterEditsToTheMigratedProfiles() {
        legacy.stored = legacyDashboardJson()
        val session = ProfileSession(store())
        session.switchTo(ProfileIds.DEV)
        session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "After Migration").value())

        val restarted = ProfileSession(store())

        assertEquals(ProfileIds.DEV, restarted.activeProfile.id)
        assertEquals("After Migration", restarted.dashboard.orderedPages.last().name)
        assertEquals(customLegacyDashboard(), restarted.collection.profile(ProfileIds.DEFAULT)!!.dashboard)
    }

    @Test
    fun theLegacyValueIsNeverModifiedOrRemoved() {
        val raw = legacyDashboardJson()
        legacy.stored = raw

        store().load()
        ProfileSession(store()).switchTo(ProfileIds.GAME)

        assertEquals(raw, legacy.stored)
        assertEquals(0, legacy.writes)
    }

    // -- the existing Windows action migration still works through the profile migration --

    @Test
    fun aMacOsEraLayoutMigratesItsActionIdsIntoTheDefaultProfile() {
        legacy.stored = MAC_OS_ERA_LAYOUT_JSON

        val loaded = store().loadWithSource()

        assertEquals(Source.MIGRATED_FROM_LEGACY_LAYOUT, loaded.source)
        val widgets = loaded.collection.profile(ProfileIds.DEFAULT)!!.dashboard.page("p")!!.widgets
        assertEquals(listOf("windows_search"), widgets.first { it.id == "a" }.referencedActionIds())
        assertEquals(listOf("open_task_manager"), widgets.first { it.id == "b" }.referencedActionIds())
        assertEquals(listOf("task_view"), widgets.first { it.id == "c" }.referencedActionIds())
        assertEquals(listOf("open_explorer"), widgets.first { it.id == "d" }.referencedActionIds())
        assertEquals(listOf("lock_screen"), widgets.first { it.id == "e" }.referencedActionIds())
        assertEquals("placement and size are untouched", 2, widgets.first { it.id == "b" }.gridWidth)
    }

    @Test
    fun noRetiredActionIdReachesTheStoredProfileDocument() {
        legacy.stored = MAC_OS_ERA_LAYOUT_JSON

        store().load()

        for (retired in ActionIdMigration.LEGACY_MACOS_ACTIONS.keys) {
            assertFalse("$retired was persisted", profiles.stored!!.contains("\"$retired\""))
        }
        for (replacement in ActionIdMigration.LEGACY_MACOS_ACTIONS.values) assertTrue(profiles.stored!!.contains(replacement))
    }

    @Test
    fun aSchemaTwoLayoutThatStillNamesARetiredIdIsNotMigratedIntoAProfile() {
        // Strictness is unchanged: only schema-1 documents get the id rewrite.
        legacy.stored = MAC_OS_ERA_LAYOUT_JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        val loaded = store().loadWithSource()

        assertEquals(Source.SHIPPED_DEFAULTS, loaded.source)
        assertEquals(BuiltInProfiles.initialCollection(), loaded.collection)
    }

    // -- corrupted or unusable legacy data --

    @Test
    fun aCorruptedLegacyLayoutRecoversToTheShippedDefaultsWithoutWritingOrDeleting() {
        val corrupt = "{\"schemaVersion\":2,\"pages\":"
        legacy.stored = corrupt

        val loaded = store().loadWithSource()

        assertEquals(Source.SHIPPED_DEFAULTS, loaded.source)
        assertEquals(BuiltInProfiles.initialCollection(), loaded.collection)
        assertEquals(0, profiles.writes)
        assertEquals("what was not understood is left alone", corrupt, legacy.stored)
    }

    @Test
    fun aStructurallyInvalidLegacyLayoutRecoversToTheShippedDefaults() {
        legacy.stored = """
            {"schemaVersion":2,"pages":[{"id":"p","name":"P","order":0,"widgets":[
              {"id":"a","type":"ACTION","x":0,"y":0,"w":2,"h":2,"config":{"actionId":"lock_screen"}},
              {"id":"b","type":"ACTION","x":0,"y":0,"w":1,"h":1,"config":{"actionId":"screenshot"}}
            ]}]}
        """.trimIndent()

        assertEquals(BuiltInProfiles.initialCollection(), store().load())
    }

    @Test
    fun aLegacyLayoutNamingAnUnknownActionIsNotMigrated() {
        legacy.stored = MAC_OS_ERA_LAYOUT_JSON.replace("lock_screen", "run_shell_command")

        val loaded = store().loadWithSource()

        assertEquals(Source.SHIPPED_DEFAULTS, loaded.source)
        assertEquals(0, profiles.writes)
    }

    @Test
    fun aMissingLegacyStoreAndAMissingProfileDocumentIsJustAFreshInstall() {
        val loaded = ProfileStore(profiles, legacyLayout = null).loadWithSource()

        assertEquals(Source.SHIPPED_DEFAULTS, loaded.source)
    }

    // -- damaged profile documents --

    @Test
    fun aCorruptedProfileDocumentFallsBackWithoutDestroyingItBeforeTheFirstSave() {
        val corrupt = "{\"schemaVersion\":3,\"profiles\":[{"
        profiles.stored = corrupt

        val loaded = store().loadWithSource()

        assertEquals(Source.SHIPPED_DEFAULTS, loaded.source)
        assertEquals(BuiltInProfiles.initialCollection(), loaded.collection)
        assertEquals(0, profiles.writes)
        assertEquals(corrupt, profiles.stored)
    }

    @Test
    fun aCorruptedProfileDocumentRecoversFromAnIntactLegacyLayout() {
        // The legacy value is kept for exactly this: a damaged profile document does not cost the user the
        // layout they had before profiles existed.
        profiles.stored = "garbage"
        legacy.stored = legacyDashboardJson()

        val loaded = store().loadWithSource()

        assertEquals(Source.MIGRATED_FROM_LEGACY_LAYOUT, loaded.source)
        assertEquals(customLegacyDashboard(), loaded.collection.profile(ProfileIds.DEFAULT)!!.dashboard)
        assertEquals(Source.STORED, store().loadWithSource().source)
    }

    @Test
    fun aProfileDocumentFromANewerBuildIsNotReadAsCurrent() {
        profiles.stored = ProfileSerializer.serialize(BuiltInProfiles.initialCollection())
            .replace("\"schemaVersion\":3", "\"schemaVersion\":99")

        assertEquals(Source.SHIPPED_DEFAULTS, store().loadWithSource().source)
    }

    @Test
    fun oneDamagedProfileInTheStoredDocumentDoesNotLoseTheOthers() {
        val collection = BuiltInProfiles.initialCollection()
            .editDashboard(ProfileIds.DEFAULT) { DashboardEditor.createPage(it, "Mine") }
        profiles.stored = documentWithDamagedProfile(collection, ProfileIds.MEDIA)

        val loaded = store().loadWithSource()

        assertEquals(Source.STORED, loaded.source)
        assertEquals(collection.profile(ProfileIds.DEFAULT), loaded.collection.profile(ProfileIds.DEFAULT))
        assertEquals(collection.profile(ProfileIds.DEV), loaded.collection.profile(ProfileIds.DEV))
        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.MEDIA), loaded.collection.profile(ProfileIds.MEDIA)!!.dashboard)
    }

    @Test
    fun loadingNeverWritesEvenWhenItRepairsSomething() {
        profiles.stored = documentWithDamagedProfile(BuiltInProfiles.initialCollection(), ProfileIds.MEDIA)
        val before = profiles.stored

        store().load()

        assertEquals(0, profiles.writes)
        assertEquals(before, profiles.stored)
    }

    // -- save / reload --

    @Test
    fun savedProfilesComeBackExactly() {
        val original = ProfileEditor.setStartupDefault(
            ProfileEditor.select(
                ProfileEditor.createProfile(
                    BuiltInProfiles.initialCollection().editDashboard(ProfileIds.GAME) { DashboardEditor.createPage(it, "Mine") },
                    "Streaming"
                ).value().collection,
                ProfileIds.GAME
            ).value(),
            ProfileIds.MEDIA
        ).value()

        store().save(original)

        assertEquals(original, store().load())
        assertTrue(store().hasStoredProfiles())
        assertEquals(1, profiles.writes)
    }

    // -- startup policy --

    private fun startup(lastSelected: String, default: String, drop: Set<String> = emptySet()): ProfileCollection {
        val base = BuiltInProfiles.initialCollection()
        val collection = base.copy(
            profiles = base.profiles.filterNot { it.id in drop },
            lastSelectedProfileId = lastSelected,
            defaultProfileId = default
        )
        return collection
    }

    @Test
    fun startupPrefersTheLastSelectedProfile() {
        assertEquals(ProfileIds.GAME, startup(ProfileIds.GAME, ProfileIds.MEDIA).resolve(ProfileIds.GAME))
        assertEquals(ProfileIds.GAME, startup(ProfileIds.GAME, ProfileIds.MEDIA).activeProfile.id)
    }

    @Test
    fun startupFallsBackToTheConfiguredDefaultWhenTheLastSelectedIsGone() {
        val collection = startup("custom_deleted", ProfileIds.MEDIA)

        assertEquals(ProfileIds.MEDIA, collection.resolve(collection.lastSelectedProfileId))
        assertEquals(ProfileIds.MEDIA, collection.activeProfile.id)
    }

    @Test
    fun startupFallsBackToTheBuiltInDefaultWhenTheConfiguredDefaultIsAlsoGone() {
        val collection = startup("custom_deleted", "custom_also_deleted")

        assertEquals(ProfileIds.DEFAULT, collection.resolve(collection.lastSelectedProfileId))
        assertEquals(ProfileIds.DEFAULT, collection.activeProfile.id)
    }

    @Test
    fun startupFallsBackToTheFirstValidProfileWhenTheBuiltInDefaultIsMissingToo() {
        val collection = startup("ghost", "phantom", drop = setOf(ProfileIds.DEFAULT))

        assertEquals(ProfileIds.DEV, collection.resolve(collection.lastSelectedProfileId))
        assertEquals(ProfileIds.DEV, collection.activeProfile.id)
    }

    @Test
    fun theFirstValidProfileMeansFirstInDisplayOrderAndSkipsInvalidOnes() {
        val base = startup("ghost", "phantom", drop = setOf(ProfileIds.DEFAULT))
        val brokenDev = base.profile(ProfileIds.DEV)!!.copy(dashboard = Dashboard(emptyList()))
        val collection = base.withProfile(brokenDev)

        assertEquals(ProfileIds.GAME, collection.resolve("ghost"))
    }

    @Test
    fun aLastSelectedProfileWithAnInvalidDashboardIsSkipped() {
        val base = BuiltInProfiles.initialCollection()
        val broken = base.profile(ProfileIds.GAME)!!.copy(dashboard = Dashboard(listOf(DashboardPage("p", "P", 0, emptyList()), DashboardPage("p", "Q", 1, emptyList()))))
        val collection = base.withProfile(broken).copy(lastSelectedProfileId = ProfileIds.GAME, defaultProfileId = ProfileIds.MEDIA)

        assertEquals(ProfileIds.MEDIA, collection.resolve(ProfileIds.GAME))
    }

    @Test
    fun theStartupPolicyIsAppliedThroughTheStoreForStaleStoredIds() {
        profiles.stored = ProfileSerializer.serialize(
            ProfileEditor.setStartupDefault(BuiltInProfiles.initialCollection(), ProfileIds.MEDIA).value()
        ).replace("\"lastSelectedProfileId\":\"default\"", "\"lastSelectedProfileId\":\"custom_deleted\"")

        val collection = store().load()

        assertEquals(ProfileIds.MEDIA, collection.activeProfile.id)
        assertEquals(ProfileIds.MEDIA, collection.lastSelectedProfileId)
    }

    @Test
    fun restartRestoresTheLastSelectedProfileNotTheStartupDefault() {
        val session = ProfileSession(store())
        session.setStartupDefault(ProfileIds.MEDIA)
        session.switchTo(ProfileIds.GAME)

        val restarted = ProfileSession(store())

        assertEquals(ProfileIds.GAME, restarted.activeProfile.id)
        assertEquals(ProfileIds.MEDIA, restarted.collection.defaultProfileId)
    }

    @Test
    fun deletingTheLastSelectedProfileAndRestartingUsesTheStartupDefault() {
        val session = ProfileSession(store())
        val custom = session.createProfile("Temp").value()
        session.setStartupDefault(ProfileIds.MEDIA)
        session.switchTo(custom.id)
        session.deleteProfile(custom.id)

        val restarted = ProfileSession(store())

        assertEquals(ProfileIds.MEDIA, restarted.activeProfile.id)
    }

    // -- the legacy store is unchanged --

    @Test
    fun theLegacyDashboardStoreStillBehavesAsBefore() {
        val legacyStore = DashboardStore(legacy)
        assertNull(legacyStore.loadStored())
        assertEquals(DefaultDashboardFactory.create(), legacyStore.load())

        legacy.stored = MAC_OS_ERA_LAYOUT_JSON
        val stored = legacyStore.loadStored()!!
        assertEquals(0, legacy.writes)
        assertEquals(listOf("windows_search"), stored.page("p")!!.widget("a")!!.referencedActionIds())

        legacyStore.load()
        assertEquals("load() still writes a migrated layout back, as before", 1, legacy.writes)
    }
}
