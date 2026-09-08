package com.alicontrol.app.dashboard

/**
 * The single logical grid every [DashboardPage] lays its [DashboardWidget]s
 * out on. 12x8 gives enough resolution for the size variety TASK-007 asks for
 * (2x2 small buttons up to 8x4/12x4 media widgets) while staying a stable,
 * easy-to-reason-about unit for TASK-008's future editor to target.
 */
object DashboardGrid {
    const val COLUMNS = 12
    const val ROWS = 8
}
