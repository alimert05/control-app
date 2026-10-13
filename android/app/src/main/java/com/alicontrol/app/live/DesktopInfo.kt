package com.alicontrol.app.live

import com.alicontrol.app.actions.Platform

/**
 * What a desktop agent can do, as announced in its `capabilities` message(s).
 * Wire ids are descriptive and stable; an id this build does not know is simply
 * ignored (a newer agent may announce more than this tablet understands).
 *
 * An agent only announces a capability it has actually probed as working on
 * that machine — e.g. `telemetry.gpu` is absent on a PC without GPU engine
 * counters, and the `telemetry.temperature.*` ids are not announced at all
 * because Windows has no reliable user-mode source for them.
 */
enum class DesktopCapability(val wireId: String) {
    ACTIONS("actions"),
    TELEMETRY_CPU("telemetry.cpu"),
    TELEMETRY_MEMORY("telemetry.memory"),
    TELEMETRY_GPU("telemetry.gpu"),
    TELEMETRY_TEMPERATURE_CPU("telemetry.temperature.cpu"),
    TELEMETRY_TEMPERATURE_GPU("telemetry.temperature.gpu"),
    MEDIA("media"),
    MEDIA_ARTWORK("media.artwork");

    companion object {
        fun fromWireId(id: String): DesktopCapability? = values().firstOrNull { it.wireId == id }
    }
}

/**
 * The identity of the connected desktop, as reported by the agent's
 * `desktop_info` message — the single source of truth for "which platform am I
 * talking to". Nothing in the app hardcodes a connected platform any more.
 *
 * - [hasIdentity] is false until the agent has introduced itself (for example
 *   the instant after connecting, or an agent that predates `desktop_info`).
 *   In that state nothing is gated on platform or capabilities: the supported
 *   platform is assumed and the agent itself re-validates every action.
 * - [platform] is null when the agent reported a platform this build does not
 *   know; remote actions are then treated as unsupported (never deleted from the
 *   saved layout — see [com.alicontrol.app.dashboard.WidgetSupport]).
 * - [capabilities] is null until the agent has announced them; once non-null a
 *   missing capability disables the widgets that need it.
 */
data class DesktopInfo(
    val hasIdentity: Boolean = false,
    val platform: Platform? = null,
    val platformId: String? = null,
    val name: String? = null,
    val protocolVersion: Int = 0,
    val capabilities: Set<DesktopCapability>? = null
) {

    /** Null while capabilities are unknown (not yet announced); otherwise whether [capability] is present. */
    fun supports(capability: DesktopCapability): Boolean? = capabilities?.contains(capability)

    companion object {
        val UNKNOWN = DesktopInfo()
    }
}
