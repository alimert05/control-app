package com.alicontrol.app.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import com.alicontrol.app.R
import com.alicontrol.app.dashboard.DashboardPage

/**
 * Inflates `dashboard_page.xml` and fills it with every widget on [page].
 *
 * [bindings] collects the live-data hooks of whichever widgets on this page
 * display desktop state, so the adapter can push updates to exactly those
 * views and nothing else.
 */
class DashboardPageRenderer(private val widgetFactory: DashboardWidgetViewFactory) {

    fun render(
        inflater: LayoutInflater,
        parent: ViewGroup,
        page: DashboardPage,
        isConnected: Boolean,
        editState: DashboardEditState = DashboardEditState(),
        editListener: DashboardEditListener? = null,
        bindings: LiveWidgetBindings? = null
    ): DashboardGridLayout {
        val root = inflater.inflate(R.layout.dashboard_page, parent, false) as DashboardGridLayout
        root.showGridGuides = editState.isEditing
        for (widget in page.widgets) {
            root.addView(widgetFactory.build(widget, isConnected, editState, editListener, bindings))
        }
        return root
    }
}
