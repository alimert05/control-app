package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.dashboard.DashboardWidget
import com.alicontrol.app.dashboard.EditRejection
import com.alicontrol.app.dashboard.EditResult
import com.alicontrol.app.dashboard.WidgetConfig
import com.alicontrol.app.dashboard.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile switching, persistence of every operation, and the Edit Mode flow — the behaviour
 * `MainActivity` relies on, driven through a real [ProfileStore] over in-memory storage.
 */
class ProfileSessionTest {

    private val persistence = InMemoryLayoutPersistence()
    private val store = ProfileStore(persistence)

    private var nextId = 0
    private fun session() = ProfileSession(store, newId = { "custom_test_${++nextId}" })

    private fun reload(): ProfileCollection = ProfileStore(persistence).load()

    // -- switching --

    @Test
    fun switchesDefaultToDevToGameToMediaAndBack() {
        val session = session()
        assertEquals(ProfileIds.DEFAULT, session.activeProfile.id)

        for (id in listOf(ProfileIds.DEV, ProfileIds.GAME, ProfileIds.MEDIA, ProfileIds.DEFAULT)) {
            val switched = session.switchTo(id).value()

            assertEquals(id, switched.id)
            assertEquals(id, session.activeProfile.id)
            assertEquals("the dashboard shown is the profile's own", BuiltInProfiles.dashboardFor(id), session.dashboard)
        }
    }

    @Test
    fun theActiveProfileIsPersistedOnEverySwitch() {
        val session = session()

        for (id in listOf(ProfileIds.DEV, ProfileIds.GAME, ProfileIds.MEDIA, ProfileIds.DEFAULT)) {
            session.switchTo(id)
            assertEquals(id, reload().lastSelectedProfileId)
            assertEquals(id, ProfileSession(ProfileStore(persistence)).activeProfile.id)
        }
    }

