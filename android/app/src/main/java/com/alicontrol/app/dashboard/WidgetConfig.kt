package com.alicontrol.app.dashboard

/** Where a [WidgetType.SHORTCUT] widget navigates. Local-only — never sent over BLE. */
enum class ShortcutDestination {
    DEVICE_SETTINGS,
    ALL_ACTIONS
}

/**
 * Per-widget-type configuration. A widget's [WidgetType] and its [WidgetConfig]
 * subtype always agree (enforced by [com.alicontrol.app.dashboard.DashboardWidget]'s
 * factory helpers, not by the compiler) — this stays a sealed class rather than
 * one flat bag of nullable fields so every widget only carries the data it
 * actually needs.
 *
 * Every action id referenced anywhere here is a raw [com.alicontrol.app.ble.AliControlCommands]
 * string — the widget layer never defines its own action metadata, it only
 * points at [com.alicontrol.app.actions.ActionLibrary] entries by id.
 */
sealed class WidgetConfig {

    /** Sends one action from the library. */
    data class Action(val actionId: String) : WidgetConfig()

    /** A compact strip of closely related actions (e.g. transport controls, volume/mute). */
    data class ActionGroup(val actionIds: List<String>) : WidgetConfig()

    /** An honest-unavailable numeric readout (CPU/GPU/RAM %). No real telemetry in TASK-007. */
    data class Metric(val label: String) : WidgetConfig()

    /** A visual gauge shell. No real telemetry in TASK-007 — always renders disabled/unavailable. */
    data class Gauge(val label: String) : WidgetConfig()

    /** Local tablet time. [showDate] adds a small secondary date line. */
    data class Clock(val showDate: Boolean = true) : WidgetConfig()

    /** Now-playing shell: real transport controls, no metadata/artwork. */
    data class Media(val previousActionId: String, val playPauseActionId: String, val nextActionId: String) : WidgetConfig()

    /**
     * Visual foundation only (TASK-007 scope). [actionIds] is reserved for a
     * future two-way-synced slider (e.g. remote volume) and is intentionally
     * unused by the renderer today — rendering never fakes a value.
     */
    data class Slider(val label: String, val actionIds: List<String> = emptyList()) : WidgetConfig()

    /** Local, non-BLE navigation entry point. */
    data class Shortcut(val label: String, val destination: ShortcutDestination) : WidgetConfig()
}
