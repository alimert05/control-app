package com.alicontrol.app.actions

/**
 * What the connected desktop agent needs in order to actually perform this
 * action. Purely descriptive on the Android side — Android never requests or
 * checks this itself (it doesn't run on the same machine as the permission);
 * it only informs the UI/documentation of what might fail, and why, on the
 * desktop end. The desktop agent (see macos/.../Control/ActionRegistry.swift)
 * is what actually enforces this.
 */
enum class PermissionRequirement {
    NONE,
    ACCESSIBILITY
}
