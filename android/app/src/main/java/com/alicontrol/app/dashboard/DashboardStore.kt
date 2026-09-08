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

    /** The stored layout if one is present and valid, otherwise the shipped default. */
    fun load(): Dashboard = DashboardSerializer.deserialize(persistence.read()) ?: defaultFactory()

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
