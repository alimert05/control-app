package com.alicontrol.app.actions

import com.alicontrol.app.ble.AliControlCommands

/**
 * The full, static, in-memory catalog of actions Ali Control knows about.
 * Android never executes anything itself; this only describes what MAY be sent
 * (as one of these action IDs) and lets the UI filter by platform/category.
 *
 * The connected desktop agent owns all actual execution and independently
 * re-validates every ID against its own allowlist (see the Windows agent's
 * `ActionRegistry`) — this list existing here does not itself grant the tablet
 * the ability to run anything; it is purely descriptive metadata for the one
 * thing Android is allowed to do, which is send a predefined action ID.
 *
 * Danger levels and permission requirements mirror the agent's own
 * classification exactly (its `UsesInputSimulation` flags) — kept in sync
 * manually, the same way the UUIDs and action ID strings themselves are.
 */
object ActionLibrary {

    private val WINDOWS = setOf(Platform.WINDOWS)

    val all: List<ActionDefinition> = listOf(
        // Media — system media keys: global, focus-independent.
        ActionDefinition(AliControlCommands.PLAY_PAUSE, "Play / Pause", ActionCategory.MEDIA, WINDOWS),
        ActionDefinition(AliControlCommands.NEXT_TRACK, "Next Track", ActionCategory.MEDIA, WINDOWS),
        ActionDefinition(AliControlCommands.PREVIOUS_TRACK, "Previous Track", ActionCategory.MEDIA, WINDOWS),
        ActionDefinition(AliControlCommands.VOLUME_UP, "Volume Up", ActionCategory.MEDIA, WINDOWS),
        ActionDefinition(AliControlCommands.VOLUME_DOWN, "Volume Down", ActionCategory.MEDIA, WINDOWS),
        ActionDefinition(AliControlCommands.MUTE_TOGGLE, "Mute", ActionCategory.MEDIA, WINDOWS),

        // System
        ActionDefinition(AliControlCommands.LOCK_SCREEN, "Lock Screen", ActionCategory.SYSTEM, WINDOWS),
        ActionDefinition(
            AliControlCommands.SLEEP, "Sleep", ActionCategory.SYSTEM, WINDOWS,
            dangerLevel = DangerLevel.CONFIRM
        ),
        ActionDefinition(AliControlCommands.DISPLAY_SLEEP, "Display Sleep", ActionCategory.SYSTEM, WINDOWS),
        ActionDefinition(
            AliControlCommands.SCREENSHOT, "Screenshot", ActionCategory.SYSTEM, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION
        ),
        ActionDefinition(
            AliControlCommands.SHOW_DESKTOP, "Show Desktop", ActionCategory.SYSTEM, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION
        ),
        ActionDefinition(
            AliControlCommands.WINDOWS_SEARCH, "Windows Search", ActionCategory.SYSTEM, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION,
            shortLabel = "Search"
        ),
        ActionDefinition(
            AliControlCommands.TASK_VIEW, "Task View", ActionCategory.SYSTEM, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION,
            shortLabel = "Task View"
        ),

        // Apps — fixed launches owned by the agent.
        ActionDefinition(AliControlCommands.OPEN_TERMINAL, "Terminal", ActionCategory.APPS, WINDOWS),
        ActionDefinition(AliControlCommands.OPEN_VSCODE, "VS Code", ActionCategory.APPS, WINDOWS),
        ActionDefinition(AliControlCommands.OPEN_EXPLORER, "File Explorer", ActionCategory.APPS, WINDOWS, shortLabel = "Explorer"),
        ActionDefinition(AliControlCommands.OPEN_BROWSER, "Browser", ActionCategory.APPS, WINDOWS),
        ActionDefinition(AliControlCommands.OPEN_SPOTIFY, "Spotify", ActionCategory.APPS, WINDOWS),
        ActionDefinition(AliControlCommands.OPEN_SYSTEM_SETTINGS, "Settings", ActionCategory.APPS, WINDOWS),
        ActionDefinition(AliControlCommands.OPEN_TASK_MANAGER, "Task Manager", ActionCategory.APPS, WINDOWS, shortLabel = "Tasks"),

        // Window — the foreground window's own system commands (never process termination).
        ActionDefinition(AliControlCommands.MINIMIZE_WINDOW, "Minimize Window", ActionCategory.WINDOW, WINDOWS),
        ActionDefinition(AliControlCommands.CLOSE_WINDOW, "Close Window", ActionCategory.WINDOW, WINDOWS),
        // Windows has no OS-level fullscreen: this maximizes / restores the foreground window.
        ActionDefinition(AliControlCommands.FULLSCREEN_TOGGLE, "Maximize / Restore", ActionCategory.WINDOW, WINDOWS),

        // Productivity — edit shortcuts on the focused window.
        ActionDefinition(
            AliControlCommands.COPY, "Copy", ActionCategory.PRODUCTIVITY, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION
        ),
        ActionDefinition(
            AliControlCommands.PASTE, "Paste", ActionCategory.PRODUCTIVITY, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION
        ),
        ActionDefinition(
            AliControlCommands.UNDO, "Undo", ActionCategory.PRODUCTIVITY, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION
        ),
        ActionDefinition(
            AliControlCommands.REDO, "Redo", ActionCategory.PRODUCTIVITY, WINDOWS,
            permissionRequirement = PermissionRequirement.INPUT_SIMULATION
        )
    )

    private val byId: Map<String, ActionDefinition> = all.associateBy { it.id }

    fun find(id: String): ActionDefinition? = byId[id]

    fun forPlatform(platform: Platform): List<ActionDefinition> = all.filter { it.supports(platform) }

    fun forCategory(category: ActionCategory): List<ActionDefinition> = all.filter { it.category == category }
}
