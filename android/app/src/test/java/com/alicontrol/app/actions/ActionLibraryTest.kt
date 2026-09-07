package com.alicontrol.app.actions

import com.alicontrol.app.ble.AliControlCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLibraryTest {

    @Test
    fun noDuplicateActionIds() {
        val ids = ActionLibrary.all.map { it.id }
        assertEquals("duplicate action id found", ids.size, ids.toSet().size)
    }

    @Test
    fun everyActionHasANonBlankLabel() {
        for (action in ActionLibrary.all) {
            assertTrue("action ${action.id} has a blank label", action.label.isNotBlank())
        }
    }

    @Test
    fun everyActionSupportsAtLeastOnePlatform() {
        for (action in ActionLibrary.all) {
            assertTrue("action ${action.id} supports no platform", action.supportedPlatforms.isNotEmpty())
        }
    }

    @Test
    fun expectedTotalActionCount() {
        // 6 media + 7 system + 7 apps + 3 window + 4 productivity = 27.
        assertEquals(27, ActionLibrary.all.size)
    }

    @Test
    fun existingPreTask006ActionIdsArePreserved() {
        val ids = ActionLibrary.all.map { it.id }.toSet()
        assertTrue(ids.contains(AliControlCommands.PLAY_PAUSE))
        assertTrue(ids.contains(AliControlCommands.VOLUME_UP))
        assertTrue(ids.contains(AliControlCommands.VOLUME_DOWN))
        assertTrue(ids.contains(AliControlCommands.MUTE_TOGGLE))
        assertTrue(ids.contains(AliControlCommands.OPEN_TERMINAL))
    }

    @Test
    fun sleepRequiresConfirmation() {
        assertEquals(DangerLevel.CONFIRM, ActionLibrary.find(AliControlCommands.SLEEP)?.dangerLevel)
    }

    @Test
    fun everyActionOtherThanSleepIsSafe() {
        for (action in ActionLibrary.all) {
            if (action.id == AliControlCommands.SLEEP) continue
            assertEquals("action ${action.id} should be SAFE", DangerLevel.SAFE, action.dangerLevel)
        }
    }

    @Test
    fun noShutdownRestartOrLogoutActionsExist() {
        val forbidden = setOf("shutdown", "restart", "logout", "log_out", "sign_out", "power_off")
        val ids = ActionLibrary.all.map { it.id }.toSet()
        assertTrue(forbidden.intersect(ids).isEmpty())
    }

    @Test
    fun macOnlyActionsAreNotAlsoMarkedForWindows() {
        val macOnlyIds = listOf(
            AliControlCommands.DISPLAY_SLEEP,
            AliControlCommands.SPOTLIGHT,
            AliControlCommands.MISSION_CONTROL,
            AliControlCommands.OPEN_FINDER,
            AliControlCommands.OPEN_ACTIVITY_MONITOR
        )
        for (id in macOnlyIds) {
            val action = ActionLibrary.find(id)
            assertNotNull("expected $id to exist", action)
            assertEquals(setOf(Platform.MACOS), action?.supportedPlatforms)
        }
    }

    @Test
    fun forPlatformReturnsOnlyActionsSupportingThatPlatform() {
        val macActions = ActionLibrary.forPlatform(Platform.MACOS)
        assertTrue(macActions.isNotEmpty())
        assertTrue(macActions.all { it.supports(Platform.MACOS) })

        // Every action currently supports macOS (it's the only implemented agent).
        assertEquals(ActionLibrary.all.size, macActions.size)

        val windowsActions = ActionLibrary.forPlatform(Platform.WINDOWS)
        assertTrue(windowsActions.all { it.supports(Platform.WINDOWS) })
        assertFalse(windowsActions.any { it.id == AliControlCommands.SPOTLIGHT })
    }

    @Test
    fun forCategoryReturnsOnlyThatCategory() {
        val media = ActionLibrary.forCategory(ActionCategory.MEDIA)
        assertTrue(media.isNotEmpty())
        assertTrue(media.all { it.category == ActionCategory.MEDIA })
        assertEquals(6, media.size)
    }

    @Test
    fun findReturnsNullForUnknownId() {
        assertNull(ActionLibrary.find("does_not_exist"))
        assertNull(ActionLibrary.find(""))
    }

    @Test
    fun accessibilityDependentActionsAreCorrectlyFlagged() {
        // Must match macOS ActionRegistry's requiresAccessibility set exactly.
        val expectedAccessibility = setOf(
            AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK, AliControlCommands.PREVIOUS_TRACK,
            AliControlCommands.SCREENSHOT, AliControlCommands.SHOW_DESKTOP, AliControlCommands.SPOTLIGHT, AliControlCommands.MISSION_CONTROL,
            AliControlCommands.MINIMIZE_WINDOW, AliControlCommands.CLOSE_WINDOW, AliControlCommands.FULLSCREEN_TOGGLE,
            AliControlCommands.COPY, AliControlCommands.PASTE, AliControlCommands.UNDO, AliControlCommands.REDO
        )
        for (action in ActionLibrary.all) {
            val expected = if (action.id in expectedAccessibility) PermissionRequirement.ACCESSIBILITY else PermissionRequirement.NONE
            assertEquals("action ${action.id} permission mismatch", expected, action.permissionRequirement)
        }
    }
}
