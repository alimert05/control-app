package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.dashboard.DefaultDashboardFactory
import com.alicontrol.app.dashboard.EditRejection
import com.alicontrol.app.dashboard.EditResult
import com.alicontrol.app.dashboard.LayoutPersistence

/** An in-memory [LayoutPersistence]: the real store logic with no SharedPreferences or Context. */
internal class InMemoryLayoutPersistence(var stored: String? = null) : LayoutPersistence {
    var writes = 0
        private set

    override fun read(): String? = stored

    override fun write(json: String) {
        stored = json
        writes++
    }

    override fun clear() {
        stored = null
    }
}

/** The value of a successful edit; fails the test (with the reason) if it was rejected. */
internal fun <T> EditResult<T>.value(): T = when (this) {
    is EditResult.Success -> value
    is EditResult.Rejected -> throw AssertionError("expected success but was rejected: $reason")
}

/** The reason an edit was rejected; fails the test if it succeeded. */
internal fun EditResult<*>.rejection(): EditRejection = when (this) {
    is EditResult.Rejected -> reason
    is EditResult.Success -> throw AssertionError("expected a rejection but the edit succeeded")
}

/** Runs a `DashboardEditor` page edit against one page of a dashboard, as `MainActivity.applyPageEdit` does. */
internal fun Dashboard.editPage(pageId: String, edit: (DashboardPage) -> EditResult<DashboardPage>): EditResult<Dashboard> {
    val page = page(pageId) ?: return EditResult.Rejected(EditRejection.UNKNOWN_PAGE)
    return when (val result = edit(page)) {
        is EditResult.Success -> EditResult.Success(withPage(result.value))
        is EditResult.Rejected -> result
    }
}

/** Applies a `DashboardEditor` dashboard edit to one profile via [ProfileEditor.replaceDashboard]. */
internal fun ProfileCollection.editDashboard(
    profileId: String,
    edit: (Dashboard) -> EditResult<Dashboard>
): ProfileCollection {
    val current = profile(profileId)!!.dashboard
    return ProfileEditor.replaceDashboard(this, profileId, edit(current).value()).value()
}

/**
 * A user-customised pre-profile dashboard: the shipped default plus a "Gaming" page holding a
 * Spotify launcher — the kind of layout a TASK-008 user would already have on the tablet.
 */
internal fun customLegacyDashboard(): Dashboard {
    val withPage = DashboardEditor.createPage(DefaultDashboardFactory.create(), "Gaming").value()
    val gaming = DashboardEditor.addActionWidget(withPage.orderedPages.last(), AliControlCommands.OPEN_SPOTIFY).value()
    return withPage.withPage(gaming)
}

/** A pre-profile (macOS-era, schema 1) layout using three of the four retired action ids. */
internal val MAC_OS_ERA_LAYOUT_JSON = """
    {"schemaVersion":1,"pages":[{"id":"p","name":"Mine","order":0,"widgets":[
      {"id":"a","type":"ACTION","x":0,"y":0,"w":1,"h":1,"config":{"actionId":"spotlight"}},
      {"id":"b","type":"ACTION","x":1,"y":0,"w":2,"h":1,"config":{"actionId":"open_activity_monitor"}},
      {"id":"c","type":"ACTION","x":0,"y":1,"w":1,"h":1,"config":{"actionId":"mission_control"}},
      {"id":"d","type":"ACTION","x":1,"y":1,"w":1,"h":1,"config":{"actionId":"open_finder"}},
      {"id":"e","type":"ACTION","x":2,"y":1,"w":1,"h":1,"config":{"actionId":"lock_screen"}}
    ]}]}
""".trimIndent()
