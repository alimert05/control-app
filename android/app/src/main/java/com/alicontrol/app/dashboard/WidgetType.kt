package com.alicontrol.app.dashboard

/**
 * The reusable widget types the dashboard renderer knows how to draw, each
 * carrying its own min/max size in [DashboardGrid] cells.
 *
 * The constraints exist so the editor can never leave a widget in a state
 * where its own content stops being readable or tappable — a 1x1 MEDIA
 * widget has nowhere to put three transport buttons, and a 6x4 single ACTION
 * button is just wasted space. [DashboardEditor] enforces these on every add,
 * move and resize; nothing in the UI layer re-decides them.
 *
 * [SHORTCUT] is an Ali Control addition beyond the task's list — local,
 * non-BLE navigation (Device/Settings, All Actions) that can live in the grid
 * rather than only in the header. It never touches
 * [com.alicontrol.app.actions.ActionLibrary] or the transport.
 */
enum class WidgetType(
    val minWidth: Int,
    val minHeight: Int,
    val maxWidth: Int,
    val maxHeight: Int
) {
    ACTION(1, 1, 2, 2),
    ACTION_GROUP(2, 1, 6, 2),
    METRIC(1, 1, 2, 2),
    GAUGE(1, 1, 2, 2),
    CLOCK(2, 1, 4, 2),
    DAILY_OVERVIEW(3, 2, 6, 4),
    MEDIA(3, 2, 6, 4),
    SLIDER(2, 1, 6, 2),
    SHORTCUT(1, 1, 2, 2);

    /** The size a freshly added widget of this type gets — its minimum, which always fits if anything does. */
    val defaultWidth: Int get() = minWidth
    val defaultHeight: Int get() = minHeight

    fun allowsSize(width: Int, height: Int): Boolean =
        width in minWidth..maxWidth && height in minHeight..maxHeight

    fun allows(placement: WidgetPlacement): Boolean = allowsSize(placement.width, placement.height)

    /** Clamps an arbitrary (e.g. mid-drag) size into this type's allowed range. */
    fun clampWidth(width: Int): Int = width.coerceIn(minWidth, maxWidth)

    fun clampHeight(height: Int): Int = height.coerceIn(minHeight, maxHeight)
}
