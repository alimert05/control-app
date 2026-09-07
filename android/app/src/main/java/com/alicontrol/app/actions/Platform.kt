package com.alicontrol.app.actions

/**
 * Which desktop platform(s) an action is available on. `WINDOWS` exists here
 * from the start even though no Windows agent exists yet (TASK-006+), so the
 * Android app can already determine which actions are relevant once one
 * does, without touching this model again.
 */
enum class Platform {
    MACOS,
    WINDOWS
}
