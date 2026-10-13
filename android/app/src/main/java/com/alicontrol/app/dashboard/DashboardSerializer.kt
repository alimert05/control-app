package com.alicontrol.app.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a [Dashboard] into a small JSON document and back.
 *
 * `org.json` is part of the Android platform itself, so this adds nothing to
 * the APK and pulls in no serialization framework (no Room, no SQL, no
 * reflection-based JSON library) — matching the same "hand-rolled, no
 * dependency" spirit as the BLE
 * [com.alicontrol.app.ble.ProtocolMessage] envelope.
 *
 * **Deserialization is strict and total**: any structural problem — bad JSON,
 * an unknown widget type, a widget outside the 6x4 grid, overlapping widgets,
 * duplicate ids, an action id that isn't in [com.alicontrol.app.actions.ActionLibrary],
 * or a schema version this build doesn't understand — returns null rather
 * than a half-built dashboard. [DashboardStore] then falls back to the
 * shipped default, so a corrupted file degrades to "you get the defaults
 * back", never to a crash or a silently broken layout.
 */
object DashboardSerializer {

    /**
     * Bumped whenever the persisted content changes incompatibly. A file written
     * by a newer build is refused (and replaced by defaults) rather than guessed
     * at; a file written by an older, still-understood build is migrated.
     *
     * - 1: the original layout. Action ids could be the macOS-era ones
     *   (`spotlight`, `mission_control`, `open_finder`, `open_activity_monitor`).
     * - 2: Windows-only. Those ids are replaced by their Windows equivalents
     *   (see [ActionIdMigration]); the document structure is otherwise identical.
     */
    const val SCHEMA_VERSION = 2

    /** The oldest schema this build can still read (and migrate to [SCHEMA_VERSION]). */
    const val OLDEST_SUPPORTED_SCHEMA_VERSION = 1

    /** A successfully read layout, and whether it came from an older schema and was migrated on the way in. */
    data class Loaded(val dashboard: Dashboard, val migratedFromVersion: Int?) {
        val wasMigrated: Boolean get() = migratedFromVersion != null
    }

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_PAGES = "pages"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_ORDER = "order"
    private const val KEY_WIDGETS = "widgets"
    private const val KEY_TYPE = "type"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_WIDTH = "w"
    private const val KEY_HEIGHT = "h"
    private const val KEY_CONFIG = "config"
    private const val KEY_ACTION_ID = "actionId"
    private const val KEY_ACTION_IDS = "actionIds"
    private const val KEY_LABEL = "label"
    private const val KEY_SHOW_DATE = "showDate"
    private const val KEY_LOCATION = "location"
    private const val KEY_PREVIOUS = "previous"
    private const val KEY_PLAY_PAUSE = "playPause"
    private const val KEY_NEXT = "next"
    private const val KEY_DESTINATION = "destination"

    fun serialize(dashboard: Dashboard): String = toJson(dashboard).toString()

    /**
     * The dashboard as a JSON object — the one representation of a [Dashboard],
     * used both for the stand-alone document above and for each profile's
     * dashboard inside a profile document (see
     * [com.alicontrol.app.dashboard.profile.ProfileSerializer]). Profiles
     * embed this exact shape rather than defining a second one.
     */
    fun toJson(dashboard: Dashboard): JSONObject {
        val pages = JSONArray()
        for (page in dashboard.orderedPages) {
            pages.put(
                JSONObject()
                    .put(KEY_ID, page.id)
                    .put(KEY_NAME, page.name)
                    .put(KEY_ORDER, page.order)
                    .put(KEY_WIDGETS, serializeWidgets(page.widgets))
            )
        }
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .put(KEY_PAGES, pages)
    }

    /** Returns null for anything this build can't fully and validly reconstruct. */
    fun deserialize(json: String?): Dashboard? = deserializeWithInfo(json)?.dashboard

