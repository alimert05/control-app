package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.Dashboard

/**
 * Every profile the tablet holds, plus the two ids that decide which one is
 * showing:
 *
 * - [lastSelectedProfileId] — the profile the user was on. It is rewritten on every
 *   switch and is what the app restores at startup, so it is also "the active
 *   profile" while the app runs.
 * - [defaultProfileId] — the user's chosen *startup default*, a fallback target used
 *   only when the last selected profile cannot be restored. It is not the same thing
 *   as the built-in profile whose id happens to be `default` ([ProfileIds.DEFAULT]),
 *   although a fresh install points it there.
 *
 * Like [Dashboard], this is a plain immutable value; [ProfileEditor] produces new
 * ones and [ProfileStore] persists them.
 */
data class ProfileCollection(
    val profiles: List<DashboardProfile>,
    val lastSelectedProfileId: String,
    val defaultProfileId: String
) {

    /** Profiles in display order, deterministic by [DashboardProfile.order] (ties keep list order). */
    val orderedProfiles: List<DashboardProfile> get() = profiles.sortedBy { it.order }

    fun profile(profileId: String): DashboardProfile? = profiles.firstOrNull { it.id == profileId }

    /**
     * The profile being shown: the last selected one, or — if that id no longer
     * names a usable profile — the startup fallback ([resolve]). Never null for a
     * non-empty collection, so a damaged id can never leave the UI with nothing to render.
     */
    val activeProfile: DashboardProfile
        get() = profile(resolve(lastSelectedProfileId)) ?: orderedProfiles.first()

    /** Replaces one profile, leaving every other profile (and the ordering) untouched. */
    fun withProfile(profile: DashboardProfile): ProfileCollection =
        copy(profiles = profiles.map { if (it.id == profile.id) profile else it })

    /**
     * The startup / fallback resolution, in this exact order:
     *
     * 1. [preferredId] (normally the last selected profile),
     * 2. the configured startup default ([defaultProfileId]),
     * 3. the built-in `default` profile,
     * 4. the first valid profile in display order.
     *
     * A candidate counts only if it names a profile that [DashboardProfile.isValid].
     * Returns the id of the winning profile; for an empty (or entirely invalid)
     * collection there is nothing to choose and [preferredId] comes back unchanged.
     */
    fun resolve(preferredId: String?): String {
        val usable = { id: String? -> id != null && profile(id)?.isValid() == true }
        return when {
            usable(preferredId) -> preferredId!!
            usable(defaultProfileId) -> defaultProfileId
            usable(ProfileIds.DEFAULT) -> ProfileIds.DEFAULT
            else -> orderedProfiles.firstOrNull { it.isValid() }?.id ?: preferredId.orEmpty()
        }
    }

    /**
     * Repairs the invariants a stored or hand-built collection can violate, without
     * touching any profile's dashboard:
     *
     * - duplicate ids collapse to the first profile (in display order) with that id;
     * - each profile's built-in flag follows its id, so a stored flag can neither
     *   make a custom profile undeletable nor strip a built-in's protection;
     * - [DashboardProfile.order] is renumbered 0..n-1 in display order;
     * - the last-selected and startup-default ids are resolved through [resolve]
     *   (the startup default falls back through the same chain, skipping itself).
     *
     * Idempotent: normalizing a normalized collection returns an equal one.
     */
    fun normalized(): ProfileCollection {
        val unique = orderedProfiles.distinctBy { it.id }
            .map { it.copy(builtIn = ProfileIds.isBuiltIn(it.id)) }
            .mapIndexed { index, profile -> profile.copy(order = index) }
        val base = copy(profiles = unique)
        // The default must be settled first: the last-selected fallback consults it.
        val settledDefault = base.copy(
            defaultProfileId = base.fallbackFor(base.defaultProfileId)
        )
        return settledDefault.copy(
            lastSelectedProfileId = settledDefault.resolve(settledDefault.lastSelectedProfileId)
        )
    }

    /** Validity of the whole collection — used by tests and as a guard before anything is rendered. */
    fun isValid(): Boolean {
        if (profiles.isEmpty()) return false
        if (profiles.map { it.id }.toSet().size != profiles.size) return false
        if (!profiles.all { it.isValid() }) return false
        return profile(lastSelectedProfileId) != null && profile(defaultProfileId) != null
    }

    /** [candidate] if it names a usable profile, otherwise the built-in default, otherwise the first valid profile. */
    private fun fallbackFor(candidate: String): String {
        val usable = { id: String -> profile(id)?.isValid() == true }
        return when {
            usable(candidate) -> candidate
            usable(ProfileIds.DEFAULT) -> ProfileIds.DEFAULT
            else -> orderedProfiles.firstOrNull { it.isValid() }?.id ?: candidate
        }
    }
}
