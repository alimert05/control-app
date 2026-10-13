package com.alicontrol.app.actions

import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.icons.ActionIconRegistry
import org.junit.Assert.assertEquals
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
    fun everyActionHasANonBlankLabelAndShortLabel() {
        for (action in ActionLibrary.all) {
            assertTrue("action ${action.id} has a blank label", action.label.isNotBlank())
            assertTrue("action ${action.id} has a blank short label", action.shortLabel.isNotBlank())
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
    fun theActionIdSetIsExactlyTheOneTheWindowsAgentImplements() {
        // Must match AliControlActions.All (minus ping) in the Windows agent exactly.
        val expected = setOf(
            "play_pause", "next_track", "previous_track", "volume_up", "volume_down", "mute_toggle",
            "lock_screen", "sleep", "display_sleep", "screenshot", "show_desktop", "windows_search", "task_view",
            "open_terminal", "open_vscode", "open_explorer", "open_browser", "open_spotify", "open_system_settings", "open_task_manager",
            "minimize_window", "close_window", "fullscreen_toggle",
            "copy", "paste", "undo", "redo"
        )
        assertEquals(expected, ActionLibrary.all.map { it.id }.toSet())
    }

    @Test
    fun theRetiredReferencePlatformIdsAreGone() {
        for (legacy in listOf("spotlight", "mission_control", "open_finder", "open_activity_monitor")) {
            assertNull("$legacy must not be an active action any more", ActionLibrary.find(legacy))
        }
    }

    @Test
    fun existingStableActionIdsArePreserved() {
        val ids = ActionLibrary.all.map { it.id }.toSet()
        for (stable in listOf(
            AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK, AliControlCommands.PREVIOUS_TRACK,
            AliControlCommands.VOLUME_UP, AliControlCommands.VOLUME_DOWN, AliControlCommands.MUTE_TOGGLE,
            AliControlCommands.LOCK_SCREEN, AliControlCommands.SLEEP, AliControlCommands.SCREENSHOT,
            AliControlCommands.SHOW_DESKTOP, AliControlCommands.OPEN_TERMINAL, AliControlCommands.OPEN_VSCODE,
            AliControlCommands.OPEN_BROWSER, AliControlCommands.OPEN_SPOTIFY, AliControlCommands.OPEN_SYSTEM_SETTINGS,
            AliControlCommands.MINIMIZE_WINDOW, AliControlCommands.CLOSE_WINDOW, AliControlCommands.FULLSCREEN_TOGGLE,
            AliControlCommands.COPY, AliControlCommands.PASTE, AliControlCommands.UNDO, AliControlCommands.REDO
        )) {
            assertTrue("stable id $stable is missing", stable in ids)
        }
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
    fun everyActionIsAvailableOnWindowsTheOnlySupportedPlatform() {
        val windowsActions = ActionLibrary.forPlatform(Platform.WINDOWS)
        assertEquals(ActionLibrary.all.size, windowsActions.size)
        assertTrue(windowsActions.all { it.supports(Platform.WINDOWS) })
    }

    @Test
    fun windowsPlatformIsKnownByItsWireId() {
        assertEquals(Platform.WINDOWS, Platform.fromWireId("windows"))
        assertEquals(Platform.WINDOWS, Platform.fromWireId("Windows"))
        assertNull(Platform.fromWireId("macos"))
        assertNull(Platform.fromWireId("linux"))
        assertNull(Platform.fromWireId(""))
        assertNull(Platform.fromWireId(null))
        assertEquals(Platform.WINDOWS, Platform.DEFAULT)
    }

    @Test
    fun forCategoryReturnsOnlyThatCategory() {
        val media = ActionLibrary.forCategory(ActionCategory.MEDIA)
        assertTrue(media.isNotEmpty())
        assertTrue(media.all { it.category == ActionCategory.MEDIA })
        assertEquals(6, media.size)
    }

    @Test
    fun categoryCountsMatchTheAgent() {
        assertEquals(7, ActionLibrary.forCategory(ActionCategory.SYSTEM).size)
        assertEquals(7, ActionLibrary.forCategory(ActionCategory.APPS).size)
        assertEquals(3, ActionLibrary.forCategory(ActionCategory.WINDOW).size)
        assertEquals(4, ActionLibrary.forCategory(ActionCategory.PRODUCTIVITY).size)
    }

    @Test
    fun findReturnsNullForUnknownId() {
        assertNull(ActionLibrary.find("does_not_exist"))
        assertNull(ActionLibrary.find(""))
    }

    @Test
    fun keyboardInputDependentActionsAreCorrectlyFlagged() {
        // Must match the agent's UsesInputSimulation set exactly.
        val expected = setOf(
            AliControlCommands.SCREENSHOT, AliControlCommands.SHOW_DESKTOP,
            AliControlCommands.WINDOWS_SEARCH, AliControlCommands.TASK_VIEW,
            AliControlCommands.COPY, AliControlCommands.PASTE, AliControlCommands.UNDO, AliControlCommands.REDO
        )
        for (action in ActionLibrary.all) {
            val want = if (action.id in expected) PermissionRequirement.INPUT_SIMULATION else PermissionRequirement.NONE
            assertEquals("action ${action.id} permission mismatch", want, action.permissionRequirement)
        }
    }

    @Test
    fun everyActionHasADedicatedIcon() {
        for (action in ActionLibrary.all) {
            assertTrue("no dedicated icon for ${action.id}", ActionIconRegistry.hasDedicatedIcon(action.id))
        }
    }

    @Test
    fun ambiguousTileLabelsAreDisambiguated() {
        // "Task View" and "Task Manager" would both be "Task" by the first-word rule.
        assertNotNull(ActionLibrary.find(AliControlCommands.OPEN_TASK_MANAGER))
        assertEquals("Tasks", ActionLibrary.find(AliControlCommands.OPEN_TASK_MANAGER)?.shortLabel)
        assertEquals("Task View", ActionLibrary.find(AliControlCommands.TASK_VIEW)?.shortLabel)
        assertEquals("Search", ActionLibrary.find(AliControlCommands.WINDOWS_SEARCH)?.shortLabel)
        assertEquals("Explorer", ActionLibrary.find(AliControlCommands.OPEN_EXPLORER)?.shortLabel)
    }
}
