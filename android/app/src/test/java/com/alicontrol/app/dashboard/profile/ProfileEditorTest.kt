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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Create / duplicate / rename / reorder / default / reset / delete, and the guarantee that
 * customizing one profile (with the existing TASK-008 editor) never changes another.
 */
class ProfileEditorTest {

    private val initial = BuiltInProfiles.initialCollection()

    private fun withCustom(name: String = "Mine"): Pair<ProfileCollection, DashboardProfile> {
        val added = ProfileEditor.createProfile(initial, name).value()
        return added.collection to added.profile
    }

    // -- select --

    @Test
    fun selectingAKnownProfileChangesOnlyTheLastSelectedId() {
        val selected = ProfileEditor.select(initial, ProfileIds.GAME).value()

        assertEquals(ProfileIds.GAME, selected.lastSelectedProfileId)
        assertEquals(ProfileIds.GAME, selected.activeProfile.id)
        assertEquals(initial.copy(lastSelectedProfileId = ProfileIds.GAME), selected)
    }

    @Test
    fun selectingTheActiveProfileIsANoOpSuccess() {
        assertEquals(initial, ProfileEditor.select(initial, ProfileIds.DEFAULT).value())
    }

    @Test
    fun selectingAnUnknownProfileIsRejected() {
        assertEquals(EditRejection.UNKNOWN_PROFILE, ProfileEditor.select(initial, "ghost").rejection())
    }

    // -- create --

    @Test
    fun createsAnEmptyValidProfileAtTheEnd() {
        val (collection, profile) = withCustom("  Streaming  ")

        assertEquals("Streaming", profile.name)
        assertFalse(profile.builtIn)
        assertEquals(5, collection.profiles.size)
        assertEquals(profile.id, collection.orderedProfiles.last().id)
        assertEquals(4, profile.order)
        assertTrue(profile.isValid())
        assertEquals(1, profile.dashboard.pages.size)
        assertTrue(profile.dashboard.pages.single().widgets.isEmpty())
        assertTrue(collection.isValid())
    }

    @Test
    fun creatingDoesNotChangeTheSelectionOrAnyExistingProfile() {
        val (collection, _) = withCustom()

        assertEquals(initial.lastSelectedProfileId, collection.lastSelectedProfileId)
        assertEquals(initial.defaultProfileId, collection.defaultProfileId)
        for (id in ProfileIds.BUILT_IN) assertEquals(initial.profile(id), collection.profile(id))
    }

    @Test
    fun aBlankNameIsRejectedWhenCreating() {
        assertEquals(EditRejection.INVALID_NAME, ProfileEditor.createProfile(initial, "   ").rejection())
        assertEquals(EditRejection.INVALID_NAME, ProfileEditor.createProfile(initial, "").rejection())
    }

    @Test
    fun aGeneratorThatKeepsReturningATakenIdStillYieldsAUniqueId() {
        var collection = initial
        repeat(3) {
            collection = ProfileEditor.createProfile(collection, "Loop", newId = { "custom_same" }).value().collection
        }

        val ids = collection.profiles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(collection.isValid())
    }

    @Test
    fun aGeneratorHandingOutABuiltInIdIsNeverUsedForACustomProfile() {
        val profile = ProfileEditor.createProfile(initial, "Sneaky", newId = { "dev" }).value().profile

        assertNotEquals("dev", profile.id)
        assertFalse(profile.builtIn)
    }

    // -- duplicate --

    @Test
    fun duplicatingProducesAnIndependentCustomCopyWithANewId() {
        val duplicated = ProfileEditor.duplicateProfile(initial, ProfileIds.DEV).value()
        val copy = duplicated.profile
        val source = duplicated.collection.profile(ProfileIds.DEV)!!

        assertEquals("Dev Copy", copy.name)
        assertNotEquals(source.id, copy.id)
        assertFalse("a copy of a built-in profile is a custom profile", copy.builtIn)
        assertEquals("the layout is identical to start with", source.dashboard, copy.dashboard)
        assertEquals(duplicated.collection.orderedProfiles.last().id, copy.id)
        assertTrue(duplicated.collection.isValid())
    }

