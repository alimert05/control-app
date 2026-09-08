package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.Platform

/**
 * One tile on a [DashboardPage]: what it is ([type]/[config]) and where it
 * sits ([placement], in [DashboardGrid] cells — never pixels).
 * [com.alicontrol.app.ui.dashboard.DashboardGridLayout] is what turns cells
 * into on-screen bounds, so a widget's model position *is* its rendered
 * position with no second source of truth to keep in sync.
 *
 * Immutable: TASK-008's editor moves/resizes by producing copies (see
 * [DashboardEditor]), which is what makes every layout operation trivially
 * unit-testable and makes an invalid intermediate state impossible to
 * persist.
 */
data class DashboardWidget(
    val id: String,
    val type: WidgetType,
    val placement: WidgetPlacement,
    val config: WidgetConfig
) {
    /** Convenience constructor for literal layouts (see [DefaultDashboardFactory]) and tests. */
    constructor(
        id: String,
        type: WidgetType,
        gridX: Int,
        gridY: Int,
        gridWidth: Int,
        gridHeight: Int,
        config: WidgetConfig
    ) : this(id, type, WidgetPlacement(gridX, gridY, gridWidth, gridHeight), config)

    init {
        require(placement.width > 0 && placement.height > 0) { "widget $id has non-positive size" }
        require(placement.x >= 0 && placement.y >= 0) { "widget $id has a negative grid position" }
        require(type.matches(config)) { "widget $id type $type does not match ${config.javaClass.simpleName}" }
    }

    val gridX: Int get() = placement.x
    val gridY: Int get() = placement.y
    val gridWidth: Int get() = placement.width
    val gridHeight: Int get() = placement.height
    val gridRight: Int get() = placement.right
    val gridBottom: Int get() = placement.bottom

    fun isWithinBounds(columns: Int = DashboardGrid.COLUMNS, rows: Int = DashboardGrid.ROWS): Boolean =
        placement.isWithinBounds(columns, rows)

    /** Half-open rectangle overlap — touching edges (e.g. one ends where another starts) is not an overlap. */
    fun overlaps(other: DashboardWidget): Boolean = placement.overlaps(other.placement)

    /** Whether this widget's size satisfies its own [WidgetType]'s min/max constraints. */
    fun satisfiesSizeConstraints(): Boolean = type.allows(placement)

    /**
     * Every [ActionLibrary] action id this widget can send, regardless of
     * [type] — the one place that knows how to dig an action id out of any
     * [WidgetConfig] variant. Used by the renderer (to wire clicks), by the
     * editor (to validate ids), and by tests.
     */
    fun referencedActionIds(): List<String> = when (config) {
        is WidgetConfig.Action -> listOf(config.actionId)
        is WidgetConfig.ActionGroup -> config.actionIds
        is WidgetConfig.Media -> listOf(config.previousActionId, config.playPauseActionId, config.nextActionId)
        is WidgetConfig.Slider -> config.actionIds
        is WidgetConfig.Metric, is WidgetConfig.Gauge, is WidgetConfig.Clock,
        is WidgetConfig.DailyOverview, is WidgetConfig.Shortcut -> emptyList()
    }

    /** Whether this widget's clicks are BLE commands that must be disabled while disconnected. */
    fun isRemoteControl(): Boolean = referencedActionIds().isNotEmpty()

    /**
     * Platform policy for the dashboard: a remote widget is enabled only when
     * every action it exposes supports [platform]. An unsupported action is
     * never removed from a saved layout — it simply renders disabled (see
     * [com.alicontrol.app.ui.dashboard.DashboardWidgetViewFactory]). Local and
     * informational widgets contain no action IDs and remain available.
     */
    fun isSupportedOn(platform: Platform): Boolean =
        referencedActionIds().all { ActionLibrary.find(it)?.supports(platform) == true }

    private fun WidgetType.matches(config: WidgetConfig): Boolean = when (this) {
        WidgetType.ACTION -> config is WidgetConfig.Action
        WidgetType.ACTION_GROUP -> config is WidgetConfig.ActionGroup
        WidgetType.METRIC -> config is WidgetConfig.Metric
        WidgetType.GAUGE -> config is WidgetConfig.Gauge
        WidgetType.CLOCK -> config is WidgetConfig.Clock
        WidgetType.DAILY_OVERVIEW -> config is WidgetConfig.DailyOverview
        WidgetType.MEDIA -> config is WidgetConfig.Media
        WidgetType.SLIDER -> config is WidgetConfig.Slider
        WidgetType.SHORTCUT -> config is WidgetConfig.Shortcut
    }
}
