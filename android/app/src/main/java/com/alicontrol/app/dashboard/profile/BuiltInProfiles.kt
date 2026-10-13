package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.dashboard.DashboardWidget
import com.alicontrol.app.dashboard.DefaultDashboardFactory
import com.alicontrol.app.dashboard.ShortcutDestination
import com.alicontrol.app.dashboard.WidgetConfig
import com.alicontrol.app.dashboard.WidgetType

/**
 * The four shipped profiles — **Default**, **Dev**, **Game**, **Media** — as data,
 * in the same spirit as [DefaultDashboardFactory]: layouts are values on the
 * existing 6x4 grid, not code that builds Views.
 *
 * - Every template uses only existing widget types and ids from `ActionLibrary`
 *   (so nothing here can introduce an action the Windows agent does not
 *   allowlist), and every placement satisfies the normal grid and per-type size
 *   rules. `BuiltInProfilesTest` re-checks all of that for every template.
 * - **Default** is [DefaultDashboardFactory] unchanged — the layout every existing
 *   install already has — so "Reset to Default Layout" restores exactly what the
 *   app shipped with before profiles existed.
 * - These are layout templates, not features: nothing here detects processes or
 *   games, drives Steam/Discord/fans, or reserves a placeholder for AI developer
 *   tools (TASK-014). Free cells are simply free.
 * - Profiles describe user contexts, not operating systems; there is no per-platform
 *   variant of any of them.
 *
 * Each call to [dashboardFor] builds a fresh [Dashboard], so a profile reset or a
 * newly added built-in can never alias another profile's data.
 */
object BuiltInProfiles {

    /** The user-facing name a built-in profile ships with. The id is [ProfileIds]; the name is separate and can be renamed. */
    fun displayName(id: String): String? = when (id) {
        ProfileIds.DEFAULT -> "Default"
        ProfileIds.DEV -> "Dev"
        ProfileIds.GAME -> "Game"
        ProfileIds.MEDIA -> "Media"
        else -> null
    }

    /** The shipped layout for a built-in profile id, or null for any other id (custom profiles have no template). */
    fun dashboardFor(id: String): Dashboard? = when (id) {
        ProfileIds.DEFAULT -> DefaultDashboardFactory.create()
        ProfileIds.DEV -> devDashboard()
        ProfileIds.GAME -> gameDashboard()
        ProfileIds.MEDIA -> mediaDashboard()
        else -> null
    }

    /**
     * The built-in profile [id] at display position [order]. [dashboard] overrides the
     * shipped template — used only to carry a migrated pre-profile layout into Default.
     */
    fun profile(id: String, order: Int, dashboard: Dashboard? = null): DashboardProfile? {
        val name = displayName(id) ?: return null
        val layout = dashboard ?: dashboardFor(id) ?: return null
        return DashboardProfile(id = id, name = name, order = order, builtIn = true, dashboard = layout)
    }

    /**
     * The collection a first run starts with: all four built-ins in canonical order,
     * Default selected and set as the startup default. [defaultDashboard], when given,
     * becomes the Default profile's dashboard instead of the shipped one — that is the
     * whole of the legacy-layout migration (see [ProfileStore]).
     */
    fun initialCollection(defaultDashboard: Dashboard? = null): ProfileCollection = ProfileCollection(
        profiles = ProfileIds.BUILT_IN.mapIndexedNotNull { index, id ->
            profile(id, index, dashboard = if (id == ProfileIds.DEFAULT) defaultDashboard else null)
        },
        lastSelectedProfileId = ProfileIds.DEFAULT,
        defaultProfileId = ProfileIds.DEFAULT
    )