    @Test
    fun editingTheCopyNeverChangesTheOriginalAndViceVersa() {
        val duplicated = ProfileEditor.duplicateProfile(initial, ProfileIds.DEV).value()
        val copyId = duplicated.profile.id

        val editedCopy = duplicated.collection.editDashboard(copyId) { dashboard ->
            dashboard.editPage("page_dev_main") { DashboardEditor.removeWidget(it, "dev_vscode") }
        }
        assertEquals("original is untouched", initial.profile(ProfileIds.DEV), editedCopy.profile(ProfileIds.DEV))
        assertNotEquals(initial.profile(ProfileIds.DEV)!!.dashboard, editedCopy.profile(copyId)!!.dashboard)

        val editedOriginal = duplicated.collection.editDashboard(ProfileIds.DEV) { dashboard ->
            DashboardEditor.createPage(dashboard, "Only On Dev")
        }
        assertEquals("copy is untouched", duplicated.collection.profile(copyId), editedOriginal.profile(copyId))
    }

    @Test
    fun theCopySharesNoCollectionsWithTheOriginal() {
        // A widget whose config list is a *mutable* list a caller could still hold on to.
        val sharedIds = mutableListOf(AliControlCommands.VOLUME_DOWN, AliControlCommands.VOLUME_UP)
        val group = DashboardWidget("g", WidgetType.ACTION_GROUP, 0, 0, 2, 1, WidgetConfig.ActionGroup(sharedIds))
        val source = initial.copy(
            profiles = initial.profiles.map {
                if (it.id == ProfileIds.DEV) it.copy(dashboard = Dashboard(listOf(DashboardPage("p", "P", 0, listOf(group))))) else it
            }
        )

        val copy = ProfileEditor.duplicateProfile(source, ProfileIds.DEV).value().profile

        val originalPage = source.profile(ProfileIds.DEV)!!.dashboard.pages.single()
        val copiedPage = copy.dashboard.pages.single()
        assertNotSame(originalPage, copiedPage)
        assertNotSame(originalPage.widgets, copiedPage.widgets)
        assertNotSame(originalPage.widgets.single(), copiedPage.widgets.single())

        sharedIds.add(AliControlCommands.MUTE_TOGGLE) // mutate the original's backing list
        val copiedConfig = copiedPage.widgets.single().config as WidgetConfig.ActionGroup
        assertEquals(listOf(AliControlCommands.VOLUME_DOWN, AliControlCommands.VOLUME_UP), copiedConfig.actionIds)
    }

    @Test
    fun deepCopyOfEveryTemplateEqualsItButSharesNoPageOrWidgetList() {
        for (id in ProfileIds.BUILT_IN) {
            val original = BuiltInProfiles.dashboardFor(id)!!
            val copy = ProfileEditor.deepCopy(original)

            assertEquals(id, original, copy)
            assertNotSame(original.pages, copy.pages)
            original.pages.zip(copy.pages).forEach { (a, b) ->
                assertNotSame(a, b)
                assertNotSame(a.widgets, b.widgets)
            }
        }
    }

    @Test
    fun duplicatesAreNumberedWhenTheNameIsTaken() {
        val first = ProfileEditor.duplicateProfile(initial, ProfileIds.DEV).value()
        val second = ProfileEditor.duplicateProfile(first.collection, ProfileIds.DEV).value()
        val third = ProfileEditor.duplicateProfile(second.collection, ProfileIds.DEV).value()

        assertEquals(listOf("Dev Copy", "Dev Copy 2", "Dev Copy 3"), listOf(first, second, third).map { it.profile.name })
    }

    @Test
    fun aDuplicatedNameStaysWithinTheMaximumLength() {
        val long = ProfileEditor.renameProfile(initial, ProfileIds.DEV, "x".repeat(24)).value()

        val copy = ProfileEditor.duplicateProfile(long, ProfileIds.DEV).value().profile

        assertTrue(copy.name.length <= ProfileEditor.MAX_PROFILE_NAME_LENGTH)
        assertTrue(copy.name.endsWith(" Copy"))
    }

    @Test
    fun duplicatingAnUnknownProfileIsRejected() {
        assertEquals(EditRejection.UNKNOWN_PROFILE, ProfileEditor.duplicateProfile(initial, "ghost").rejection())
    }

    // -- rename --

    @Test
    fun renamingTrimsAndKeepsTheId() {
        val renamed = ProfileEditor.renameProfile(initial, ProfileIds.DEV, "  Coding  ").value()

        assertEquals("Coding", renamed.profile(ProfileIds.DEV)!!.name)
        assertEquals(initial.profile(ProfileIds.DEV)!!.dashboard, renamed.profile(ProfileIds.DEV)!!.dashboard)
        assertEquals(initial.profile(ProfileIds.DEV)!!.order, renamed.profile(ProfileIds.DEV)!!.order)
        assertTrue(renamed.profile(ProfileIds.DEV)!!.builtIn)
    }

