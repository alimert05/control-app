package com.alicontrol.app.dashboard

/**
 * Where one widget sits on the [DashboardGrid], in whole cells. Pure value
 * type with no Android dependency: every move/resize/collision decision in
 * TASK-008's editor is made here (and unit-tested here) before any View is
 * touched.
 *
 * Coordinates are half-open rectangles — a widget occupies columns
 * `[x, x+width)` and rows `[y, y+height)`, so two widgets that merely touch
 * edges do not overlap.
 */
data class WidgetPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun isWithinBounds(columns: Int = DashboardGrid.COLUMNS, rows: Int = DashboardGrid.ROWS): Boolean =
        x >= 0 && y >= 0 && width >= 1 && height >= 1 && right <= columns && bottom <= rows

    fun overlaps(other: WidgetPlacement): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y

    fun movedTo(newX: Int, newY: Int): WidgetPlacement = copy(x = newX, y = newY)

    fun resizedTo(newWidth: Int, newHeight: Int): WidgetPlacement = copy(width = newWidth, height = newHeight)
}
