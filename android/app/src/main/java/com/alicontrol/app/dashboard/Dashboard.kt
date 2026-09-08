package com.alicontrol.app.dashboard

/**
 * The whole dashboard: an ordered set of pages. Nothing here hardcodes a page
 * count — [pages] is a plain list, so [DefaultDashboardFactory] shipping 3
 * pages is that factory's choice, not a constraint of this model.
 * [DashboardEditor] creates, renames, deletes and reorders pages by producing
 * new [Dashboard] values; this class never changes for those operations.
 */
data class Dashboard(val pages: List<DashboardPage>) {

    /** Pages in display order, deterministic by [DashboardPage.order]. */
    val orderedPages: List<DashboardPage> get() = pages.sortedBy { it.order }

    fun page(pageId: String): DashboardPage? = pages.firstOrNull { it.id == pageId }

    fun indexOfPage(pageId: String): Int = orderedPages.indexOfFirst { it.id == pageId }

    /** Replaces one page in place, keeping every other page (and the ordering) untouched. */
    fun withPage(page: DashboardPage): Dashboard =
        copy(pages = pages.map { if (it.id == page.id) page else it })

    /** Rewrites [DashboardPage.order] to 0..n-1 following current display order — run after any page CRUD. */
    fun normalizedOrder(): Dashboard =
        copy(pages = orderedPages.mapIndexed { index, page -> page.copy(order = index) })

    fun isValid(): Boolean {
        if (pages.isEmpty()) return false
        val ids = pages.map { it.id }
        if (ids.size != ids.toSet().size) return false
        return pages.all { it.isValid() }
    }
}