    @Test
    fun aRenameLongerThanTheLimitIsTruncated() {
        val renamed = ProfileEditor.renameProfile(initial, ProfileIds.DEV, "y".repeat(80)).value()

        assertEquals(ProfileEditor.MAX_PROFILE_NAME_LENGTH, renamed.profile(ProfileIds.DEV)!!.name.length)
        assertEquals(24, ProfileEditor.MAX_PROFILE_NAME_LENGTH)
    }

    @Test
    fun aBlankRenameIsRejectedAndChangesNothing() {
        assertEquals(EditRejection.INVALID_NAME, ProfileEditor.renameProfile(initial, ProfileIds.DEV, "   ").rejection())
        assertEquals(EditRejection.UNKNOWN_PROFILE, ProfileEditor.renameProfile(initial, "ghost", "Name").rejection())
    }

    @Test
    fun renamingDoesNotAffectOtherProfiles() {
        val renamed = ProfileEditor.renameProfile(initial, ProfileIds.GAME, "Gaming").value()

        for (id in listOf(ProfileIds.DEFAULT, ProfileIds.DEV, ProfileIds.MEDIA)) {
            assertEquals(initial.profile(id), renamed.profile(id))
        }
    }

    // -- reorder --

    @Test
    fun movingAProfileLaterAndEarlierRewritesTheOrderDeterministically() {
        val later = ProfileEditor.moveProfile(initial, ProfileIds.DEFAULT, 1).value()
        assertEquals(listOf("dev", "default", "game", "media"), later.orderedProfiles.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), later.orderedProfiles.map { it.order })

