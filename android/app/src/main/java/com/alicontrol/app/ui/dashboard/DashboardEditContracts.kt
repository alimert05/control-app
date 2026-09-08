package com.alicontrol.app.ui.dashboard

/**
 * What the renderer needs to know about editing: whether edit mode is on at
 * all, and which widget (if any) is currently selected and therefore showing
 * its remove badge and resize handle.
 */
data class DashboardEditState(
    val isEditing: Boolean = false,
    val selectedWidgetId: String? = null
) {
    fun isSelected(widgetId: String): Boolean = isEditing && selectedWidgetId == widgetId
}

/**
 * The edit operations a rendered widget can ask for. Implemented by
 * [com.alicontrol.app.ui.MainActivity], which routes each one through
 * [com.alicontrol.app.dashboard.DashboardEditor] — the view layer proposes,
 * the pure model layer decides.
 *
 * The `can*` calls exist so a drag can tint its preview live (green-ish for a
 * legal drop, red for an illegal one) using exactly the same validation the
 * commit will run, instead of a second, subtly different copy of the rules.
 */
interface DashboardEditListener {
    fun onWidgetTapped(widgetId: String)
    fun onRemoveWidget(widgetId: String)
    fun onMoveWidget(widgetId: String, x: Int, y: Int)
    fun onResizeWidget(widgetId: String, width: Int, height: Int)
    fun canMoveWidget(widgetId: String, x: Int, y: Int): Boolean
    fun canResizeWidget(widgetId: String, width: Int, height: Int): Boolean
}
