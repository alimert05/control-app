package com.alicontrol.app.actions

/**
 * Structured metadata for one action Ali Control can send. This describes
 * what an action IS — it never executes anything itself. Sending still goes
 * through [com.alicontrol.app.transport.BluetoothTransport.send] with the
 * raw [id] exactly as defined in
 * [com.alicontrol.app.ble.AliControlCommands].
 */
data class ActionDefinition(
    val id: String,
    val label: String,
    val category: ActionCategory,
    val supportedPlatforms: Set<Platform>,
    val dangerLevel: DangerLevel = DangerLevel.SAFE,
    val permissionRequirement: PermissionRequirement = PermissionRequirement.NONE,
    /**
     * The one word a 1x1 tile has room for. Defaults to the first word of [label];
     * set explicitly where that would be ambiguous ("Task View" and "Task Manager"
     * would both become "Task") or unhelpful ("File Explorer" -> "File").
     */
    val shortLabel: String = label.substringBefore(' ').trim()
) {
    fun supports(platform: Platform): Boolean = platform in supportedPlatforms
}
