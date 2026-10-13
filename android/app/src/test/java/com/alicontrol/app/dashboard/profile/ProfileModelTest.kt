package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.dashboard.Dashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile model: stable ids, names independent of ids, ordering, classification and ownership. */
class ProfileModelTest {

    private val initial = BuiltInProfiles.initialCollection()

    // -- identity --

    @Test
    fun builtInProfileIdsAreTheFourStableOnesAndAreUnique() {
        assertEquals(listOf("default", "dev", "game", "media"), ProfileIds.BUILT_IN)
        assertEquals(ProfileIds.BUILT_IN.size, ProfileIds.BUILT_IN.toSet().size)
        assertEquals(ProfileIds.BUILT_IN, initial.orderedProfiles.map { it.id })
    }

    @Test
    fun displayNamesAreSeparateFromIds() {
        assertEquals("Default", initial.profile(ProfileIds.DEFAULT)!!.name)
        assertEquals("Dev", initial.profile(ProfileIds.DEV)!!.name)
        assertEquals("Game", initial.profile(ProfileIds.GAME)!!.name)
        assertEquals("Media", initial.profile(ProfileIds.MEDIA)!!.name)

        val renamed = ProfileEditor.renameProfile(initial, ProfileIds.DEV, "Development").value()
        val dev = renamed.profile(ProfileIds.DEV)!!
        assertEquals("Development", dev.name)
        assertEquals("the id never follows the name", "dev", dev.id)
    }

    @Test
    fun customProfileIdsAreGeneratedAndNotDerivedFromTheName() {
        val first = ProfileEditor.createProfile(initial, "Streaming").value()
        val second = ProfileEditor.createProfile(first.collection, "Streaming").value()

        assertTrue(first.profile.id.startsWith("custom_"))
        assertFalse(first.profile.id.contains("streaming", ignoreCase = true))
        assertNotEquals("two profiles may share a name but never an id", first.profile.id, second.profile.id)
        assertTrue(Regex("custom_[0-9a-f]{12}").matches(first.profile.id))
    }

    @Test
    fun generatedIdsDoNotCollideAcrossManyProfiles() {
        assertEquals(500, List(500) { ProfileIds.generate() }.toSet().size)
    }

    // -- ordering --

    @Test
    fun displayOrderIsDeterministicByOrderNotListPosition() {
        val shuffled = initial.copy(profiles = initial.profiles.reversed())

        assertEquals(initial.orderedProfiles, shuffled.orderedProfiles)
        assertEquals(listOf(0, 1, 2, 3), shuffled.orderedProfiles.map { it.order })
    }

    @Test
    fun normalizingIsIdempotentAndRenumbersGaps() {
        val gappy = initial.copy(
            profiles = initial.profiles.mapIndexed { index, profile -> profile.copy(order = index * 10 + 3) }
        )

        val once = gappy.normalized()

        assertEquals(listOf(0, 1, 2, 3), once.orderedProfiles.map { it.order })
        assertEquals(once, once.normalized())
        assertEquals(ProfileIds.BUILT_IN, once.orderedProfiles.map { it.id })
    }

    // -- arbitrary count and classification --

    @Test
    fun anyNumberOfCustomProfilesIsSupported() {
        var collection = initial
        repeat(40) { index ->
            collection = ProfileEditor.createProfile(collection, "Custom $index").value().collection
        }

        assertEquals(44, collection.profiles.size)
        assertTrue(collection.isValid())
        assertEquals(44, collection.profiles.map { it.id }.toSet().size)
        assertEquals((0 until 44).toList(), collection.orderedProfiles.map { it.order })
        assertEquals(4, collection.profiles.count { it.builtIn })
        assertEquals(40, collection.profiles.count { !it.builtIn })
    }

    @Test
    fun builtInAndCustomProfilesAreClassifiedByTheirId() {
        assertTrue(initial.profiles.all { it.builtIn })
        val custom = ProfileEditor.createProfile(initial, "Mine").value().profile
        assertFalse(custom.builtIn)
        assertTrue(ProfileIds.isBuiltIn("dev"))
        assertFalse(ProfileIds.isBuiltIn(custom.id))
        assertFalse(ProfileIds.isBuiltIn("Dev"))
    }

    @Test
    fun aProfileWhoseBuiltInFlagDisagreesWithItsIdIsNotValidUntilNormalized() {
        val lyingDev = initial.profile(ProfileIds.DEV)!!.copy(builtIn = false)
        val collection = initial.withProfile(lyingDev)

        assertFalse(lyingDev.isValid())
        assertFalse(collection.isValid())
        assertTrue(collection.normalized().profile(ProfileIds.DEV)!!.builtIn)
        assertTrue(collection.normalized().isValid())
    }

    @Test
    fun duplicateIdsCollapseToTheFirstProfile() {
        val impostor = initial.profile(ProfileIds.DEV)!!.copy(name = "Impostor", order = 99)
        val collection = initial.copy(profiles = initial.profiles + impostor).normalized()

        assertEquals(4, collection.profiles.size)
        assertEquals("Dev", collection.profile(ProfileIds.DEV)!!.name)
    }

    // -- ownership --

    @Test
    fun everyProfileOwnsExactlyOneIndependentDashboard() {
        val dashboards = initial.profiles.map { it.dashboard }
        for (i in dashboards.indices) for (j in i + 1 until dashboards.size) {
            assertNotSame(dashboards[i], dashboards[j])
            assertNotEquals("profiles ${ProfileIds.BUILT_IN[i]} and ${ProfileIds.BUILT_IN[j]} must not share a layout", dashboards[i], dashboards[j])
        }
    }

    @Test
    fun editingOneProfilesDashboardNeverChangesAnother() {
        val before = initial
        val dev = initial.profile(ProfileIds.DEV)!!.dashboard
        val edited = DashboardEditor.createPage(dev, "Scratch").value()

        val after = ProfileEditor.replaceDashboard(before, ProfileIds.DEV, edited).value()

        assertEquals(edited, after.profile(ProfileIds.DEV)!!.dashboard)
        for (id in listOf(ProfileIds.DEFAULT, ProfileIds.GAME, ProfileIds.MEDIA)) {
            assertEquals("$id changed", before.profile(id), after.profile(id))
        }
    }

    // -- validity --

    @Test
    fun aProfileIsValidOnlyWithAnIdANameAndAValidDashboard() {
        val dev = initial.profile(ProfileIds.DEV)!!

        assertTrue(dev.isValid())
        assertFalse(dev.copy(id = " ").isValid())
        assertFalse(dev.copy(name = "  ").isValid())
        assertFalse(dev.copy(dashboard = Dashboard(emptyList())).isValid())
        assertFalse(dev.copy(dashboard = Dashboard(listOf(DashboardPage("p", "P", 0, emptyList()), DashboardPage("p", "Q", 1, emptyList())))).isValid())
    }

    @Test
    fun aCollectionNeedsProfilesUniqueIdsAndResolvableSelection() {
        assertTrue(initial.isValid())
        assertFalse(ProfileCollection(emptyList(), "default", "default").isValid())
        assertFalse(initial.copy(lastSelectedProfileId = "ghost").isValid())
        assertFalse(initial.copy(defaultProfileId = "ghost").isValid())
    }
}
