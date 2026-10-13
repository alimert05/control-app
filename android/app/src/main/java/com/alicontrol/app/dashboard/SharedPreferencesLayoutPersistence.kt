package com.alicontrol.app.dashboard

import android.content.Context

/**
 * The real storage behind [DashboardStore] and
 * [com.alicontrol.app.dashboard.profile.ProfileStore]: one JSON string under
 * one key of a small [android.content.SharedPreferences] file.
 *
 * Deliberately a separate preferences file from `kiosk_prefs` so that a reset
 * can remove a layout without touching unrelated settings like
 * keep-screen-awake. Writes use `apply()` (async, no fsync on the UI thread)
 * — a dropped write in a hard kill costs at most the last drag, and the
 * layout is re-saved on the next edit.
 *
 * Two keys live in the file (TASK-010):
 *
 * - [LEGACY_LAYOUT_KEY] — the single pre-profile dashboard written by TASK-008
 *   and TASK-009 builds. It is only ever *read*, once, to migrate it into the
 *   Default profile; it is never rewritten or removed afterwards.
 * - [PROFILES_KEY] — the profile document (schema 3), which owns every
 *   dashboard from then on.
 *
 * Each instance reads and writes exactly one of them; [clear] removes only that
 * key.
 */
class SharedPreferencesLayoutPersistence(
    context: Context,
    private val key: String = LEGACY_LAYOUT_KEY
) : LayoutPersistence {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(key, null)

    override fun write(json: String) {
        prefs.edit().putString(key, json).apply()
    }

    override fun clear() {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val PREFS_NAME = "dashboard_prefs"

        /** The pre-profile single dashboard. Read for migration only. */
        const val LEGACY_LAYOUT_KEY = "dashboard_layout_json"

        /** The profile document: profiles, their dashboards, last selected and startup default. */
        const val PROFILES_KEY = "dashboard_profiles_json"
    }
}
