package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary

/** Why an edit was refused. Surfaced to the user as a short message, never as a silent no-op. */
enum class EditRejection {
    OUT_OF_BOUNDS,
    OVERLAP,
    SIZE_CONSTRAINT,
    NO_SPACE,
    UNKNOWN_WIDGET,
    UNKNOWN_PAGE,
    UNKNOWN_ACTION,
    INVALID_NAME,
    LAST_PAGE
}

/** Either the new value produced by an edit, or the reason it was refused. Never a half-applied state. */
sealed class EditResult<out T> {
    data class Success<T>(val value: T) : EditResult<T>()
    data class Rejected(val reason: EditRejection) : EditResult<Nothing>()

    fun valueOrNull(): T? = (this as? Success)?.value
    val isSuccess: Boolean get() = this is Success
}

/**
 * Every dashboard mutation TASK-008 supports, as pure functions over
 * immutable model values: move, resize, add and remove widgets; create,
 * rename, delete and reorder pages.
 *
 * Nothing here touches Android, Views, touch events or storage — the editor
 * UI translates a gesture into a candidate (x, y) or (width, height) and asks
 * this object whether it's legal. That keeps the actual layout rules (grid
 * bounds, collisions, per-type size limits, "can't delete the last page")
 * in one unit-tested place instead of spread across touch handlers, and
 * means an invalid layout can never reach the renderer or persistence.
 *
 * Rejections are values, not exceptions: an illegal drop leaves the widget
 * exactly where it was and tells the caller why.
 */
object DashboardEditor {

    const val MAX_PAGE_NAME_LENGTH = 24

    // -- Widget placement --

    /**
     * Moves [widgetId] so its top-left corner sits at ([x], [y]), keeping its
     * current size. Refused if that would leave the grid or overlap another
     * widget; the widget's own current cells never count as a collision.
     */
    fun moveWidget(page: DashboardPage, widgetId: String, x: Int, y: Int): EditResult<DashboardPage> {
        val widget = page.widget(widgetId) ?: return EditResult.Rejected(EditRejection.UNKNOWN_WIDGET)
        val candidate = widget.placement.movedTo(x, y)
        if (!candidate.isWithinBounds()) return EditResult.Rejected(EditRejection.OUT_OF_BOUNDS)
        if (!page.canPlace(candidate, ignoringWidgetId = widgetId)) return EditResult.Rejected(EditRejection.OVERLAP)
        return EditResult.Success(page.replacing(widget.copy(placement = candidate)))
    }

    /**
     * Resizes [widgetId] from its fixed top-left corner. Refused if the new
     * size breaks the widget type's own min/max, leaves the grid, or covers
     * cells another widget already occupies.
     */
    fun resizeWidget(page: DashboardPage, widgetId: String, width: Int, height: Int): EditResult<DashboardPage> {
        val widget = page.widget(widgetId) ?: return EditResult.Rejected(EditRejection.UNKNOWN_WIDGET)
        if (!widget.type.allowsSize(width, height)) return EditResult.Rejected(EditRejection.SIZE_CONSTRAINT)
        val candidate = widget.placement.resizedTo(width, height)
        if (!candidate.isWithinBounds()) return EditResult.Rejected(EditRejection.OUT_OF_BOUNDS)
        if (!page.canPlace(candidate, ignoringWidgetId = widgetId)) return EditResult.Rejected(EditRejection.OVERLAP)
        return EditResult.Success(page.replacing(widget.copy(placement = candidate)))
    }

    /** Removes a widget from this page only — never from [ActionLibrary], the macOS registry, or another page. */
    fun removeWidget(page: DashboardPage, widgetId: String): EditResult<DashboardPage> {
        if (page.widget(widgetId) == null) return EditResult.Rejected(EditRejection.UNKNOWN_WIDGET)
        return EditResult.Success(page.copy(widgets = page.widgets.filterNot { it.id == widgetId }))
    }

    /**
     * Adds a widget of [type] at its default size in the first free position
     * (scanning row by row, left to right, so placement is deterministic and
     * predictable rather than "wherever it fits"). Existing widgets are never
     * moved or dropped to make room: if nothing fits, this is refused with
     * [EditRejection.NO_SPACE].
     */
    fun addWidget(page: DashboardPage, type: WidgetType, config: WidgetConfig): EditResult<DashboardPage> {
        if (!isConfigKnown(config)) return EditResult.Rejected(EditRejection.UNKNOWN_ACTION)
        val placement = findFreePlacement(page, type.defaultWidth, type.defaultHeight)
            ?: return EditResult.Rejected(EditRejection.NO_SPACE)
        val widget = DashboardWidget(nextWidgetId(page, type), type, placement, config)
        return EditResult.Success(page.copy(widgets = page.widgets + widget))
    }

    /**
     * Adds an ACTION widget for [actionId]. The id must already exist in
     * [ActionLibrary] — the picker only ever offers library entries, and this
     * check makes an arbitrary/unknown action id impossible to smuggle into a
     * layout even through a hand-edited persisted file.
     */
    fun addActionWidget(page: DashboardPage, actionId: String): EditResult<DashboardPage> {
        if (ActionLibrary.find(actionId) == null) return EditResult.Rejected(EditRejection.UNKNOWN_ACTION)
        return addWidget(page, WidgetType.ACTION, WidgetConfig.Action(actionId))
    }