    /**
     * Guarantees every built-in profile exists: any that is missing (never stored, or
     * lost to a damaged entry) is added from its shipped template after the profiles
     * already present, and the result is normalized. This is also how a build that ships
     * a new built-in profile reaches existing installs, without touching anything the
     * user already has.
     */
    fun complete(collection: ProfileCollection): ProfileCollection {
        val missing = ProfileIds.BUILT_IN.filter { collection.profile(it) == null }
        if (missing.isEmpty()) return collection.normalized()
        val firstNewOrder = (collection.profiles.maxOfOrNull { it.order } ?: -1) + 1
        val added = missing.mapIndexedNotNull { index, id -> profile(id, firstNewOrder + index) }
        return collection.copy(profiles = collection.profiles + added).normalized()
    }

    // -- Dev ----------------------------------------------------------------------------

    private fun devDashboard(): Dashboard = Dashboard(listOf(devMainPage(), devToolsPage(), devSystemPage()))

    /** Dev — VS Code, Terminal and Browser big; Explorer, Search, Task Manager; live CPU/RAM/GPU along the bottom. */
    private fun devMainPage() = DashboardPage(
        id = "page_dev_main",
        name = "Dev",
        order = 0,
        widgets = listOf(
            action("dev_vscode", AliControlCommands.OPEN_VSCODE, 0, 0, 2, 2),
            action("dev_terminal", AliControlCommands.OPEN_TERMINAL, 2, 0, 2, 2),
            action("dev_browser", AliControlCommands.OPEN_BROWSER, 4, 0, 2, 2),

            action("dev_explorer", AliControlCommands.OPEN_EXPLORER, 0, 2, 2, 1),
            action("dev_search", AliControlCommands.WINDOWS_SEARCH, 2, 2, 2, 1),
            action("dev_task_manager", AliControlCommands.OPEN_TASK_MANAGER, 4, 2, 2, 1),

            metric("dev_cpu", "CPU", 0, 3, 2, 1),
            metric("dev_ram", "RAM", 2, 3, 2, 1),
            gauge("dev_gpu", "GPU", 4, 3, 2, 1)
        )
    )

    /** Tools — capture, desktop and edit helpers. The free cells are intentionally left for the user. */
    private fun devToolsPage() = DashboardPage(
        id = "page_dev_tools",
        name = "Tools",
        order = 1,
        widgets = listOf(
            action("tools_screenshot", AliControlCommands.SCREENSHOT, 0, 0, 2, 2),
            action("tools_show_desktop", AliControlCommands.SHOW_DESKTOP, 2, 0, 2, 2),
            action("tools_task_view", AliControlCommands.TASK_VIEW, 4, 0, 2, 2),

            group(
                "tools_clipboard", 0, 2, 4, 1,
                AliControlCommands.COPY, AliControlCommands.PASTE, AliControlCommands.UNDO, AliControlCommands.REDO
            ),
            group("tools_window", 4, 2, 2, 1, AliControlCommands.MINIMIZE_WINDOW, AliControlCommands.FULLSCREEN_TOGGLE),

            shortcut("tools_all_actions", "All Actions", ShortcutDestination.ALL_ACTIONS, 0, 3, 2, 1)
        )
    )

    /** System — large live readings, then the system tools and Device settings. */
    private fun devSystemPage() = DashboardPage(
        id = "page_dev_system",
        name = "System",
        order = 2,
        widgets = listOf(
            metric("dsys_cpu", "CPU", 0, 0, 2, 2),
            metric("dsys_ram", "RAM", 2, 0, 2, 2),
            gauge("dsys_gpu", "GPU", 4, 0, 2, 2),

            action("dsys_task_manager", AliControlCommands.OPEN_TASK_MANAGER, 0, 2, 2, 1),
            action("dsys_settings", AliControlCommands.OPEN_SYSTEM_SETTINGS, 2, 2, 2, 1),
            shortcut("dsys_device", "Device", ShortcutDestination.DEVICE_SETTINGS, 4, 2, 2, 1),

            action("dsys_lock", AliControlCommands.LOCK_SCREEN, 0, 3, 2, 1)
        )
    )

    // -- Game ---------------------------------------------------------------------------

