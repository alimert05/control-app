package com.alicontrol.app.dashboard

/**
 * The reusable widget types the dashboard renderer knows how to draw.
 *
 * [SHORTCUT] is one addition beyond TASK-007's minimum list (ACTION through
 * SLIDER) — it exists purely for local, non-BLE navigation (Device/Settings,
 * All Actions) that the Page 1 mock places directly in the grid rather than
 * only in the header bar. It never touches [com.alicontrol.app.actions.ActionLibrary]
 * or the transport.
 */
enum class WidgetType {
    ACTION,
    ACTION_GROUP,
    METRIC,
    GAUGE,
    CLOCK,
    MEDIA,
    SLIDER,
    SHORTCUT
}
