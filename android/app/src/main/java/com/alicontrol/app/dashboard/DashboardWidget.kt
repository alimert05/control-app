package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.Platform

/**
 * One tile on a [DashboardPage]. Position/size are in [DashboardGrid] cell
 * units, not pixels — [com.alicontrol.app.ui.dashboard.DashboardGridLayout]
 * is what turns these into real on-screen bounds. This layout metadata is
 * exactly what TASK-008's future editor will read and mutate; TASK-007 only
 * ever constructs it from static data (see [DefaultDashboardFactory]).
 */
data class DashboardWidget(
    val id: String,
    val type: WidgetType,
    val gridX: Int,
    val gridY: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val config: WidgetConfig
) {
    init {
        require(gridWidth > 0 && gridHeight > 0) { "widget $id has non-positive size" }
        require(gridX >= 0 && gridY >= 0) { "widget $id has a negative grid position" }
        require(type.matches(config)) { "widget $id type $type does not match ${config.javaClass.simpleName}" }
    }

    val gridRight: Int get() = gridX + gridWidth
    val gridBottom: Int get() = gridY + gridHeight

    fun isWithinBounds(columns: Int = DashboardGrid.COLUMNS, rows: Int = DashboardGrid.ROWS): Boolean =
        gridX >= 0 && gridY >= 0 && gridRight <= columns && gridBottom <= rows

    /** Half-open rectangle overlap — touching edges (e.g. one ends where another starts) is not an overlap. */
    fun overlaps(other: DashboardWidget): Boolean =
        gridX < other.gridRight && gridRight > other.gridX &&
            gridY < other.gridBottom && gridBottom > other.gridY

    /**
     * Every [com.alicontrol.app.actions.ActionLibrary] action id this widget
     * can send, regardless of [type] — the one place that knows how to dig an
     * action id out of any [WidgetConfig] variant. Used both by the renderer
     * (to wire clicks) and by tests (to verify every id is a real library
     * entry and every default action widget has icon coverage).
     */
    fun referencedActionIds(): List<String> = when (config) {
        is WidgetConfig.Action -> listOf(config.actionId)
        is WidgetConfig.ActionGroup -> config.actionIds
        is WidgetConfig.Media -> listOf(config.previousActionId, config.playPauseActionId, config.nextActionId)
        is WidgetConfig.Slider -> config.actionIds
        is WidgetConfig.Metric, is WidgetConfig.Gauge, is WidgetConfig.Clock, is WidgetConfig.Shortcut -> emptyList()
    }

    /** Whether this widget's clicks are BLE commands that must be disabled while disconnected. */
    fun isRemoteControl(): Boolean = referencedActionIds().isNotEmpty()

    /**
     * Platform policy for the fixed-grid dashboard: a remote widget is
     * enabled only when every action it exposes supports [platform]. Local
     * and informational widgets contain no action IDs and remain available.
     */
    fun isSupportedOn(platform: Platform): Boolean =
        referencedActionIds().all { ActionLibrary.find(it)?.supports(platform) == true }

    private fun WidgetType.matches(config: WidgetConfig): Boolean = when (this) {
        WidgetType.ACTION -> config is WidgetConfig.Action
        WidgetType.ACTION_GROUP -> config is WidgetConfig.ActionGroup
        WidgetType.METRIC -> config is WidgetConfig.Metric
        WidgetType.GAUGE -> config is WidgetConfig.Gauge
        WidgetType.CLOCK -> config is WidgetConfig.Clock
        WidgetType.MEDIA -> config is WidgetConfig.Media
        WidgetType.SLIDER -> config is WidgetConfig.Slider
        WidgetType.SHORTCUT -> config is WidgetConfig.Shortcut
    }
}