    /**
     * The first placement of [width] x [height] that fits, scanning row-major
     * from the top-left. Returns null when the page has no free rectangle
     * that size.
     */
    fun findFreePlacement(
        page: DashboardPage,
        width: Int,
        height: Int,
        columns: Int = DashboardGrid.COLUMNS,
        rows: Int = DashboardGrid.ROWS
    ): WidgetPlacement? {
        if (width < 1 || height < 1 || width > columns || height > rows) return null
        for (y in 0..(rows - height)) {
            for (x in 0..(columns - width)) {
                val candidate = WidgetPlacement(x, y, width, height)
                if (page.canPlace(candidate, columns = columns, rows = rows)) return candidate
            }
        }
        return null
    }

    /** Whether a widget of [type] could be added at all right now — drives the picker's "Not enough space" state. */
    fun hasSpaceFor(page: DashboardPage, type: WidgetType): Boolean =
        findFreePlacement(page, type.defaultWidth, type.defaultHeight) != null

    // -- Pages --

    /** Creates an empty page at the end of the order. Names are validated exactly like [renamePage]. */
    fun createPage(dashboard: Dashboard, name: String): EditResult<Dashboard> {
        val cleanName = sanitizePageName(name) ?: return EditResult.Rejected(EditRejection.INVALID_NAME)
        val page = DashboardPage(
            id = nextPageId(dashboard),
            name = cleanName,
            order = dashboard.pages.size,
            widgets = emptyList()
        )
        return EditResult.Success(dashboard.copy(pages = dashboard.pages + page).normalizedOrder())
    }

    /** Renames a page. The page's internal id — what widgets and persistence key off — never changes. */
    fun renamePage(dashboard: Dashboard, pageId: String, name: String): EditResult<Dashboard> {
        val page = dashboard.page(pageId) ?: return EditResult.Rejected(EditRejection.UNKNOWN_PAGE)
        val cleanName = sanitizePageName(name) ?: return EditResult.Rejected(EditRejection.INVALID_NAME)
        return EditResult.Success(dashboard.withPage(page.copy(name = cleanName)))
    }

    /** Deletes a page. The last remaining page is never deletable — the dashboard always has somewhere to be. */
    fun deletePage(dashboard: Dashboard, pageId: String): EditResult<Dashboard> {
        if (dashboard.page(pageId) == null) return EditResult.Rejected(EditRejection.UNKNOWN_PAGE)
        if (dashboard.pages.size <= 1) return EditResult.Rejected(EditRejection.LAST_PAGE)
        val remaining = dashboard.pages.filterNot { it.id == pageId }
        return EditResult.Success(dashboard.copy(pages = remaining).normalizedOrder())
    }

    /**
     * Moves a page [offset] positions in the display order (-1 = left,
     * +1 = right), clamped at the ends — moving the first page left is a
     * no-op success rather than an error, which is what a tapped arrow
     * should do.
     */
    fun movePage(dashboard: Dashboard, pageId: String, offset: Int): EditResult<Dashboard> {
        val ordered = dashboard.orderedPages.toMutableList()
        val index = ordered.indexOfFirst { it.id == pageId }
        if (index < 0) return EditResult.Rejected(EditRejection.UNKNOWN_PAGE)
        val target = (index + offset).coerceIn(0, ordered.size - 1)
        if (target == index) return EditResult.Success(dashboard)
        val page = ordered.removeAt(index)
        ordered.add(target, page)
        // Renumber from the new list positions directly: normalizedOrder()
        // would re-sort by the stale `order` values and undo the move.
        return EditResult.Success(Dashboard(ordered.mapIndexed { position, p -> p.copy(order = position) }))
    }

    /** Trimmed, length-capped name, or null when the user typed nothing usable. */
    fun sanitizePageName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_PAGE_NAME_LENGTH)
    }

    // -- Identity --

    /**
     * Widget ids are deterministic (`type_n`, first n that's free on the
     * page) rather than random UUIDs, so a serialize/deserialize round trip
     * and a test that adds the same widget twice both produce stable,
     * comparable results.
     */
    fun nextWidgetId(page: DashboardPage, type: WidgetType): String {
        val prefix = type.name.lowercase()
        val existing = page.widgets.map { it.id }.toSet()
        var index = 1
        while ("${prefix}_$index" in existing) index++
        return "${prefix}_$index"
    }

    fun nextPageId(dashboard: Dashboard): String {
        val existing = dashboard.pages.map { it.id }.toSet()
        var index = 1
        while ("page_custom_$index" in existing) index++
        return "page_custom_$index"
    }

    private fun DashboardPage.replacing(widget: DashboardWidget): DashboardPage =
        copy(widgets = widgets.map { if (it.id == widget.id) widget else it })

    /** Every action id a config references must be a real library entry. */
    private fun isConfigKnown(config: WidgetConfig): Boolean {
        val referenced = when (config) {
            is WidgetConfig.Action -> listOf(config.actionId)
            is WidgetConfig.ActionGroup -> config.actionIds
            is WidgetConfig.Media -> listOf(config.previousActionId, config.playPauseActionId, config.nextActionId)
            is WidgetConfig.Slider -> config.actionIds
            else -> emptyList()
        }
        if (config is WidgetConfig.ActionGroup && config.actionIds.isEmpty()) return false
        return referenced.all { ActionLibrary.find(it) != null }
    }
}
