package com.alicontrol.app.dashboard

/**
 * One page of the dashboard. [order] (not list position) is the source of
 * truth for display order — see [Dashboard.orderedPages] — so a future
 * TASK-008 reorder operation only has to change this field.
 */
data class DashboardPage(
    val id: String,
    val name: String,
    val order: Int,
    val widgets: List<DashboardWidget>
)
