package com.alicontrol.app.dashboard

/** Where a [WidgetType.SHORTCUT] widget navigates. Local-only — never sent over BLE. */
enum class ShortcutDestination {
    DEVICE_SETTINGS,
    ALL_ACTIONS
}

/**
 * Per-widget-type configuration, kept as a sealed hierarchy rather than one
 * flat bag of nullable fields (or an untyped map) so every widget only
 * carries the data it actually needs and the compiler checks the renderer
 * handles all of them. [DashboardWidget] rejects any type/config mismatch at
 * construction, and [DashboardSerializer] round-trips each variant by name.
 *
 * Every action id referenced anywhere here is a raw
 * [com.alicontrol.app.ble.AliControlCommands] string — the widget layer never
 * defines its own action metadata, it only points at
 * [com.alicontrol.app.actions.ActionLibrary] entries by id, and
 * [DashboardEditor] refuses ids the library doesn't know.
 */
sealed class WidgetConfig {

    /** Sends one action from the library. */
    data class Action(val actionId: String) : WidgetConfig()

    /** A compact strip of closely related actions (e.g. transport controls, volume/mute). */
    data class ActionGroup(val actionIds: List<String>) : WidgetConfig()

    /** An honest-unavailable numeric readout (CPU/GPU/RAM %). No real telemetry — see TASK-009. */
    data class Metric(val label: String) : WidgetConfig()

    /** A visual gauge shell. No real telemetry — always renders its unavailable state. */
    data class Gauge(val label: String) : WidgetConfig()

    /** Local tablet time. [showDate] adds a small secondary date line. */
    data class Clock(val showDate: Boolean = true) : WidgetConfig()

    /**
     * TASK-008's combined at-a-glance panel: real local time and date, plus
     * honest unavailable placeholders for weather, calendar and next event.
     * [locationLabel] is a user-visible caption only — it fetches nothing and
     * is never sent anywhere (no weather/calendar integration exists yet).
     */
    data class DailyOverview(val locationLabel: String = "") : WidgetConfig()

    /** Now-playing shell: real transport controls, no metadata/artwork. */
    data class Media(val previousActionId: String, val playPauseActionId: String, val nextActionId: String) : WidgetConfig()

    /**
     * Visual foundation only. [actionIds] is reserved for a future two-way
     * synced slider (e.g. remote volume, which would need protocol support)
     * and is intentionally unused by the renderer — it never fakes a value.
     */
    data class Slider(val label: String, val actionIds: List<String> = emptyList()) : WidgetConfig()

    /** Local, non-BLE navigation entry point. */
    data class Shortcut(val label: String, val destination: ShortcutDestination) : WidgetConfig()
}
