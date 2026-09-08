package com.alicontrol.app.dashboard

import com.alicontrol.app.ble.AliControlCommands

/**
 * Builds Ali Control's shipped 3-page dashboard as data, rather than each
 * page being hand-assembled out of Views inside an Activity. [MainActivity]
 * only ever reads the [Dashboard] this produces; it has no knowledge of
 * individual widgets. TASK-008 will eventually replace/extend this with a
 * persisted, user-editable equivalent — that migration only touches this
 * file and its call site, not the model or the renderer.
 *
 * All three pages together are laid out to exactly fill the
 * [DashboardGrid.COLUMNS] x [DashboardGrid.ROWS] grid with no overlaps —
 * verified by DashboardModelTest, not just by eye.
 */
object DefaultDashboardFactory {

    fun create(): Dashboard = Dashboard(listOf(dashboardPage(), mediaPage(), devPage()))

    /**
     * Page 1 — Dashboard: a balanced bento mix matching the TASK-007 mock —
     * CPU/GPU placeholders, quick system actions, a Now Playing shell, app
     * shortcuts, and volume/mute, plus a local clock and the Device/Settings
     * entry point required to stay reachable from the dashboard itself.
     */
    private fun dashboardPage(): DashboardPage = DashboardPage(
        id = "page_dashboard",
        name = "Dashboard",
        order = 0,
        widgets = listOf(
            DashboardWidget("dash_cpu", WidgetType.METRIC, 0, 0, 2, 2, WidgetConfig.Metric("CPU")),
            DashboardWidget("dash_gpu", WidgetType.METRIC, 2, 0, 2, 2, WidgetConfig.Metric("GPU")),
            DashboardWidget("dash_clock", WidgetType.CLOCK, 4, 0, 4, 2, WidgetConfig.Clock()),
            DashboardWidget("dash_lock", WidgetType.ACTION, 8, 0, 2, 2, WidgetConfig.Action(AliControlCommands.LOCK_SCREEN)),
            DashboardWidget("dash_screenshot", WidgetType.ACTION, 10, 0, 2, 2, WidgetConfig.Action(AliControlCommands.SCREENSHOT)),

            DashboardWidget(
                "dash_media", WidgetType.MEDIA, 0, 2, 6, 4,
                WidgetConfig.Media(AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK)
            ),
            DashboardWidget("dash_terminal", WidgetType.ACTION, 6, 2, 3, 2, WidgetConfig.Action(AliControlCommands.OPEN_TERMINAL)),
            DashboardWidget("dash_vscode", WidgetType.ACTION, 9, 2, 3, 2, WidgetConfig.Action(AliControlCommands.OPEN_VSCODE)),
            DashboardWidget("dash_spotlight", WidgetType.ACTION, 6, 4, 3, 2, WidgetConfig.Action(AliControlCommands.SPOTLIGHT)),
            DashboardWidget("dash_mission_control", WidgetType.ACTION, 9, 4, 3, 2, WidgetConfig.Action(AliControlCommands.MISSION_CONTROL)),

            DashboardWidget(
                "dash_volume", WidgetType.ACTION_GROUP, 0, 6, 3, 2,
                WidgetConfig.ActionGroup(listOf(AliControlCommands.VOLUME_DOWN, AliControlCommands.VOLUME_UP))
            ),
            DashboardWidget("dash_mute", WidgetType.ACTION, 3, 6, 3, 2, WidgetConfig.Action(AliControlCommands.MUTE_TOGGLE)),
            DashboardWidget("dash_ram", WidgetType.METRIC, 6, 6, 4, 2, WidgetConfig.Metric("RAM")),
            DashboardWidget(
                "dash_device_settings", WidgetType.SHORTCUT, 10, 6, 2, 2,
                WidgetConfig.Shortcut("Device", ShortcutDestination.DEVICE_SETTINGS)
            )
        )
    )

    /**
     * Page 2 — Media: a full-width Now Playing shell over the required
     * transport/volume/mute/Spotify actions.
     */
    private fun mediaPage(): DashboardPage = DashboardPage(
        id = "page_media",
        name = "Media",
        order = 1,
        widgets = listOf(
            DashboardWidget(
                "media_now_playing", WidgetType.MEDIA, 0, 0, 12, 4,
                WidgetConfig.Media(AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK)
            ),
            DashboardWidget("media_volume_down", WidgetType.ACTION, 0, 4, 6, 2, WidgetConfig.Action(AliControlCommands.VOLUME_DOWN)),
            DashboardWidget("media_volume_up", WidgetType.ACTION, 6, 4, 6, 2, WidgetConfig.Action(AliControlCommands.VOLUME_UP)),
            DashboardWidget("media_mute", WidgetType.ACTION, 0, 6, 6, 2, WidgetConfig.Action(AliControlCommands.MUTE_TOGGLE)),
            DashboardWidget("media_spotify", WidgetType.ACTION, 6, 6, 6, 2, WidgetConfig.Action(AliControlCommands.OPEN_SPOTIFY))
        )
    )

    /**
     * Page 3 — Dev: app shortcuts, a CPU/RAM placeholder pair standing in for
     * "System Info", and Screenshot/System Settings.
     */
    private fun devPage(): DashboardPage = DashboardPage(
        id = "page_dev",
        name = "Dev",
        order = 2,
        widgets = listOf(
            DashboardWidget("dev_vscode", WidgetType.ACTION, 0, 0, 4, 2, WidgetConfig.Action(AliControlCommands.OPEN_VSCODE)),
            DashboardWidget("dev_terminal", WidgetType.ACTION, 4, 0, 4, 2, WidgetConfig.Action(AliControlCommands.OPEN_TERMINAL)),
            DashboardWidget("dev_browser", WidgetType.ACTION, 8, 0, 4, 2, WidgetConfig.Action(AliControlCommands.OPEN_BROWSER)),

            DashboardWidget("dev_spotlight", WidgetType.ACTION, 0, 2, 4, 2, WidgetConfig.Action(AliControlCommands.SPOTLIGHT)),
            DashboardWidget("dev_finder", WidgetType.ACTION, 4, 2, 4, 2, WidgetConfig.Action(AliControlCommands.OPEN_FINDER)),
            DashboardWidget("dev_activity_monitor", WidgetType.ACTION, 8, 2, 4, 2, WidgetConfig.Action(AliControlCommands.OPEN_ACTIVITY_MONITOR)),

            DashboardWidget("dev_cpu", WidgetType.METRIC, 0, 4, 4, 4, WidgetConfig.Metric("CPU")),
            DashboardWidget("dev_ram", WidgetType.METRIC, 4, 4, 4, 4, WidgetConfig.Metric("RAM")),
            DashboardWidget("dev_screenshot", WidgetType.ACTION, 8, 4, 4, 2, WidgetConfig.Action(AliControlCommands.SCREENSHOT)),

            DashboardWidget("dev_system_settings", WidgetType.ACTION, 8, 6, 4, 2, WidgetConfig.Action(AliControlCommands.OPEN_SYSTEM_SETTINGS))
        )
    )
}