    private fun gameDashboard(): Dashboard = Dashboard(listOf(gameMainPage(), gameMediaPage(), gameSystemPage()))

    /** Game — big CPU/GPU/RAM, media and volume strips with a dedicated mute, and one row of quick actions. */
    private fun gameMainPage() = DashboardPage(
        id = "page_game_main",
        name = "Game",
        order = 0,
        widgets = listOf(
            metric("game_cpu", "CPU", 0, 0, 2, 2),
            gauge("game_gpu", "GPU", 2, 0, 2, 2),
            metric("game_ram", "RAM", 4, 0, 2, 2),

            group(
                "game_transport", 0, 2, 3, 1,
                AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK
            ),
            group("game_volume", 3, 2, 2, 1, AliControlCommands.VOLUME_DOWN, AliControlCommands.VOLUME_UP),
            action("game_mute", AliControlCommands.MUTE_TOGGLE, 5, 2, 1, 1),

            action("game_spotify", AliControlCommands.OPEN_SPOTIFY, 0, 3, 1, 1),
            action("game_task_manager", AliControlCommands.OPEN_TASK_MANAGER, 1, 3, 1, 1),
            action("game_show_desktop", AliControlCommands.SHOW_DESKTOP, 2, 3, 1, 1),
            action("game_lock", AliControlCommands.LOCK_SCREEN, 3, 3, 1, 1),
            action("game_screenshot", AliControlCommands.SCREENSHOT, 4, 3, 1, 1),
            shortcut("game_device", "Device", ShortcutDestination.DEVICE_SETTINGS, 5, 3, 1, 1)
        )
    )

    /** Media — the full MEDIA widget over volume, mute, Spotify and a clock. */
    private fun gameMediaPage() = DashboardPage(
        id = "page_game_media",
        name = "Media",
        order = 1,
        widgets = listOf(
            media("gmedia_now_playing", 0, 0, 6, 3),
            action("gmedia_volume_down", AliControlCommands.VOLUME_DOWN, 0, 3, 1, 1),
            action("gmedia_volume_up", AliControlCommands.VOLUME_UP, 1, 3, 1, 1),
            action("gmedia_mute", AliControlCommands.MUTE_TOGGLE, 2, 3, 1, 1),
            action("gmedia_spotify", AliControlCommands.OPEN_SPOTIFY, 3, 3, 1, 1),
            clock("gmedia_clock", 4, 3, 2, 1)
        )
    )

    /** System — large one-tap buttons for the things you reach for mid-game. */
    private fun gameSystemPage() = DashboardPage(
        id = "page_game_system",
        name = "System",
        order = 2,
        widgets = listOf(
            action("gsys_task_manager", AliControlCommands.OPEN_TASK_MANAGER, 0, 0, 2, 2),
            action("gsys_show_desktop", AliControlCommands.SHOW_DESKTOP, 2, 0, 2, 2),
            action("gsys_lock", AliControlCommands.LOCK_SCREEN, 4, 0, 2, 2),

            action("gsys_task_view", AliControlCommands.TASK_VIEW, 0, 2, 2, 1),
            action("gsys_search", AliControlCommands.WINDOWS_SEARCH, 2, 2, 2, 1),
            action("gsys_settings", AliControlCommands.OPEN_SYSTEM_SETTINGS, 4, 2, 2, 1),

            shortcut("gsys_device", "Device", ShortcutDestination.DEVICE_SETTINGS, 0, 3, 2, 1),
            shortcut("gsys_all_actions", "All Actions", ShortcutDestination.ALL_ACTIONS, 2, 3, 2, 1)
        )
    )

    // -- Media --------------------------------------------------------------------------

    private fun mediaDashboard(): Dashboard = Dashboard(listOf(nowPlayingPage(), controlsPage(), appsPage()))