        val back = ProfileEditor.moveProfile(later, ProfileIds.DEFAULT, -1).value()
        assertEquals(initial.orderedProfiles, back.orderedProfiles)
    }

    @Test
    fun movingPastTheEndsIsAnUnchangedSuccess() {
        assertEquals(initial, ProfileEditor.moveProfile(initial, ProfileIds.DEFAULT, -1).value())
        assertEquals(initial, ProfileEditor.moveProfile(initial, ProfileIds.MEDIA, 1).value())
        assertEquals(EditRejection.UNKNOWN_PROFILE, ProfileEditor.moveProfile(initial, "ghost", 1).rejection())
    }

    @Test
    fun reorderingKeepsEveryProfileAndTheSelection() {
        val (collection, custom) = withCustom()
        val selected = ProfileEditor.select(collection, ProfileIds.DEV).value()

        val moved = ProfileEditor.moveProfile(selected, custom.id, -3).value()

        assertEquals(custom.id, moved.orderedProfiles[1].id)
        assertEquals(selected.profiles.toSet(), moved.profiles.map { it.copy(order = selected.profile(it.id)!!.order) }.toSet())
        assertEquals(ProfileIds.DEV, moved.lastSelectedProfileId)
    }

    // -- startup default --

    @Test
    fun theStartupDefaultCanBeChangedWithoutTouchingTheSelection() {
        val changed = ProfileEditor.setStartupDefault(initial, ProfileIds.MEDIA).value()

        assertEquals(ProfileIds.MEDIA, changed.defaultProfileId)
        assertEquals(ProfileIds.DEFAULT, changed.lastSelectedProfileId)
        assertEquals(EditRejection.UNKNOWN_PROFILE, ProfileEditor.setStartupDefault(initial, "ghost").rejection())
    }

    @Test
    fun theStartupDefaultIsNotTheSameThingAsTheBuiltInDefaultProfile() {
        val changed = ProfileEditor.setStartupDefault(initial, ProfileIds.GAME).value()

        assertEquals("the built-in profile is still there", "Default", changed.profile(ProfileIds.DEFAULT)!!.name)
        assertEquals(ProfileIds.GAME, changed.defaultProfileId)
        assertNotEquals(changed.defaultProfileId, ProfileIds.DEFAULT)
    }

    // -- delete --

    @Test
    fun deletingACustomProfileRemovesOnlyThatProfile() {
        val (collection, custom) = withCustom()

        val deleted = ProfileEditor.deleteProfile(collection, custom.id).value()

        assertEquals(4, deleted.profiles.size)
        assertEquals(null, deleted.profile(custom.id))
        for (id in ProfileIds.BUILT_IN) assertEquals(initial.profile(id), deleted.profile(id))
    }

    @Test
    fun builtInProfilesCanNeverBeDeleted() {
        for (id in ProfileIds.BUILT_IN) {
            assertEquals(id, EditRejection.BUILT_IN_PROTECTED, ProfileEditor.deleteProfile(initial, id).rejection())
        }
    }

    @Test
    fun deletingAnUnknownProfileIsRejected() {
        assertEquals(EditRejection.UNKNOWN_PROFILE, ProfileEditor.deleteProfile(initial, "ghost").rejection())
    }

    @Test
    fun theFinalAvailableProfileCannotBeDeleted() {
        val (collection, custom) = withCustom()
        val onlyOne = collection.copy(
            profiles = listOf(custom.copy(order = 0)),
            lastSelectedProfileId = custom.id,
            defaultProfileId = custom.id
        )

        assertEquals(EditRejection.LAST_PROFILE, ProfileEditor.deleteProfile(onlyOne, custom.id).rejection())
    }

    @Test
    fun deletingTheActiveCustomProfileSelectsTheConfiguredStartupDefault() {
        val (collection, custom) = withCustom()
        val ready = ProfileEditor.setStartupDefault(
            ProfileEditor.select(collection, custom.id).value(), ProfileIds.MEDIA
        ).value()

        val deleted = ProfileEditor.deleteProfile(ready, custom.id).value()

        assertEquals(ProfileIds.MEDIA, deleted.lastSelectedProfileId)
        assertEquals(ProfileIds.MEDIA, deleted.activeProfile.id)
    }

    @Test
    fun deletingTheActiveCustomProfileFallsBackToTheBuiltInDefaultWhenThereIsNoOtherDefault() {
        val (collection, custom) = withCustom()
        val active = ProfileEditor.select(collection, custom.id).value()

        val deleted = ProfileEditor.deleteProfile(active, custom.id).value()

        assertEquals(ProfileIds.DEFAULT, deleted.activeProfile.id)
        assertTrue(deleted.isValid())
    }

    @Test
    fun deletingTheCustomProfileThatWasTheStartupDefaultRestoresTheBuiltInDefault() {
        val (collection, custom) = withCustom()
        val ready = ProfileEditor.setStartupDefault(collection, custom.id).value()

        val deleted = ProfileEditor.deleteProfile(ready, custom.id).value()

        assertEquals(ProfileIds.DEFAULT, deleted.defaultProfileId)
        assertTrue(deleted.isValid())
    }

    @Test
    fun deletingAnInactiveProfileLeavesTheSelectionAlone() {
        val (collection, custom) = withCustom()
        val active = ProfileEditor.select(collection, ProfileIds.DEV).value()

        val deleted = ProfileEditor.deleteProfile(active, custom.id).value()

        assertEquals(ProfileIds.DEV, deleted.lastSelectedProfileId)
    }

    // -- reset --

    @Test
    fun resettingABuiltInProfileRestoresItsTemplateAndNothingElse() {
        val customized = initial.editDashboard(ProfileIds.DEV) { DashboardEditor.createPage(it, "Scratch") }
            .let { ProfileEditor.renameProfile(it, ProfileIds.DEV, "My Dev").value() }
        assertNotEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEV), customized.profile(ProfileIds.DEV)!!.dashboard)

        val reset = ProfileEditor.resetProfile(customized, ProfileIds.DEV).value()

        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEV), reset.profile(ProfileIds.DEV)!!.dashboard)
        assertEquals("only the layout is reset, not the name", "My Dev", reset.profile(ProfileIds.DEV)!!.name)
        for (id in listOf(ProfileIds.DEFAULT, ProfileIds.GAME, ProfileIds.MEDIA)) {
            assertEquals("$id changed", customized.profile(id), reset.profile(id))
        }
        assertEquals(customized.lastSelectedProfileId, reset.lastSelectedProfileId)
        assertEquals(customized.defaultProfileId, reset.defaultProfileId)
    }

    @Test
    fun resettingDefaultLeavesTheOtherProfilesCustomizationsAlone() {
        val customDev = initial.editDashboard(ProfileIds.DEV) { DashboardEditor.createPage(it, "Mine") }
        val customDefault = customDev.editDashboard(ProfileIds.DEFAULT) { DashboardEditor.createPage(it, "Extra") }

        val reset = ProfileEditor.resetProfile(customDefault, ProfileIds.DEFAULT).value()

        assertEquals(BuiltInProfiles.dashboardFor(ProfileIds.DEFAULT), reset.profile(ProfileIds.DEFAULT)!!.dashboard)
        assertEquals(customDev.profile(ProfileIds.DEV), reset.profile(ProfileIds.DEV))
    }

    @Test
    fun resettingACustomProfileRestoresAnEmptyValidDashboard() {
        val (collection, custom) = withCustom()
        val filled = collection.editDashboard(custom.id) { dashboard ->
            dashboard.editPage("page_custom_1") { DashboardEditor.addActionWidget(it, AliControlCommands.LOCK_SCREEN) }
        }
        assertTrue(filled.profile(custom.id)!!.dashboard.pages.single().widgets.isNotEmpty())

        val reset = ProfileEditor.resetProfile(filled, custom.id).value()

        assertEquals(ProfileEditor.emptyDashboard(), reset.profile(custom.id)!!.dashboard)
        assertTrue(reset.profile(custom.id)!!.isValid())
    }

    @Test
    fun resettingAnUnknownProfileIsRejected() {
        assertEquals(EditRejection.UNKNOWN_PROFILE, ProfileEditor.resetProfile(initial, "ghost").rejection())
    }

    // -- replacing a dashboard --

    @Test
    fun anInvalidDashboardIsRefusedAndNeverStored() {
        val overlapping = Dashboard(
            listOf(
                DashboardPage(
                    "p", "P", 0,
                    listOf(
                        DashboardWidget("a", WidgetType.ACTION, 0, 0, 2, 2, WidgetConfig.Action(AliControlCommands.LOCK_SCREEN)),
                        DashboardWidget("b", WidgetType.ACTION, 1, 1, 1, 1, WidgetConfig.Action(AliControlCommands.SCREENSHOT))
                    )
                )
            )
        )

        assertEquals(
            EditRejection.INVALID_LAYOUT,
            ProfileEditor.replaceDashboard(initial, ProfileIds.DEV, overlapping).rejection()
        )
        assertEquals(
            EditRejection.INVALID_LAYOUT,
            ProfileEditor.replaceDashboard(initial, ProfileIds.DEV, Dashboard(emptyList())).rejection()
        )
        assertEquals(
            EditRejection.UNKNOWN_PROFILE,
            ProfileEditor.replaceDashboard(initial, "ghost", BuiltInProfiles.dashboardFor(ProfileIds.DEV)!!).rejection()
        )
    }

    @Test
    fun anUnchangedCollectionIsReturnedAsTheSameInstanceWhenNothingChanges() {
        assertSame(initial, ProfileEditor.select(initial, ProfileIds.DEFAULT).value())
        assertSame(initial, ProfileEditor.moveProfile(initial, ProfileIds.DEFAULT, -1).value())
    }

    // -- independent customization (the existing TASK-008 operations, per profile) --

    private fun othersUnchanged(before: ProfileCollection, after: ProfileCollection, editedId: String) {
        for (profile in before.profiles.filter { it.id != editedId }) {
            assertEquals("${profile.id} must not change when $editedId is edited", profile, after.profile(profile.id))
        }
        assertNotEquals("$editedId should have changed", before.profile(editedId), after.profile(editedId))
        assertTrue(after.profile(editedId)!!.dashboard.isValid())
    }

    /** Frees room first (removing a widget is always legal), then reports a legal move / resize found by scanning. */
    private fun freeingEdit(collection: ProfileCollection, id: String): ProfileCollection {
        val page = collection.profile(id)!!.dashboard.orderedPages.first()
        return collection.editDashboard(id) { it.editPage(page.id) { p -> DashboardEditor.removeWidget(p, page.widgets.first().id) } }
    }

    @Test
    fun removingAWidgetInOneProfileDoesNotChangeTheOthers() {
        for (id in ProfileIds.BUILT_IN) {
            val after = freeingEdit(initial, id)
            othersUnchanged(initial, after, id)
        }
    }

    @Test
    fun addingAWidgetInOneProfileDoesNotChangeTheOthers() {
        for (id in ProfileIds.BUILT_IN) {
            val freed = freeingEdit(initial, id)
            val pageId = freed.profile(id)!!.dashboard.orderedPages.first().id
            val after = freed.editDashboard(id) { it.editPage(pageId) { p -> DashboardEditor.addActionWidget(p, AliControlCommands.OPEN_SPOTIFY) } }

            othersUnchanged(freed, after, id)
            assertTrue(after.profile(id)!!.dashboard.page(pageId)!!.widgets.any { it.referencedActionIds() == listOf(AliControlCommands.OPEN_SPOTIFY) })
        }
    }

    @Test
    fun movingAWidgetInOneProfileDoesNotChangeTheOthers() {
        for (id in ProfileIds.BUILT_IN) {
            val freed = freeingEdit(initial, id)
            val page = freed.profile(id)!!.dashboard.orderedPages.first()
            val (widgetId, x, y) = firstLegalMove(page) ?: throw AssertionError("$id has no legal move after freeing a widget")

            val after = freed.editDashboard(id) { it.editPage(page.id) { p -> DashboardEditor.moveWidget(p, widgetId, x, y) } }

            othersUnchanged(freed, after, id)
            val moved = after.profile(id)!!.dashboard.page(page.id)!!.widget(widgetId)!!
            assertEquals(x to y, moved.gridX to moved.gridY)
        }
    }

    @Test
    fun resizingAWidgetInOneProfileDoesNotChangeTheOthers() {
        for (id in ProfileIds.BUILT_IN) {
            val freed = freeingEdit(initial, id)
            val page = freed.profile(id)!!.dashboard.orderedPages.first()
            val (widgetId, w, h) = firstLegalResize(page) ?: throw AssertionError("$id has no legal resize after freeing a widget")

            val after = freed.editDashboard(id) { it.editPage(page.id) { p -> DashboardEditor.resizeWidget(p, widgetId, w, h) } }

            othersUnchanged(freed, after, id)
            val resized = after.profile(id)!!.dashboard.page(page.id)!!.widget(widgetId)!!
            assertEquals(w to h, resized.gridWidth to resized.gridHeight)
        }
    }

    @Test
    fun pageCreateRenameReorderAndDeleteInOneProfileDoNotChangeTheOthers() {
        for (id in ProfileIds.BUILT_IN) {
            val firstPage = initial.profile(id)!!.dashboard.orderedPages.first().id

            val created = initial.editDashboard(id) { DashboardEditor.createPage(it, "Fresh") }
            othersUnchanged(initial, created, id)
            assertEquals("Fresh", created.profile(id)!!.dashboard.orderedPages.last().name)

            val renamed = created.editDashboard(id) { DashboardEditor.renamePage(it, firstPage, "Renamed") }
            othersUnchanged(created, renamed, id)
            assertEquals("Renamed", renamed.profile(id)!!.dashboard.page(firstPage)!!.name)

            val reordered = renamed.editDashboard(id) { DashboardEditor.movePage(it, firstPage, 1) }
            othersUnchanged(renamed, reordered, id)
            assertEquals(1, reordered.profile(id)!!.dashboard.indexOfPage(firstPage))

            val deleted = reordered.editDashboard(id) { DashboardEditor.deletePage(it, firstPage) }
            othersUnchanged(reordered, deleted, id)
            assertEquals(null, deleted.profile(id)!!.dashboard.page(firstPage))
        }
    }

    @Test
    fun theSameEditAppliedToTwoProfilesLeavesThemIndependentlyEditable() {
        val dev = initial.editDashboard(ProfileIds.DEV) { DashboardEditor.createPage(it, "Shared Name") }
        val both = dev.editDashboard(ProfileIds.GAME) { DashboardEditor.createPage(it, "Shared Name") }
            .editDashboard(ProfileIds.DEV) { DashboardEditor.renamePage(it, "page_custom_1", "Dev Only") }

        assertEquals("Dev Only", both.profile(ProfileIds.DEV)!!.dashboard.page("page_custom_1")!!.name)
        assertEquals("Shared Name", both.profile(ProfileIds.GAME)!!.dashboard.page("page_custom_1")!!.name)
    }

    private fun firstLegalMove(page: DashboardPage): Triple<String, Int, Int>? {
        for (widget in page.widgets) for (y in 0..3) for (x in 0..5) {
            if ((x != widget.gridX || y != widget.gridY) && DashboardEditor.moveWidget(page, widget.id, x, y) is EditResult.Success) {
                return Triple(widget.id, x, y)
            }
        }
        return null
    }

    private fun firstLegalResize(page: DashboardPage): Triple<String, Int, Int>? {
        for (widget in page.widgets) for (h in widget.type.minHeight..widget.type.maxHeight) for (w in widget.type.minWidth..widget.type.maxWidth) {
            if ((w != widget.gridWidth || h != widget.gridHeight) && DashboardEditor.resizeWidget(page, widget.id, w, h) is EditResult.Success) {
                return Triple(widget.id, w, h)
            }
        }
        return null
    }
}
