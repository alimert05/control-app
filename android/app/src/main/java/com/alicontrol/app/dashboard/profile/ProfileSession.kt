package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.Dashboard
import com.alicontrol.app.dashboard.EditResult

/**
 * The running app's view of the profiles: the current [ProfileCollection], kept in step
 * with storage. Every operation delegates the actual rule to [ProfileEditor] and, when it
 * succeeds, stores the result — so the UI never has to remember to save, and a switch,
 * an edit and a profile-manager action all persist the same way.
 *
 * This class is deliberately blind to everything live. It holds no reference to
 * `DesktopStateStore`, the BLE transport, the artwork cache or any Android type, which
 * is the structural reason a profile switch cannot reconnect, restart telemetry, clear
 * media state or drop artwork: there is nothing here that could. (A test asserts that
 * boundary — see `ProfileLiveStateTest`.)
 *
 * Edit Mode needs no special support: while editing, `MainActivity` feeds each
 * `DashboardEditor` result to [updateActiveDashboard], which stores it as the *active
 * profile's* dashboard and no other's. Every successful edit is therefore already
 * persisted, so leaving Edit Mode to switch profile can never discard anything.
 *
 * Main-thread only, like the rest of the UI state.
 */
class ProfileSession(
    private val store: ProfileStore,
    initial: ProfileCollection = store.load(),
    private val newId: () -> String = ProfileIds::generate
) {

    var collection: ProfileCollection = initial
        private set(value) {
            field = value
            active = value.activeProfile
        }

    // Resolved once per change rather than on every read: the UI reads `dashboard` on each touch
    // event while dragging, and resolving the active profile validates its whole dashboard.
    private var active: DashboardProfile = initial.activeProfile

    val activeProfile: DashboardProfile get() = active

    /** The dashboard being shown and edited: always `activeProfile.dashboard`. */
    val dashboard: Dashboard get() = activeProfile.dashboard

    /** Profiles in the order the switcher and the manager show them. */
    val orderedProfiles: List<DashboardProfile> get() = collection.orderedProfiles

    /** Stores [dashboard] as the active profile's own. Inactive profiles are not touched. */
    fun updateActiveDashboard(dashboard: Dashboard): EditResult<ProfileCollection> =
        commit(ProfileEditor.replaceDashboard(collection, activeProfile.id, dashboard))

    /**
     * Makes [profileId] the active profile and remembers it as the last selected one, so
     * the next launch opens it. Selecting the already active profile changes and writes nothing.
     */
    fun switchTo(profileId: String): EditResult<DashboardProfile> =
        commit(ProfileEditor.select(collection, profileId)).let { result ->
            when (result) {
                is EditResult.Success -> EditResult.Success(activeProfile)
                is EditResult.Rejected -> result
            }
        }

    /** Adds an empty custom profile. It is not selected — the caller decides whether to switch to it. */
    fun createProfile(name: String): EditResult<DashboardProfile> =
        commitAdded(ProfileEditor.createProfile(collection, name, newId))

    /** Adds an independent copy of [sourceId]. It is not selected. */
    fun duplicateProfile(sourceId: String): EditResult<DashboardProfile> =
        commitAdded(ProfileEditor.duplicateProfile(collection, sourceId, newId))

    fun renameProfile(profileId: String, name: String): EditResult<ProfileCollection> =
        commit(ProfileEditor.renameProfile(collection, profileId, name))

    fun moveProfile(profileId: String, offset: Int): EditResult<ProfileCollection> =
        commit(ProfileEditor.moveProfile(collection, profileId, offset))

    fun setStartupDefault(profileId: String): EditResult<ProfileCollection> =
        commit(ProfileEditor.setStartupDefault(collection, profileId))

    /** Restores one profile's layout (template for a built-in, empty for a custom one). */
    fun resetProfile(profileId: String): EditResult<ProfileCollection> =
        commit(ProfileEditor.resetProfile(collection, profileId))

    /** Deletes a custom profile; if it was active, [activeProfile] afterwards is the fallback. */
    fun deleteProfile(profileId: String): EditResult<ProfileCollection> =
        commit(ProfileEditor.deleteProfile(collection, profileId))

    private fun commit(result: EditResult<ProfileCollection>): EditResult<ProfileCollection> {
        if (result is EditResult.Success && result.value != collection) {
            collection = result.value
            store.save(collection)
        }
        return result
    }

    private fun commitAdded(result: EditResult<ProfileEditor.Added>): EditResult<DashboardProfile> = when (result) {
        is EditResult.Success -> {
            collection = result.value.collection
            store.save(collection)
            EditResult.Success(result.value.profile)
        }
        is EditResult.Rejected -> result
    }
}