    /** Now Playing — the existing MEDIA widget at 6x3 (title, artist, album, progress, artwork), with a volume strip and a clock. */
    private fun nowPlayingPage() = DashboardPage(
        id = "page_media_now_playing",
        name = "Now Playing",
        order = 0,
        widgets = listOf(
            media("np_media", 0, 0, 6, 3),
            group(
                "np_volume", 0, 3, 3, 1,
                AliControlCommands.VOLUME_DOWN, AliControlCommands.MUTE_TOGGLE, AliControlCommands.VOLUME_UP
            ),
            clock("np_clock", 3, 3, 3, 1)
        )
    )

    /** Controls — transport and volume/mute as large touch targets. */
    private fun controlsPage() = DashboardPage(
        id = "page_media_controls",
        name = "Controls",
        order = 1,
        widgets = listOf(
            group(
                "ctl_transport", 0, 0, 6, 2,
                AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK
            ),
            group(
                "ctl_volume", 0, 2, 6, 2,
                AliControlCommands.VOLUME_DOWN, AliControlCommands.MUTE_TOGGLE, AliControlCommands.VOLUME_UP
            )
        )
    )

    /** Apps — the Spotify launcher and the everyday launchers around it. */
    private fun appsPage() = DashboardPage(
        id = "page_media_apps",
        name = "Apps",
        order = 2,
        widgets = listOf(
            action("apps_spotify", AliControlCommands.OPEN_SPOTIFY, 0, 0, 2, 2),
            action("apps_browser", AliControlCommands.OPEN_BROWSER, 2, 0, 2, 2),
            action("apps_explorer", AliControlCommands.OPEN_EXPLORER, 4, 0, 2, 2),

            action("apps_show_desktop", AliControlCommands.SHOW_DESKTOP, 0, 2, 2, 1),
            action("apps_task_view", AliControlCommands.TASK_VIEW, 2, 2, 2, 1),
            action("apps_search", AliControlCommands.WINDOWS_SEARCH, 4, 2, 2, 1),

            action("apps_lock", AliControlCommands.LOCK_SCREEN, 0, 3, 2, 1),
            action("apps_screenshot", AliControlCommands.SCREENSHOT, 2, 3, 2, 1),
            shortcut("apps_device", "Device", ShortcutDestination.DEVICE_SETTINGS, 4, 3, 2, 1)
        )
    )

    // -- widget literals ----------------------------------------------------------------

    private fun action(id: String, actionId: String, x: Int, y: Int, w: Int, h: Int) =
        DashboardWidget(id, WidgetType.ACTION, x, y, w, h, WidgetConfig.Action(actionId))

    private fun group(id: String, x: Int, y: Int, w: Int, h: Int, vararg actionIds: String) =
        DashboardWidget(id, WidgetType.ACTION_GROUP, x, y, w, h, WidgetConfig.ActionGroup(actionIds.toList()))

    private fun metric(id: String, label: String, x: Int, y: Int, w: Int, h: Int) =
        DashboardWidget(id, WidgetType.METRIC, x, y, w, h, WidgetConfig.Metric(label))

    private fun gauge(id: String, label: String, x: Int, y: Int, w: Int, h: Int) =
        DashboardWidget(id, WidgetType.GAUGE, x, y, w, h, WidgetConfig.Gauge(label))

    private fun shortcut(id: String, label: String, destination: ShortcutDestination, x: Int, y: Int, w: Int, h: Int) =
        DashboardWidget(id, WidgetType.SHORTCUT, x, y, w, h, WidgetConfig.Shortcut(label, destination))

    private fun media(id: String, x: Int, y: Int, w: Int, h: Int) = DashboardWidget(
        id, WidgetType.MEDIA, x, y, w, h,
        WidgetConfig.Media(
            AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK
        )
    )

    /** A clock without the secondary date line (matches the Default Media page). */
    private fun clock(id: String, x: Int, y: Int, w: Int, h: Int) =
        DashboardWidget(id, WidgetType.CLOCK, x, y, w, h, WidgetConfig.Clock(showDate = false))
}