    /**
     * Like [deserialize], but also reports whether an older schema was migrated, so
     * the caller can persist the upgraded document. Schema-1 action ids that
     * referred to macOS-only features are rewritten to their Windows equivalents
     * ([ActionIdMigration]) *before* validation, so no widget is lost.
     */
    fun deserializeWithInfo(json: String?): Loaded? {
        if (json.isNullOrBlank()) return null
        return try {
            fromJson(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The same strict, total reconstruction as [deserializeWithInfo], for a
     * dashboard that has already been parsed into a [JSONObject] (a profile's
     * embedded dashboard). Returns null for anything not fully valid.
     */
    fun fromJson(root: JSONObject): Loaded? {
        return try {
            val version = root.getInt(KEY_SCHEMA_VERSION)
            if (version !in OLDEST_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) return null
            val migrateIds = version < 2
            val pagesJson = root.getJSONArray(KEY_PAGES)
            if (pagesJson.length() == 0) return null

            val pages = ArrayList<DashboardPage>(pagesJson.length())
            for (i in 0 until pagesJson.length()) {
                pages.add(deserializePage(pagesJson.getJSONObject(i), migrateIds) ?: return null)
            }
            val dashboard = Dashboard(pages).normalizedOrder()
            if (!dashboard.isValid()) return null
            if (!referencesOnlyKnownActions(dashboard)) return null
            Loaded(dashboard, migratedFromVersion = if (migrateIds) version else null)
        } catch (e: Exception) {
            // Any malformed structure at all (JSONException, IllegalArgumentException
            // from a widget's own invariants, a bad enum name) means the stored
            // layout is not trustworthy — the caller falls back to defaults.
            null
        }
    }

    private fun serializeWidgets(widgets: List<DashboardWidget>): JSONArray {
        val array = JSONArray()
        for (widget in widgets) {
            array.put(
                JSONObject()
                    .put(KEY_ID, widget.id)
                    .put(KEY_TYPE, widget.type.name)
                    .put(KEY_X, widget.gridX)
                    .put(KEY_Y, widget.gridY)
                    .put(KEY_WIDTH, widget.gridWidth)
                    .put(KEY_HEIGHT, widget.gridHeight)
                    .put(KEY_CONFIG, serializeConfig(widget.config))
            )
        }
        return array
    }

    private fun serializeConfig(config: WidgetConfig): JSONObject = when (config) {
        is WidgetConfig.Action -> JSONObject().put(KEY_ACTION_ID, config.actionId)
        is WidgetConfig.ActionGroup -> JSONObject().put(KEY_ACTION_IDS, JSONArray(config.actionIds))
        is WidgetConfig.Metric -> JSONObject().put(KEY_LABEL, config.label)
        is WidgetConfig.Gauge -> JSONObject().put(KEY_LABEL, config.label)
        is WidgetConfig.Clock -> JSONObject().put(KEY_SHOW_DATE, config.showDate)
        is WidgetConfig.DailyOverview -> JSONObject().put(KEY_LOCATION, config.locationLabel)
        is WidgetConfig.Media -> JSONObject()
            .put(KEY_PREVIOUS, config.previousActionId)
            .put(KEY_PLAY_PAUSE, config.playPauseActionId)
            .put(KEY_NEXT, config.nextActionId)
        is WidgetConfig.Slider -> JSONObject()
            .put(KEY_LABEL, config.label)
            .put(KEY_ACTION_IDS, JSONArray(config.actionIds))
        is WidgetConfig.Shortcut -> JSONObject()
            .put(KEY_LABEL, config.label)
            .put(KEY_DESTINATION, config.destination.name)
    }

    private fun deserializePage(json: JSONObject, migrateIds: Boolean): DashboardPage? {
        val id = json.getString(KEY_ID)
        val name = json.getString(KEY_NAME)
        if (id.isBlank() || name.isBlank()) return null
        val widgetsJson = json.getJSONArray(KEY_WIDGETS)
        val widgets = ArrayList<DashboardWidget>(widgetsJson.length())
        for (i in 0 until widgetsJson.length()) {
            widgets.add(deserializeWidget(widgetsJson.getJSONObject(i), migrateIds) ?: return null)
        }
        return DashboardPage(id = id, name = name, order = json.getInt(KEY_ORDER), widgets = widgets)
    }

    private fun deserializeWidget(json: JSONObject, migrateIds: Boolean): DashboardWidget? {
        val type = WidgetType.values().firstOrNull { it.name == json.getString(KEY_TYPE) } ?: return null
        val config = deserializeConfig(type, json.getJSONObject(KEY_CONFIG), migrateIds) ?: return null
        val placement = WidgetPlacement(
            x = json.getInt(KEY_X),
            y = json.getInt(KEY_Y),
            width = json.getInt(KEY_WIDTH),
            height = json.getInt(KEY_HEIGHT)
        )
        if (!placement.isWithinBounds() || !type.allows(placement)) return null
        val id = json.getString(KEY_ID)
        if (id.isBlank()) return null
        return DashboardWidget(id, type, placement, config)
    }

    private fun deserializeConfig(type: WidgetType, json: JSONObject, migrateIds: Boolean): WidgetConfig? {
        // Schema-1 documents may still name a retired (macOS-era) action; map it to its Windows
        // equivalent here, before any validation, so the widget survives with its place and size.
        val actionId: (String) -> String = if (migrateIds) ActionIdMigration::migrate else { id -> id }
        return deserializeConfigWith(type, json, actionId)
    }

    private fun deserializeConfigWith(type: WidgetType, json: JSONObject, actionId: (String) -> String): WidgetConfig? = when (type) {
        WidgetType.ACTION -> WidgetConfig.Action(actionId(json.getString(KEY_ACTION_ID)))
        WidgetType.ACTION_GROUP -> {
            val ids = json.getJSONArray(KEY_ACTION_IDS).toStringList().map(actionId)
            if (ids.isEmpty()) null else WidgetConfig.ActionGroup(ids)
        }
        WidgetType.METRIC -> WidgetConfig.Metric(json.getString(KEY_LABEL))
        WidgetType.GAUGE -> WidgetConfig.Gauge(json.getString(KEY_LABEL))
        WidgetType.CLOCK -> WidgetConfig.Clock(json.getBoolean(KEY_SHOW_DATE))
        WidgetType.DAILY_OVERVIEW -> WidgetConfig.DailyOverview(json.optString(KEY_LOCATION, ""))
        WidgetType.MEDIA -> WidgetConfig.Media(
            previousActionId = actionId(json.getString(KEY_PREVIOUS)),
            playPauseActionId = actionId(json.getString(KEY_PLAY_PAUSE)),
            nextActionId = actionId(json.getString(KEY_NEXT))
        )
        WidgetType.SLIDER -> WidgetConfig.Slider(
            label = json.getString(KEY_LABEL),
            actionIds = json.getJSONArray(KEY_ACTION_IDS).toStringList().map(actionId)
        )
        WidgetType.SHORTCUT -> {
            val destination = ShortcutDestination.values()
                .firstOrNull { it.name == json.getString(KEY_DESTINATION) }
            if (destination == null) null else WidgetConfig.Shortcut(json.getString(KEY_LABEL), destination)
        }
    }

    /**
     * A saved layout may only reference actions this build actually knows —
     * the same rule [DashboardEditor.addActionWidget] enforces when adding,
     * applied again on load so a hand-edited file can't introduce an action
     * id the app would then try to send.
     */
    private fun referencesOnlyKnownActions(dashboard: Dashboard): Boolean =
        dashboard.pages.asSequence()
            .flatMap { it.widgets.asSequence() }
            .flatMap { it.referencedActionIds().asSequence() }
            .all { com.alicontrol.app.actions.ActionLibrary.find(it) != null }

    private fun JSONArray.toStringList(): List<String> {
        val result = ArrayList<String>(length())
        for (i in 0 until length()) result.add(getString(i))
        return result
    }
}
