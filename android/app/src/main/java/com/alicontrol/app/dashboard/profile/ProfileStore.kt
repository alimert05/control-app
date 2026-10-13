package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.DashboardStore
import com.alicontrol.app.dashboard.LayoutPersistence

/**
 * Loads and saves the [ProfileCollection]: the profile document through the same
 * [LayoutPersistence] abstraction [DashboardStore] uses (one JSON string in the existing
 * `dashboard_prefs` SharedPreferences file — no database), and, exactly once, the
 * pre-profile single dashboard through [legacyLayout].
 *
 * **Load order**
 *
 * 1. A readable profile document wins ([Source.STORED]). It is repaired in memory
 *    (see [ProfileSerializer]) but loading never writes, so a damaged entry is not
 *    "fixed" on disk behind the user's back — the repaired state is stored by the next
 *    ordinary save.
 * 2. Otherwise, if a valid pre-profile layout is stored, it **becomes the Default
 *    profile's dashboard**, the shipped Dev, Game and Media templates are added, and
 *    the result is saved at once ([Source.MIGRATED_FROM_LEGACY_LAYOUT]). Action ids from
 *    schema-1 layouts are migrated to their Windows equivalents on the way
 *    (`spotlight` → `windows_search`, ...), by the same reader as before.
 * 3. Otherwise — a fresh install, or nothing readable anywhere — the shipped templates
 *    are used ([Source.SHIPPED_DEFAULTS]) and nothing is written until the first change.
 *
 * **The migration runs once and is restart-safe.** Step 2 only happens while no readable
 * profile document exists; the save in step 2 creates one, so every later launch takes
 * step 1. The legacy string is never modified or removed: a layout the user built stays
 * recoverable on disk, an older build still finds its own data, and a corrupt legacy value
 * simply yields shipped defaults instead of being "cleaned up".
 *
 * Every path returns a collection that contains all four built-in profiles and a
 * resolvable selection, so callers never handle an empty or half-valid state.
 */
class ProfileStore(
    private val persistence: LayoutPersistence,
    private val legacyLayout: DashboardStore? = null
) {

    /** Where [load]'s result came from — mostly for tests and diagnostics. */
    enum class Source { STORED, MIGRATED_FROM_LEGACY_LAYOUT, SHIPPED_DEFAULTS }

    data class Loaded(val collection: ProfileCollection, val source: Source)

    fun load(): ProfileCollection = loadWithSource().collection

    fun loadWithSource(): Loaded {
        ProfileSerializer.deserialize(persistence.read())?.let { return Loaded(it, Source.STORED) }

        val legacy = legacyLayout?.loadStored()
        if (legacy != null) {
            val migrated = BuiltInProfiles.initialCollection(defaultDashboard = legacy)
            save(migrated)
            return Loaded(migrated, Source.MIGRATED_FROM_LEGACY_LAYOUT)
        }
        return Loaded(BuiltInProfiles.initialCollection(), Source.SHIPPED_DEFAULTS)
    }

    /** Whether a readable profile document is currently stored (as opposed to running on defaults). */
    fun hasStoredProfiles(): Boolean = ProfileSerializer.deserialize(persistence.read()) != null

    fun save(collection: ProfileCollection) {
        persistence.write(ProfileSerializer.serialize(collection))
    }
}
