package com.alicontrol.app.dashboard

/**
 * Where a serialized dashboard lives. Abstracted so [DashboardStore]'s
 * load/save/fallback logic is plain Kotlin that unit tests can drive with an
 * in-memory implementation — no Android framework, no Robolectric.
 */
interface LayoutPersistence {
    fun read(): String?
    fun write(json: String)
    fun clear()
}

/**
 * Owns the user's dashboard layout: loads it, saves every edit, and restores
 * the shipped default on request.
 *
 * Two rules matter here:
 *
 * 1. **A stored layout wins over the shipped default.** Once the user has
 *    customized anything, a later app update's new default layout must not
 *    silently replace their work — [load] only falls back to
 *    [DefaultDashboardFactory] when nothing valid is stored.
 * 2. **Invalid stored state degrades to defaults, never to a crash.**
 *    [DashboardSerializer] returns null for anything it can't fully validate
 *    (corrupt JSON, wrong schema version, overlapping or out-of-bounds
 *    widgets, unknown action ids), and that path is treated exactly like
 *    "no layout saved yet".
 */
class DashboardStore(
    private val persistence: LayoutPersistence,
    private val defaultFactory: () -> Dashboard = { DefaultDashboardFactory.create() }
) {

    /**
     * The stored layout if one is present and valid, otherwise the shipped default.
     *
     * A layout saved by an older build is migrated on the way in (retired action
     * ids become their Windows equivalents — see [ActionIdMigration]) and the
     * upgraded document is written straight back, so the migration runs once and
     * every later load reads the current schema. Nothing the user placed is lost.
     */
    fun load(): Dashboard {
        val loaded = DashboardSerializer.deserializeWithInfo(persistence.read()) ?: return defaultFactory()
        if (loaded.wasMigrated) save(loaded.dashboard)
        return loaded.dashboard
    }

    /**
     * The stored layout if one is present and valid — older schemas migrated in
     * memory — or null. Unlike [load] it never substitutes the default and never
     * writes anything back.
     *
     * TASK-010 reads the pre-profile single layout through this, once, to turn it
     * into the Default profile (see
     * [com.alicontrol.app.dashboard.profile.ProfileStore]); the stored string is
     * left exactly as it was.
     */
    fun loadStored(): Dashboard? = DashboardSerializer.deserializeWithInfo(persistence.read())?.dashboard

    /** Whether a valid user layout is currently stored (as opposed to running on the defaults). */
    fun hasStoredLayout(): Boolean = DashboardSerializer.deserialize(persistence.read()) != null

    fun save(dashboard: Dashboard) {
        persistence.write(DashboardSerializer.serialize(dashboard))
    }

    /**
     * Reset to Default: drops **only** the stored dashboard layout and
     * returns the shipped one. Nothing else the app persists (keep-awake,
     * paired device, any other preference) is touched — this deliberately
     * clears one key, not a whole preferences file.
     */
    fun resetToDefault(): Dashboard {
        persistence.clear()
        return defaultFactory()
    }
}
