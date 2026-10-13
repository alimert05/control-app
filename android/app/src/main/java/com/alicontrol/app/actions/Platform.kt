package com.alicontrol.app.actions

/**
 * Which desktop platform an action is available on, and which platform the
 * connected desktop agent identifies itself as (see
 * [com.alicontrol.app.live.DesktopInfo]).
 *
 * Windows is the only supported desktop platform — macOS was the original
 * reference platform and was retired in the Windows migration. The enum (and
 * the per-action `supportedPlatforms` set that uses it) is deliberately kept:
 * the saved dashboard is platform-neutral, an action is only ever *disabled*
 * (never deleted) when the connected desktop does not support it, and a future
 * desktop platform only needs a new entry here.
 *
 * [wireId] is the `platform` value in the agent's `desktop_info` message.
 */
enum class Platform(val wireId: String) {
    WINDOWS("windows");

    companion object {
        /**
         * The platform assumed when no desktop has identified itself yet — for
         * example while editing the dashboard offline. It is the supported
         * desktop platform, not the connected one: the connection state is
         * always taken from the agent's own `desktop_info`.
         */
        val DEFAULT: Platform = WINDOWS

        /** Null for a platform id this build does not know. */
        fun fromWireId(id: String?): Platform? = values().firstOrNull { it.wireId.equals(id, ignoreCase = true) }
    }
}
