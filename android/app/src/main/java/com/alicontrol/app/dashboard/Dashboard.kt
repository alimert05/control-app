package com.alicontrol.app.dashboard

/**
 * The whole dashboard: an ordered set of pages. Nothing here hardcodes a page
 * count — [pages] is a plain list, so [DefaultDashboardFactory] shipping 3
 * pages is a choice made by that factory, not a constraint of this model.
 * TASK-008 adding/removing/reordering pages only ever needs to produce a new
 * [Dashboard] value; this class itself never needs to change.
 */
data class Dashboard(val pages: List<DashboardPage>) {

    /** Pages in display order, deterministic by [DashboardPage.order]. */
    val orderedPages: List<DashboardPage> get() = pages.sortedBy { it.order }
}
