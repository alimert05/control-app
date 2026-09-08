package com.alicontrol.app.dashboard

import android.content.Context

/**
 * The real storage behind [DashboardStore]: one JSON string in its own small
 * [android.content.SharedPreferences] file.
 *
 * Deliberately a separate preferences file from `kiosk_prefs` so that Reset
 * to Default can remove the layout without touching unrelated settings like
 * keep-screen-awake. Writes use `apply()` (async, no fsync on the UI thread)
 * — a dropped write in a hard kill costs at most the last drag, and the
 * layout is re-saved on the next edit.
 */
class SharedPreferencesLayoutPersistence(context: Context) : LayoutPersistence {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_LAYOUT, null)

    override fun write(json: String) {
        prefs.edit().putString(KEY_LAYOUT, json).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_LAYOUT).apply()
    }

    private companion object {
        const val PREFS_NAME = "dashboard_prefs"
        const val KEY_LAYOUT = "dashboard_layout_json"
    }
}
