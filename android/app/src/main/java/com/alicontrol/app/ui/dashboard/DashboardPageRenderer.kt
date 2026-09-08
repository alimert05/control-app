package com.alicontrol.app.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import com.alicontrol.app.R
import com.alicontrol.app.dashboard.DashboardPage

/** Inflates `dashboard_page.xml` and fills it with every widget on [page]. */
class DashboardPageRenderer(private val widgetFactory: DashboardWidgetViewFactory) {

    fun render(
        inflater: LayoutInflater,
        parent: ViewGroup,
        page: DashboardPage,
        isConnected: Boolean,
        editState: DashboardEditState = DashboardEditState(),
        editListener: DashboardEditListener? = null
    ): DashboardGridLayout {
        val root = inflater.inflate(R.layout.dashboard_page, parent, false) as DashboardGridLayout
        root.showGridGuides = editState.isEditing
        for (widget in page.widgets) {
            root.addView(widgetFactory.build(widget, isConnected, editState, editListener))
        }
        return root
    }
}
