package com.alicontrol.app.dashboard

import com.alicontrol.app.ble.AliControlCommands

/**
 * Ali Control's shipped dashboard, expressed as data rather than assembled
 * out of Views inside an Activity. It is the starting point a user edits and
 * the layout [DashboardStore] restores on Reset to Default — never something
 * the app forces back over a saved layout.
 *
 * The defaults are Windows-native: Windows Search, Task View, File Explorer and
 * Task Manager sit where the macOS-era layout had Spotlight, Mission Control,
 * Finder and Activity Monitor.
 *
 * TASK-008 refines the TASK-007 defaults onto the coarser 6x4 grid:
 * the large media player is gone from the Dashboard page (it lives on the
 * dedicated Media page, where there's room for it) and is replaced by a
 * compact transport strip, a DAILY_OVERVIEW panel becomes the page's primary
 * visual element, and single actions shrink to 1x1 so six of them fit in one
 * row instead of consuming a quarter of the screen each.
 *
 * All three pages happen to fill their 24 cells exactly, but nothing
 * requires that — empty cells are legal, and the editor produces them
 * routinely.
 */
object DefaultDashboardFactory {

    fun create(): Dashboard = Dashboard(listOf(dashboardPage(), mediaPage(), devPage()))

    /**
     * Page 1 — Dashboard.
     *
     * ```
     * ┌────────────────────┬──────┬──────┬──────┐
     * │                    │ CPU  │ GPU  │ RAM  │
     * │   DAILY OVERVIEW   ├──────┼──────┼──────┤
     * │                    │ Lock │ Shot │Device│
     * ├──────┬──────┬──────┼──────┼──────┼──────┤
     * │ Term │ Code │Search│ Task │Explor│Browsr│
     * ├──────┴──────┴──────┼──────┴──────┴──────┤
     * │   ⏮   ▶/⏸   ⏭    │  Vol-  Mute  Vol+  │
     * └────────────────────┴────────────────────┘
     * ```
     */
    private fun dashboardPage(): DashboardPage = DashboardPage(
        id = "page_dashboard",
        name = "Dashboard",
        order = 0,
        widgets = listOf(
            DashboardWidget("dash_overview", WidgetType.DAILY_OVERVIEW, 0, 0, 3, 2, WidgetConfig.DailyOverview()),

            DashboardWidget("dash_cpu", WidgetType.METRIC, 3, 0, 1, 1, WidgetConfig.Metric("CPU")),
            DashboardWidget("dash_gpu", WidgetType.METRIC, 4, 0, 1, 1, WidgetConfig.Metric("GPU")),
            DashboardWidget("dash_ram", WidgetType.METRIC, 5, 0, 1, 1, WidgetConfig.Metric("RAM")),

            DashboardWidget("dash_lock", WidgetType.ACTION, 3, 1, 1, 1, WidgetConfig.Action(AliControlCommands.LOCK_SCREEN)),
            DashboardWidget("dash_screenshot", WidgetType.ACTION, 4, 1, 1, 1, WidgetConfig.Action(AliControlCommands.SCREENSHOT)),
            DashboardWidget(
                "dash_device_settings", WidgetType.SHORTCUT, 5, 1, 1, 1,
                WidgetConfig.Shortcut("Device", ShortcutDestination.DEVICE_SETTINGS)
            ),

            DashboardWidget("dash_terminal", WidgetType.ACTION, 0, 2, 1, 1, WidgetConfig.Action(AliControlCommands.OPEN_TERMINAL)),
            DashboardWidget("dash_vscode", WidgetType.ACTION, 1, 2, 1, 1, WidgetConfig.Action(AliControlCommands.OPEN_VSCODE)),
            DashboardWidget("dash_windows_search", WidgetType.ACTION, 2, 2, 1, 1, WidgetConfig.Action(AliControlCommands.WINDOWS_SEARCH)),
            DashboardWidget("dash_task_view", WidgetType.ACTION, 3, 2, 1, 1, WidgetConfig.Action(AliControlCommands.TASK_VIEW)),
            DashboardWidget("dash_explorer", WidgetType.ACTION, 4, 2, 1, 1, WidgetConfig.Action(AliControlCommands.OPEN_EXPLORER)),
            DashboardWidget("dash_browser", WidgetType.ACTION, 5, 2, 1, 1, WidgetConfig.Action(AliControlCommands.OPEN_BROWSER)),

            // Compact media controls replace TASK-007's full-size MEDIA widget here.
            DashboardWidget(
                "dash_transport", WidgetType.ACTION_GROUP, 0, 3, 3, 1,
                WidgetConfig.ActionGroup(
                    listOf(AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK)
                )
            ),
            DashboardWidget(
                "dash_volume", WidgetType.ACTION_GROUP, 3, 3, 3, 1,
                WidgetConfig.ActionGroup(
                    listOf(AliControlCommands.VOLUME_DOWN, AliControlCommands.MUTE_TOGGLE, AliControlCommands.VOLUME_UP)
                )
            )
        )
    )

