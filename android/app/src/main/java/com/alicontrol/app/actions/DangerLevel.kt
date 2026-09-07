package com.alicontrol.app.actions

/**
 * SAFE — sent immediately on tap.
 * CONFIRM — the UI must ask the user to confirm before sending (e.g. sleep).
 *
 * Shutdown/restart/logout/destructive-file actions are deliberately not
 * modeled at all yet (out of scope for TASK-006) rather than being
 * classified under either value here.
 */
enum class DangerLevel {
    SAFE,
    CONFIRM
}
