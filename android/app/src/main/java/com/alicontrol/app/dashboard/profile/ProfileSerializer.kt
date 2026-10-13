package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.DashboardSerializer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a [ProfileCollection] into the profile document and back.
 *
 * Document shape (schema 3):
 *
 * ```
 * {
 *   "schemaVersion": 3,
 *   "lastSelectedProfileId": "dev",
 *   "defaultProfileId": "default",
 *   "profiles": [
 *     { "id": "default", "name": "Default", "builtIn": true, "order": 0,
 *       "dashboard": { "schemaVersion": 2, "pages": [ ... ] } },
 *     ...
 *   ]
 * }
 * ```
 *
 * Each `dashboard` is exactly the object [DashboardSerializer] already writes, and
 * is read back by the same strict reader — profiles add structure *around* the
 * existing dashboard format and define no second one. That also keeps the
 * macOS-era action-id migration working for any embedded dashboard that still
 * carries the old schema.
 *
 * **Schema history.** 1 and 2 are the single-dashboard documents (see
 * [DashboardSerializer.SCHEMA_VERSION]); 3 is this profile document. A document
 * from a newer build is refused rather than guessed at. Unknown keys are ignored,
 * so a later build can add fields (a theme id, say) without breaking this reader.
 *
 * **Recovery is per profile.** The document as a whole is accepted as long as its
 * version and `profiles` array are readable; after that every entry stands on its own:
 *
 * - an entry that is not an object, has no usable id, or repeats an earlier id is dropped;
 * - a *custom* profile whose dashboard is missing or invalid (bad placement, overlap,
 *   unknown widget or action id, ...) is dropped — its layout cannot be trusted;
 * - a *built-in* profile in the same state is restored from its shipped template (built-ins
 *   must always exist), keeping its stored name and position;
 * - a blank or missing name falls back to the built-in's own name, or "Profile";
 * - the stored `builtIn` flag is advisory — it is recomputed from the id, so a stored
 *   flag cannot make a custom profile undeletable or strip a built-in's protection;
 * - missing built-ins are added from their templates, and stale or unknown last-selected /
 *   startup-default ids resolve through the normal startup policy.
 *
 * So one damaged profile never costs the user the others. Only a document that cannot
 * be read at all returns null, and [ProfileStore] then falls back.
 */
object ProfileSerializer {

    /** The profile document's own version (the single-dashboard documents are 1 and 2). */
    const val SCHEMA_VERSION = 3

    /** The oldest profile-document version this build can read. */
    const val OLDEST_SUPPORTED_SCHEMA_VERSION = 3

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_LAST_SELECTED = "lastSelectedProfileId"
    private const val KEY_DEFAULT = "defaultProfileId"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_BUILT_IN = "builtIn"
    private const val KEY_ORDER = "order"
    private const val KEY_DASHBOARD = "dashboard"

    private const val FALLBACK_CUSTOM_NAME = "Profile"

    fun serialize(collection: ProfileCollection): String {
        val profiles = JSONArray()
        for (profile in collection.orderedProfiles) {
            profiles.put(
                JSONObject()
                    .put(KEY_ID, profile.id)
                    .put(KEY_NAME, profile.name)
                    .put(KEY_BUILT_IN, profile.builtIn)
                    .put(KEY_ORDER, profile.order)
                    .put(KEY_DASHBOARD, DashboardSerializer.toJson(profile.dashboard))
            )
        }
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .put(KEY_LAST_SELECTED, collection.lastSelectedProfileId)
            .put(KEY_DEFAULT, collection.defaultProfileId)
            .put(KEY_PROFILES, profiles)
            .toString()
    }

    /**
     * The stored collection, repaired per the rules above — always containing every
     * built-in profile, a resolvable selection and a resolvable startup default — or
     * null when [json] is not a readable profile document at all (absent, not JSON,
     * a version this build does not read, or no `profiles` array).
     */
    fun deserialize(json: String?): ProfileCollection? {
        if (json.isNullOrBlank()) return null
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val version = root.optInt(KEY_SCHEMA_VERSION, -1)
        if (version !in OLDEST_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) return null
        val entries = root.optJSONArray(KEY_PROFILES) ?: return null

        val seen = HashSet<String>()
        val profiles = ArrayList<DashboardProfile>(entries.length())
        for (index in 0 until entries.length()) {
            val profile = parseProfile(entries.optJSONObject(index), index) ?: continue
            if (seen.add(profile.id)) profiles.add(profile)
        }

        val stored = ProfileCollection(
            profiles = profiles,
            lastSelectedProfileId = root.optString(KEY_LAST_SELECTED, ""),
            defaultProfileId = root.optString(KEY_DEFAULT, "")
        )
        return BuiltInProfiles.complete(stored)
    }

    /** One entry, recovered independently of every other. Null means the entry cannot be salvaged. */
    private fun parseProfile(entry: JSONObject?, index: Int): DashboardProfile? {
        if (entry == null) return null
        val id = (entry.opt(KEY_ID) as? String)?.trim().orEmpty()
        if (id.isEmpty()) return null

        val builtIn = ProfileIds.isBuiltIn(id)
        val name = (entry.opt(KEY_NAME) as? String)?.let(ProfileEditor::sanitizeName)
            ?: BuiltInProfiles.displayName(id)
            ?: FALLBACK_CUSTOM_NAME
        val dashboard = entry.optJSONObject(KEY_DASHBOARD)
            ?.let { DashboardSerializer.fromJson(it)?.dashboard }
            ?: BuiltInProfiles.dashboardFor(id) // a damaged built-in comes back from its template; a custom one is dropped
            ?: return null

        return DashboardProfile(
            id = id,
            name = name,
            order = entry.optInt(KEY_ORDER, index),
            builtIn = builtIn,
            dashboard = dashboard
        )
    }
}
