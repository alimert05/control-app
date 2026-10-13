package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.live.DesktopCapability
import com.alicontrol.app.live.DesktopInfo
import com.alicontrol.app.live.MetricType

/** Whether a saved widget can do its job against the desktop that is connected right now. */
enum class WidgetAvailability {
    AVAILABLE,

    /** The desktop's platform does not support one of the widget's actions (or an action id is unknown). */
    UNSUPPORTED_ACTION,

    /** The desktop announced its capabilities and does not have one this widget needs. */
    MISSING_CAPABILITY
}

/**
 * The single place that decides whether a widget is usable against the connected
 * desktop, from the desktop's own announcements ([DesktopInfo]) — never from an
 * assumption about which platform it is.
 *
 * The rule that matters most: **availability only ever changes how a widget is
 * drawn (dimmed, not tappable). It never changes the saved layout.** A widget the
 * connected desktop cannot serve stays exactly where it is, and becomes usable
 * again the moment a desktop that supports it connects. A capability that is
 * "temporarily absent" (disconnected, or not yet announced) is never grounds for
 * removing anything.
 *
 * Until the agent has introduced itself ([DesktopInfo.hasIdentity] false) nothing
 * is gated on platform; until it has announced capabilities
 * ([DesktopInfo.capabilities] null) nothing is gated on them. The agent
 * independently re-validates every action regardless.
 */
object WidgetSupport {

    fun availability(widget: DashboardWidget, desktop: DesktopInfo): WidgetAvailability {
        if (widget.isRemoteControl() && !actionsSupportedByPlatform(widget.referencedActionIds(), desktop)) {
            return WidgetAvailability.UNSUPPORTED_ACTION
        }
        val missing = requiredCapabilities(widget).any { desktop.supports(it) == false }
        return if (missing) WidgetAvailability.MISSING_CAPABILITY else WidgetAvailability.AVAILABLE
    }

    /**
     * Whether a tap on [actionId] should result in a send, as far as the connected
     * desktop's platform and capabilities are concerned. (Connection state and
     * confirmation dialogs are the dispatcher's concern.)
     */
    fun canSend(actionId: String, desktop: DesktopInfo): Boolean =
        actionsSupportedByPlatform(listOf(actionId), desktop) &&
            desktop.supports(DesktopCapability.ACTIONS) != false

    /** The capabilities a widget needs the desktop to have. Local/informational widgets need none. */
    fun requiredCapabilities(widget: DashboardWidget): Set<DesktopCapability> = when (val config = widget.config) {
        is WidgetConfig.Action, is WidgetConfig.ActionGroup, is WidgetConfig.Media, is WidgetConfig.Slider ->
            if (widget.isRemoteControl()) setOf(DesktopCapability.ACTIONS) else emptySet()
        is WidgetConfig.Metric -> setOfNotNull(telemetryCapability(MetricType.forLabel(config.label)))
        is WidgetConfig.Gauge -> setOfNotNull(telemetryCapability(MetricType.forLabel(config.label)))
        is WidgetConfig.Clock, is WidgetConfig.DailyOverview, is WidgetConfig.Shortcut -> emptySet()
    }

    /** The telemetry capability behind a reading, or null for a label this build has no live source for. */
    fun telemetryCapability(metric: MetricType): DesktopCapability? = when (metric) {
        MetricType.CPU -> DesktopCapability.TELEMETRY_CPU
        MetricType.MEMORY -> DesktopCapability.TELEMETRY_MEMORY
        MetricType.GPU -> DesktopCapability.TELEMETRY_GPU
        MetricType.CPU_TEMPERATURE -> DesktopCapability.TELEMETRY_TEMPERATURE_CPU
        MetricType.GPU_TEMPERATURE -> DesktopCapability.TELEMETRY_TEMPERATURE_GPU
        MetricType.UNKNOWN -> null
    }

    private fun actionsSupportedByPlatform(actionIds: List<String>, desktop: DesktopInfo): Boolean {
        if (actionIds.any { ActionLibrary.find(it) == null }) return false
        if (!desktop.hasIdentity) return true // not introduced yet: assume the supported platform
        val platform: Platform = desktop.platform ?: return false // a platform this build does not know
        return actionIds.all { ActionLibrary.find(it)?.supports(platform) == true }
    }
}