    /** Page 2 — Media: keeps the full-size MEDIA widget, plus volume/mute/Spotify and a clock. */
    private fun mediaPage(): DashboardPage = DashboardPage(
        id = "page_media",
        name = "Media",
        order = 1,
        widgets = listOf(
            DashboardWidget(
                "media_now_playing", WidgetType.MEDIA, 0, 0, 6, 3,
                WidgetConfig.Media(AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK)
            ),
            DashboardWidget("media_volume_down", WidgetType.ACTION, 0, 3, 1, 1, WidgetConfig.Action(AliControlCommands.VOLUME_DOWN)),
            DashboardWidget("media_volume_up", WidgetType.ACTION, 1, 3, 1, 1, WidgetConfig.Action(AliControlCommands.VOLUME_UP)),
            DashboardWidget("media_mute", WidgetType.ACTION, 2, 3, 1, 1, WidgetConfig.Action(AliControlCommands.MUTE_TOGGLE)),
            DashboardWidget("media_spotify", WidgetType.ACTION, 3, 3, 1, 1, WidgetConfig.Action(AliControlCommands.OPEN_SPOTIFY)),
            DashboardWidget("media_clock", WidgetType.CLOCK, 4, 3, 2, 1, WidgetConfig.Clock(showDate = false))
        )
    )

    /** Page 3 — Dev: VS Code, Terminal, Browser, Windows Search, Explorer, Task Manager, plus system metrics and All Actions. */
    private fun devPage(): DashboardPage = DashboardPage(
        id = "page_dev",
        name = "Dev",
        order = 2,
        widgets = listOf(
            DashboardWidget("dev_vscode", WidgetType.ACTION, 0, 0, 2, 1, WidgetConfig.Action(AliControlCommands.OPEN_VSCODE)),
            DashboardWidget("dev_terminal", WidgetType.ACTION, 2, 0, 2, 1, WidgetConfig.Action(AliControlCommands.OPEN_TERMINAL)),
            DashboardWidget("dev_browser", WidgetType.ACTION, 4, 0, 2, 1, WidgetConfig.Action(AliControlCommands.OPEN_BROWSER)),

            DashboardWidget("dev_windows_search", WidgetType.ACTION, 0, 1, 2, 1, WidgetConfig.Action(AliControlCommands.WINDOWS_SEARCH)),
            DashboardWidget("dev_explorer", WidgetType.ACTION, 2, 1, 2, 1, WidgetConfig.Action(AliControlCommands.OPEN_EXPLORER)),
            DashboardWidget("dev_task_manager", WidgetType.ACTION, 4, 1, 2, 1, WidgetConfig.Action(AliControlCommands.OPEN_TASK_MANAGER)),

            DashboardWidget("dev_screenshot", WidgetType.ACTION, 0, 2, 2, 1, WidgetConfig.Action(AliControlCommands.SCREENSHOT)),
            DashboardWidget("dev_system_settings", WidgetType.ACTION, 2, 2, 2, 1, WidgetConfig.Action(AliControlCommands.OPEN_SYSTEM_SETTINGS)),
            DashboardWidget(
                "dev_all_actions", WidgetType.SHORTCUT, 4, 2, 2, 1,
                WidgetConfig.Shortcut("All Actions", ShortcutDestination.ALL_ACTIONS)
            ),

            DashboardWidget("dev_cpu", WidgetType.METRIC, 0, 3, 2, 1, WidgetConfig.Metric("CPU")),
            DashboardWidget("dev_ram", WidgetType.METRIC, 2, 3, 2, 1, WidgetConfig.Metric("RAM")),
            DashboardWidget("dev_gpu_gauge", WidgetType.GAUGE, 4, 3, 2, 1, WidgetConfig.Gauge("GPU"))
        )
    )
}
