package com.alicontrol.app.dashboard

import com.alicontrol.app.ble.AliControlCommands

/**
 * Saved-layout migration for action ids.
 *
 * macOS was the original reference desktop, and a few action ids described
 * macOS-only features. When Windows became the only supported desktop those ids
 * were replaced by their Windows equivalents. A dashboard saved by an older
 * build (schema 1) may still reference the old ids, and a user's widgets must
 * never be silently lost — so on load each old id is rewritten to the
 * equivalent action, and the widget keeps its place, size and page:
 *
 * | Old id (schema 1)        | Becomes             | Why it is the same intent |
 * |--------------------------|---------------------|---------------------------|
 * | `spotlight`              | `windows_search`    | system-wide search launcher |
 * | `mission_control`        | `task_view`         | window/desktop overview (Win+Tab) |
 * | `open_finder`            | `open_explorer`     | the file manager |
 * | `open_activity_monitor`  | `open_task_manager` | the process/performance monitor |
 *
 * Every other action id existed unchanged on both platforms and is untouched.
 * Only schema-1 documents are migrated: a current-schema document that contains
 * an id the library does not know is still rejected, so a hand-edited file
 * cannot smuggle an arbitrary id into a layout.
 */
object ActionIdMigration {

    /** The complete list of renamed ids — also the documentation of record (see docs/WINDOWS_MIGRATION.md). */
    val LEGACY_MACOS_ACTIONS: Map<String, String> = mapOf(
        "spotlight" to AliControlCommands.WINDOWS_SEARCH,
        "mission_control" to AliControlCommands.TASK_VIEW,
        "open_finder" to AliControlCommands.OPEN_EXPLORER,
        "open_activity_monitor" to AliControlCommands.OPEN_TASK_MANAGER
    )

    /** The Windows equivalent of [actionId] if it is a retired id; otherwise [actionId] unchanged. */
    fun migrate(actionId: String): String = LEGACY_MACOS_ACTIONS[actionId] ?: actionId

    fun isLegacy(actionId: String): Boolean = actionId in LEGACY_MACOS_ACTIONS
}
