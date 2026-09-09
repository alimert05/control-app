package com.alicontrol.app.ui.dashboard

import com.alicontrol.app.live.DesktopState

/**
 * The live-data update hooks for one rendered dashboard page.
 *
 * A widget that shows live values registers a small closure here while it is
 * being built; the page's adapter then pushes each new [DesktopState] through
 * all of them. This is what keeps the design free of the things TASK-009
 * rules out — no widget subscribes to the transport, none parses a payload,
 * and none owns a timer. There is one state source, and these closures are
 * how it reaches the views.
 *
 * A registry belongs to one bound page view and is cleared when that view is
 * rebuilt or recycled, so closures can never outlive the views they write to.
 */
class LiveWidgetBindings {

    private val binders = mutableListOf<(DesktopState) -> Unit>()

    fun register(binder: (DesktopState) -> Unit) {
        binders.add(binder)
    }

    fun apply(state: DesktopState) {
        for (binder in binders) binder(state)
    }

    fun clear() {
        binders.clear()
    }

    val isEmpty: Boolean get() = binders.isEmpty()
}
