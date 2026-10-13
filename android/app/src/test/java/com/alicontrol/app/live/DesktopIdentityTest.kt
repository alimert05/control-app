package com.alicontrol.app.live

import com.alicontrol.app.actions.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connected desktop's identity: parsing `desktop_info` / `capabilities`, how the
 * shared store keeps the connected platform dynamically, and that nothing about it
 * survives a disconnect. The literal JSON strings must match what the Windows agent
 * emits (see DesktopInfoTests in the agent's test project).
 */
class DesktopIdentityTest {

    private val desktopInfoJson = "{\"type\":\"desktop_info\",\"platform\":\"windows\",\"protocol\":2,\"name\":\"ALI-PC\"}"
    private val capabilitiesJson =
        "{\"type\":\"capabilities\",\"ids\":[\"actions\",\"telemetry.cpu\",\"telemetry.memory\",\"telemetry.gpu\",\"media\",\"media.artwork\"]}"

    private val store = DesktopStateStore(clock = { 1_000L }, scheduler = object : StalenessScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit) = Unit
        override fun cancel() = Unit
    })

    private fun receive(json: String) = store.onDataReceived(json.toByteArray(Charsets.UTF_8))

    // -- parsing --

    @Test
    fun parsesTheAgentsDesktopInfo() {
        val message = LiveStateMessage.fromJson(desktopInfoJson) as LiveStateMessage.Desktop
        assertEquals("windows", message.platformId)
        assertEquals(2, message.protocolVersion)
        assertEquals("ALI-PC", message.name)
    }

    @Test
    fun anAgentThatOmitsTheProtocolVersionIsTreatedAsVersionOne() {
        val message = LiveStateMessage.fromJson("{\"type\":\"desktop_info\",\"platform\":\"windows\"}") as LiveStateMessage.Desktop
        assertEquals(1, message.protocolVersion)
        assertNull(message.name)
    }

    @Test
    fun parsesTheAgentsCapabilities() {
        val message = LiveStateMessage.fromJson(capabilitiesJson) as LiveStateMessage.Capabilities
        assertEquals(
            listOf("actions", "telemetry.cpu", "telemetry.memory", "telemetry.gpu", "media", "media.artwork"),
            message.ids
        )
    }

    @Test
    fun aMalformedCapabilityListIsRejectedNotTreatedAsEmpty() {
        assertNull(LiveStateMessage.fromJson("{\"type\":\"capabilities\"}"))
        assertNull(LiveStateMessage.fromJson("{\"type\":\"capabilities\",\"ids\":\"actions\"}"))
        assertNull(LiveStateMessage.fromJson("{\"type\":\"capabilities\",\"ids\":[\"actions\",7]}"))
        assertNull(LiveStateMessage.fromJson("{\"type\":\"capabilities\",\"ids\":[\"actions\""))
    }

    @Test
    fun aDesktopNameWithEscapesDecodesProperly() {
        val message = LiveStateMessage.fromJson(
            "{\"type\":\"desktop_info\",\"platform\":\"windows\",\"protocol\":2,\"name\":\"Ali\\\"s PC\"}"
        ) as LiveStateMessage.Desktop
        assertEquals("Ali\"s PC", message.name)
    }

    @Test
    fun identityMessagesAreNotConfusedWithAcknowledgementsOrLiveState() {
        assertNull(com.alicontrol.app.ble.ProtocolMessage.fromJson(desktopInfoJson)?.status)
        assertEquals("desktop_info", com.alicontrol.app.ble.ProtocolMessage.fromJson(desktopInfoJson)?.type)
        assertNull(LiveStateMessage.fromJson("{\"type\":\"state\",\"action\":\"volume_up\",\"status\":\"ok\"}"))
    }

    // -- capability ids --

    @Test
    fun everyCapabilityIdRoundTripsAndUnknownOnesAreIgnored() {
        for (capability in DesktopCapability.values()) {
            assertEquals(capability, DesktopCapability.fromWireId(capability.wireId))
        }
        assertNull(DesktopCapability.fromWireId("telemetry.fan"))
        assertNull(DesktopCapability.fromWireId(""))
    }

    @Test
    fun capabilityIdsAreTheOnesTheAgentDefines() {
        assertEquals(
            setOf(
                "actions", "telemetry.cpu", "telemetry.memory", "telemetry.gpu",
                "telemetry.temperature.cpu", "telemetry.temperature.gpu", "media", "media.artwork"
            ),
            DesktopCapability.values().map { it.wireId }.toSet()
        )
    }

    // -- the store keeps the connected platform dynamically --

    @Test
    fun theStoreStartsWithNoIdentity() {
        val desktop = store.state.desktop
        assertFalse(desktop.hasIdentity)
        assertNull(desktop.platform)
        assertNull(desktop.capabilities)
    }

    @Test
    fun desktopInfoSetsTheConnectedPlatformFromTheAgentNotFromAConstant() {
        receive(desktopInfoJson)

        val desktop = store.state.desktop
        assertTrue(desktop.hasIdentity)
        assertEquals(Platform.WINDOWS, desktop.platform)
        assertEquals("windows", desktop.platformId)
        assertEquals("ALI-PC", desktop.name)
        assertEquals(2, desktop.protocolVersion)
        assertNull("capabilities arrive in their own message", desktop.capabilities)
    }

    @Test
    fun anUnknownPlatformIsKeptAsReportedAndNeverCoercedToWindows() {
        receive("{\"type\":\"desktop_info\",\"platform\":\"plan9\",\"protocol\":2,\"name\":\"X\"}")

        val desktop = store.state.desktop
        assertTrue(desktop.hasIdentity)
        assertNull(desktop.platform)
        assertEquals("plan9", desktop.platformId)
    }

    @Test
    fun capabilitiesAreStoredAndKnownIdsOnly() {
        receive(desktopInfoJson)
        receive("{\"type\":\"capabilities\",\"ids\":[\"actions\",\"telemetry.cpu\",\"telemetry.fan\"]}")

        assertEquals(
            setOf(DesktopCapability.ACTIONS, DesktopCapability.TELEMETRY_CPU),
            store.state.desktop.capabilities
        )
    }

    @Test
    fun capabilityMessagesAreAdditiveSoALongListMayBeSplit() {
        receive(desktopInfoJson)
        receive("{\"type\":\"capabilities\",\"ids\":[\"actions\",\"telemetry.cpu\"]}")
        receive("{\"type\":\"capabilities\",\"ids\":[\"media\"]}")

        assertEquals(
            setOf(DesktopCapability.ACTIONS, DesktopCapability.TELEMETRY_CPU, DesktopCapability.MEDIA),
            store.state.desktop.capabilities
        )
    }

    @Test
    fun aNewIntroductionStartsTheCapabilityListOver() {
        receive(desktopInfoJson)
        receive(capabilitiesJson)
        assertNotNull(store.state.desktop.capabilities)

        receive(desktopInfoJson)

        assertNull(store.state.desktop.capabilities)
    }

    @Test
    fun anEmptyCapabilityListMeansTheDesktopHasNone() {
        receive(desktopInfoJson)
        receive("{\"type\":\"capabilities\",\"ids\":[]}")

        assertEquals(emptySet<DesktopCapability>(), store.state.desktop.capabilities)
        assertEquals(false, store.state.desktop.supports(DesktopCapability.ACTIONS))
    }

    @Test
    fun supportsIsUnknownUntilCapabilitiesArrive() {
        receive(desktopInfoJson)
        assertNull(store.state.desktop.supports(DesktopCapability.TELEMETRY_GPU))

        receive(capabilitiesJson)
        assertEquals(true, store.state.desktop.supports(DesktopCapability.TELEMETRY_GPU))
        assertEquals(false, store.state.desktop.supports(DesktopCapability.TELEMETRY_TEMPERATURE_CPU))
    }

    @Test
    fun aBlankMachineNameIsDroppedRatherThanShown() {
        receive("{\"type\":\"desktop_info\",\"platform\":\"windows\",\"protocol\":2,\"name\":\"  \"}")
        assertNull(store.state.desktop.name)
    }

    // -- lifecycle --

    @Test
    fun disconnectingForgetsWhichDesktopItWas() {
        receive(desktopInfoJson)
        receive(capabilitiesJson)

        store.onDisconnected()

        assertEquals(DesktopInfo.UNKNOWN, store.state.desktop)
    }

    @Test
    fun aNewConnectionNeverInheritsThePreviousDesktopsIdentity() {
        receive(desktopInfoJson)
        receive(capabilitiesJson)

        store.onConnected()

        assertEquals(DesktopInfo.UNKNOWN, store.state.desktop)
    }

    @Test
    fun listenersAreToldWhenTheIdentityArrivesSoWidgetsCanReEvaluate() {
        val seen = mutableListOf<DesktopInfo>()
        store.addListener { seen.add(it.desktop) }

        receive(desktopInfoJson)
        receive(capabilitiesJson)

        assertEquals(3, seen.size) // initial state, desktop_info, capabilities
        assertFalse(seen.first().hasIdentity)
        assertTrue(seen.last().capabilities!!.contains(DesktopCapability.MEDIA_ARTWORK))
    }

    @Test
    fun identityDoesNotExpireLikeTelemetryDoes() {
        var now = 1_000L
        val timed = DesktopStateStore(clock = { now }, scheduler = object : StalenessScheduler {
            override fun schedule(delayMs: Long, action: () -> Unit) = Unit
            override fun cancel() = Unit
        })
        timed.onDataReceived(desktopInfoJson.toByteArray())
        timed.onDataReceived(capabilitiesJson.toByteArray())

        now += 10 * DesktopState.STALE_TIMEOUT_MS

        assertEquals(Platform.WINDOWS, timed.visibleState().desktop.platform)
        assertNotNull(timed.visibleState().desktop.capabilities)
    }

    @Test
    fun identityMessagesDoNotDisturbTelemetryOrMedia() {
        receive("{\"type\":\"metrics\",\"cpu\":34,\"memory\":48}")
        receive(desktopInfoJson)
        receive(capabilitiesJson)

        assertEquals(34, store.state.metrics.cpuPercent)
        assertEquals(48, store.state.metrics.memoryPercent)
    }
}
