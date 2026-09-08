package com.alicontrol.app.dashboard

/**
 * The single logical grid every [DashboardPage] lays its [DashboardWidget]s
 * out on.
 *
 * TASK-008 replaces TASK-007's fine-grained 12x8 grid with a coarse
 * **6 columns x 4 rows** (24 logical placement cells). The coarser grid is
 * what makes hand-editing on a 7-inch tablet practical: one cell is a real,
 * thumb-sized target, so "drag this one cell to the right" is an achievable
 * gesture rather than a pixel-hunt. The 24 cells are placement units, not 24
 * mandatory buttons — pages are free to leave cells empty.
 */
object DashboardGrid {
    const val COLUMNS = 6
    const val ROWS = 4

    /** Total logical placement cells on one page. */
    const val CELLS = COLUMNS * ROWS
}
