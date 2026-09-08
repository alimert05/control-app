package com.alicontrol.app.dashboard

/**
 * One page of the dashboard: a name and the widgets placed on its own
 * independent [DashboardGrid]. [order] (not list position) is the source of
 * truth for display order — see [Dashboard.orderedPages] — so reordering only
 * has to rewrite this field.
 */
data class DashboardPage(
    val id: String,
    val name: String,
    val order: Int,
    val widgets: List<DashboardWidget>
) {

    fun widget(widgetId: String): DashboardWidget? = widgets.firstOrNull { it.id == widgetId }

    /**
     * Whether [placement] is a legal home on this page: inside the grid and
     * not overlapping anything except (optionally) the widget being moved or
     * resized, which must not collide with its own current cells.
     */
    fun canPlace(
        placement: WidgetPlacement,
        ignoringWidgetId: String? = null,
        columns: Int = DashboardGrid.COLUMNS,
        rows: Int = DashboardGrid.ROWS
    ): Boolean {
        if (!placement.isWithinBounds(columns, rows)) return false
        return widgets.none { it.id != ignoringWidgetId && it.placement.overlaps(placement) }
    }

    /** Every structural rule a page must satisfy — used by the editor, the loader and tests alike. */
    fun isValid(columns: Int = DashboardGrid.COLUMNS, rows: Int = DashboardGrid.ROWS): Boolean {
        if (id.isBlank() || name.isBlank()) return false
        val ids = widgets.map { it.id }
        if (ids.size != ids.toSet().size) return false
        if (widgets.any { !it.isWithinBounds(columns, rows) || !it.satisfiesSizeConstraints() }) return false
        widgets.forEachIndexed { index, widget ->
            for (other in widgets.drop(index + 1)) {
                if (widget.overlaps(other)) return false
            }
        }
        return true
    }

    /** Cells not covered by any widget — the search space for automatic placement. */
    fun freeCellCount(columns: Int = DashboardGrid.COLUMNS, rows: Int = DashboardGrid.ROWS): Int =
        columns * rows - widgets.sumOf { it.gridWidth * it.gridHeight }
}
