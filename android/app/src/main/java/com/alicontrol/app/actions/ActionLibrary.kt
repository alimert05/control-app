package com.alicontrol.app.actions

import com.alicontrol.app.ble.AliControlCommands

/**
 * The full, static, in-memory catalog of actions Ali Control knows about —
 * TASK-006's action registry. Android never executes anything itself; this
 * only describes what MAY be sent (as one of these action IDs) and lets UI
 * (and, later, TASK-007's customizable layout) filter by platform/category.
 *
 * The connected desktop agent owns all actual execution and independently
 * re-validates every ID against its own allowlist (see
 * macos/.../Control/ActionRegistry.swift) — this list existing here does not
 * itself grant the tablet the ability to run anything; it is purely
 * descriptive metadata for the one thing Android is actually allowed to do,
 * which is send a predefined action ID.
 *
 * Danger levels/permission requirements mirror the macOS agent's own
 * classification exactly (see ActionRegistry's `requiresAccessibility`
 * flags) — kept in sync manually, the same way the UUIDs and action ID
 * strings themselves are kept in sync across the two codebases.
 */
object ActionLibrary {

    val all: List<ActionDefinition> = listOf(
        // Media — play_pause/volume_up/volume_down/mute_toggle existed before TASK-006.
        ActionDefinition(
            AliControlCommands.PLAY_PAUSE, "Play / Pause", ActionCategory.MEDIA,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.NEXT_TRACK, "Next Track", ActionCategory.MEDIA,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.PREVIOUS_TRACK, "Previous Track", ActionCategory.MEDIA,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(AliControlCommands.VOLUME_UP, "Volume Up", ActionCategory.MEDIA, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(AliControlCommands.VOLUME_DOWN, "Volume Down", ActionCategory.MEDIA, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(AliControlCommands.MUTE_TOGGLE, "Mute", ActionCategory.MEDIA, setOf(Platform.MACOS, Platform.WINDOWS)),

        // System
        ActionDefinition(AliControlCommands.LOCK_SCREEN, "Lock Screen", ActionCategory.SYSTEM, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(
            AliControlCommands.SLEEP, "Sleep", ActionCategory.SYSTEM,
            setOf(Platform.MACOS, Platform.WINDOWS),
            dangerLevel = DangerLevel.CONFIRM
        ),
        ActionDefinition(AliControlCommands.DISPLAY_SLEEP, "Display Sleep", ActionCategory.SYSTEM, setOf(Platform.MACOS)),
        ActionDefinition(
            AliControlCommands.SCREENSHOT, "Screenshot", ActionCategory.SYSTEM,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.SHOW_DESKTOP, "Show Desktop", ActionCategory.SYSTEM,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.SPOTLIGHT, "Spotlight", ActionCategory.SYSTEM,
            setOf(Platform.MACOS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.MISSION_CONTROL, "Mission Control", ActionCategory.SYSTEM,
            setOf(Platform.MACOS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),

        // Apps — open_terminal existed before TASK-006.
        ActionDefinition(AliControlCommands.OPEN_TERMINAL, "Terminal", ActionCategory.APPS, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(AliControlCommands.OPEN_VSCODE, "VS Code", ActionCategory.APPS, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(AliControlCommands.OPEN_FINDER, "Finder", ActionCategory.APPS, setOf(Platform.MACOS)),
        ActionDefinition(AliControlCommands.OPEN_BROWSER, "Browser", ActionCategory.APPS, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(AliControlCommands.OPEN_SPOTIFY, "Spotify", ActionCategory.APPS, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(AliControlCommands.OPEN_SYSTEM_SETTINGS, "System Settings", ActionCategory.APPS, setOf(Platform.MACOS, Platform.WINDOWS)),
        ActionDefinition(AliControlCommands.OPEN_ACTIVITY_MONITOR, "Activity Monitor", ActionCategory.APPS, setOf(Platform.MACOS)),

        // Window
        ActionDefinition(
            AliControlCommands.MINIMIZE_WINDOW, "Minimize Window", ActionCategory.WINDOW,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.CLOSE_WINDOW, "Close Window", ActionCategory.WINDOW,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.FULLSCREEN_TOGGLE, "Fullscreen", ActionCategory.WINDOW,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),

        // Productivity
        ActionDefinition(
            AliControlCommands.COPY, "Copy", ActionCategory.PRODUCTIVITY,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.PASTE, "Paste", ActionCategory.PRODUCTIVITY,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.UNDO, "Undo", ActionCategory.PRODUCTIVITY,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        ),
        ActionDefinition(
            AliControlCommands.REDO, "Redo", ActionCategory.PRODUCTIVITY,
            setOf(Platform.MACOS, Platform.WINDOWS),
            permissionRequirement = PermissionRequirement.ACCESSIBILITY
        )
    )

    private val byId: Map<String, ActionDefinition> = all.associateBy { it.id }

    fun find(id: String): ActionDefinition? = byId[id]

    fun forPlatform(platform: Platform): List<ActionDefinition> = all.filter { it.supports(platform) }

    fun forCategory(category: ActionCategory): List<ActionDefinition> = all.filter { it.category == category }
}