    @Test
    fun switchingNeverModifiesAnInactiveProfile() {
        val session = session()
        // Give every profile some history first, so "unchanged" means something.
        for (id in ProfileIds.BUILT_IN) {
            session.switchTo(id)
            session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "Mine $id").value())
        }
        session.switchTo(ProfileIds.DEFAULT)
        val before = session.collection.profiles.associateBy { it.id }

        for (id in listOf(ProfileIds.DEV, ProfileIds.GAME, ProfileIds.MEDIA, ProfileIds.DEFAULT, ProfileIds.GAME)) {
            session.switchTo(id)
            for (profile in session.collection.profiles) assertEquals("${profile.id} changed", before[profile.id], profile)
        }
    }

    @Test
    fun switchingToTheActiveProfileChangesAndWritesNothing() {
        val session = session()
        session.switchTo(ProfileIds.DEV)
        val writes = persistence.writes

        val result = session.switchTo(ProfileIds.DEV)

        assertTrue(result.isSuccess)
        assertEquals(writes, persistence.writes)
    }

    @Test
    fun switchingToAnUnknownProfileIsRejectedAndLeavesEverythingAsItWas() {
        val session = session()
        session.switchTo(ProfileIds.GAME)
        val before = session.collection
        val writes = persistence.writes

        assertEquals(EditRejection.UNKNOWN_PROFILE, session.switchTo("ghost").rejection())

        assertEquals(before, session.collection)
        assertEquals(ProfileIds.GAME, session.activeProfile.id)
        assertEquals(writes, persistence.writes)
    }

    // -- editing the active profile --

    @Test
    fun editsAreStoredOnTheActiveProfileOnly() {
        val session = session()
        session.switchTo(ProfileIds.DEV)

        val edited = DashboardEditor.createPage(session.dashboard, "Notes").value()
        session.updateActiveDashboard(edited)

        val reloaded = reload()
        assertEquals(edited, reloaded.profile(ProfileIds.DEV)!!.dashboard)
        for (id in listOf(ProfileIds.DEFAULT, ProfileIds.GAME, ProfileIds.MEDIA)) {
            assertEquals(BuiltInProfiles.dashboardFor(id), reloaded.profile(id)!!.dashboard)
        }
    }

    @Test
    fun eachProfileKeepsItsOwnCustomizationAcrossSwitches() {
        val session = session()
        session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "On Default").value())
        session.switchTo(ProfileIds.DEV)
        session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "On Dev").value())

        session.switchTo(ProfileIds.DEFAULT)
        assertEquals("On Default", session.dashboard.orderedPages.last().name)
        assertTrue(session.dashboard.pages.none { it.name == "On Dev" })

        session.switchTo(ProfileIds.DEV)
        assertEquals("On Dev", session.dashboard.orderedPages.last().name)
        assertTrue(session.dashboard.pages.none { it.name == "On Default" })
    }

    @Test
    fun anInvalidEditIsRefusedAndNothingIsWritten() {
        val session = session()
        val writes = persistence.writes
        val before = session.collection

        val overlapping = Dashboard(
            listOf(
                DashboardPage(
                    "p", "P", 0,
                    listOf(
                        DashboardWidget("a", WidgetType.ACTION, 0, 0, 2, 2, WidgetConfig.Action(AliControlCommands.LOCK_SCREEN)),
                        DashboardWidget("b", WidgetType.ACTION, 0, 0, 1, 1, WidgetConfig.Action(AliControlCommands.SCREENSHOT))
                    )
                )
            )
        )

        assertEquals(EditRejection.INVALID_LAYOUT, session.updateActiveDashboard(overlapping).rejection())
        assertEquals(before, session.collection)
        assertEquals(writes, persistence.writes)
    }

    @Test
    fun theLiveEditingFlowDragsAWidgetThenSwitchesWithoutLosingTheEdit() {
        // What MainActivity does: Edit Mode applies each DashboardEditor result through
        // updateActiveDashboard; switching profile first leaves Edit Mode, then switches.
        val session = session()
        session.switchTo(ProfileIds.DEV)
        val page = session.dashboard.page("page_dev_tools")!!
        val moved = DashboardEditor.moveWidget(page, "tools_all_actions", 2, 3).value()
        session.updateActiveDashboard(session.dashboard.withPage(moved))
        val editedDev = session.dashboard

        session.switchTo(ProfileIds.GAME) // Edit Mode already left; nothing is pending

        assertEquals(editedDev, reload().profile(ProfileIds.DEV)!!.dashboard)
        assertEquals(2, reload().profile(ProfileIds.DEV)!!.dashboard.page("page_dev_tools")!!.widget("tools_all_actions")!!.gridX)
        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.GAME), session.dashboard)
    }

    @Test
    fun theWholeEditorSurfaceReachesTheActiveProfileThroughOnePath() {
        val session = session()
        session.switchTo(ProfileIds.DEV)
        var dashboard = session.dashboard
        val firstPage = dashboard.orderedPages.first().id

        dashboard = DashboardEditor.createPage(dashboard, "New").value()
        dashboard = DashboardEditor.renamePage(dashboard, firstPage, "Renamed").value()
        dashboard = DashboardEditor.movePage(dashboard, firstPage, 1).value()
        dashboard = DashboardEditor.deletePage(dashboard, "page_dev_system").value()
        dashboard = dashboard.withPage(DashboardEditor.removeWidget(dashboard.page(firstPage)!!, "dev_vscode").value())
        dashboard = dashboard.withPage(DashboardEditor.addActionWidget(dashboard.page(firstPage)!!, AliControlCommands.OPEN_SPOTIFY).value())
        session.updateActiveDashboard(dashboard)

        assertEquals(dashboard, reload().profile(ProfileIds.DEV)!!.dashboard)
        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEFAULT), reload().profile(ProfileIds.DEFAULT)!!.dashboard)
    }

    // -- profile management --

    @Test
    fun createdProfilesAreEmptyPersistedAndSwitchable() {
        val session = session()

        val created = session.createProfile("Streaming").value()
        assertEquals("not selected until the caller switches", ProfileIds.DEFAULT, session.activeProfile.id)
        session.switchTo(created.id)
        session.updateActiveDashboard(
            session.dashboard.withPage(DashboardEditor.addActionWidget(session.dashboard.orderedPages.first(), AliControlCommands.OPEN_SPOTIFY).value())
        )

        val restarted = ProfileSession(ProfileStore(persistence))
        assertEquals(created.id, restarted.activeProfile.id)
        assertEquals("Streaming", restarted.activeProfile.name)
        assertEquals(1, restarted.dashboard.orderedPages.first().widgets.size)
    }

    @Test
    fun duplicatingThenEditingTheCopyLeavesTheOriginalUnchangedAfterRestart() {
        val session = session()
        val copy = session.duplicateProfile(ProfileIds.DEV).value()
        session.switchTo(copy.id)
        session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "Copy Only").value())

        val restarted = ProfileSession(ProfileStore(persistence))
        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEV), restarted.collection.profile(ProfileIds.DEV)!!.dashboard)
        assertNotEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEV), restarted.collection.profile(copy.id)!!.dashboard)
        assertEquals("Dev Copy", restarted.collection.profile(copy.id)!!.name)
    }

    @Test
    fun renameKeepsTheIdAndPersists() {
        val session = session()

        session.renameProfile(ProfileIds.GAME, "  Gaming  ")

        val reloaded = reload()
        assertEquals("Gaming", reloaded.profile(ProfileIds.GAME)!!.name)
        assertEquals(ProfileIds.GAME, reloaded.profile(ProfileIds.GAME)!!.id)
    }

    @Test
    fun theProfileOrderPersistsAndIsWhatTheSwitcherUses() {
        val session = session()
        val custom = session.createProfile("Mine").value()

        session.moveProfile(custom.id, -4)
        session.moveProfile(ProfileIds.MEDIA, -1)

        val expected = listOf(custom.id, "default", "dev", "media", "game")
        assertEquals(expected, session.orderedProfiles.map { it.id })
        assertEquals(expected, ProfileSession(ProfileStore(persistence)).orderedProfiles.map { it.id })
    }

    @Test
    fun theStartupDefaultPersists() {
        val session = session()

        session.setStartupDefault(ProfileIds.MEDIA)

        assertEquals(ProfileIds.MEDIA, reload().defaultProfileId)
    }

    @Test
    fun resettingABuiltInProfilePersistsAndAffectsOnlyThatProfile() {
        val session = session()
        session.switchTo(ProfileIds.DEV)
        session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "Scratch").value())
        session.switchTo(ProfileIds.DEFAULT)
        session.updateActiveDashboard(DashboardEditor.createPage(session.dashboard, "Keep").value())
        val keptDefault = session.dashboard

        session.resetProfile(ProfileIds.DEV)

        val reloaded = reload()
        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEV), reloaded.profile(ProfileIds.DEV)!!.dashboard)
        assertEquals(keptDefault, reloaded.profile(ProfileIds.DEFAULT)!!.dashboard)
    }

    @Test
    fun deletingTheActiveCustomProfileSelectsAValidFallbackAndPersistsIt() {
        val session = session()
        val custom = session.createProfile("Temp").value()
        session.switchTo(custom.id)

        session.deleteProfile(custom.id)

        assertEquals(ProfileIds.DEFAULT, session.activeProfile.id)
        assertNull(session.collection.profile(custom.id))
        assertNull(reload().profile(custom.id))
        assertEquals(ProfileIds.DEFAULT, reload().lastSelectedProfileId)
    }

    @Test
    fun builtInProfilesCannotBeDeletedThroughTheSession() {
        val session = session()
        val before = session.collection
        val writes = persistence.writes

        for (id in ProfileIds.BUILT_IN) {
            assertEquals(EditRejection.BUILT_IN_PROTECTED, session.deleteProfile(id).rejection())
        }

        assertEquals(before, session.collection)
        assertEquals(writes, persistence.writes)
    }

    @Test
    fun eachSuccessfulOperationWritesOnceAndARejectedOneNeverWrites() {
        val session = session()
        assertEquals(0, persistence.writes)

        session.switchTo(ProfileIds.DEV)
        assertEquals(1, persistence.writes)
        session.createProfile("Mine")
        assertEquals(2, persistence.writes)
        session.renameProfile(ProfileIds.DEV, "Coding")
        assertEquals(3, persistence.writes)

        session.renameProfile(ProfileIds.DEV, "   ")
        session.deleteProfile(ProfileIds.DEV)
        session.switchTo("ghost")
        assertEquals(3, persistence.writes)
    }

    @Test
    fun theSessionAlwaysRendersAValidDashboard() {
        val session = session()
        for (id in ProfileIds.BUILT_IN) {
            session.switchTo(id)
            assertTrue(session.dashboard.isValid())
            assertTrue(session.collection.isValid())
        }
        val result: EditResult<*> = session.switchTo("ghost")
        assertTrue(result is EditResult.Rejected)
        assertTrue(session.dashboard.isValid())
    }
}
