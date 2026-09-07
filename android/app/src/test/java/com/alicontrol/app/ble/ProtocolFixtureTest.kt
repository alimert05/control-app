package com.alicontrol.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform protocol fixtures. The literal JSON strings in this file
 * MUST match the ones in
 * macos/Tests/AliControlMacTests/ProtocolFixtureTests.swift exactly — the
 * two files exist specifically to catch wire-format drift between the two
 * platforms, and were added after a real bug where every command arrived on
 * macOS as unparseable garbage (see [malformedMessagesFailSafely] for the
 * actual shape of that failure — it was BLE MTU truncation of an otherwise
 * fully compatible format, not a field-name/JSON-shape mismatch).
 */
class ProtocolFixtureTest {

    @Test
    fun serializesVolumeUpToCanonicalJson() {
        val message = ProtocolMessage.command(AliControlCommands.VOLUME_UP)
        assertEquals("{\"type\":\"command\",\"action\":\"volume_up\"}", message.toJson())
    }

    @Test
    fun serializesEveryAllowlistedCommandToCanonicalJson() {
        assertEquals("{\"type\":\"command\",\"action\":\"ping\"}", ProtocolMessage.command(AliControlCommands.PING).toJson())
        assertEquals("{\"type\":\"command\",\"action\":\"volume_up\"}", ProtocolMessage.command(AliControlCommands.VOLUME_UP).toJson())
        assertEquals("{\"type\":\"command\",\"action\":\"volume_down\"}", ProtocolMessage.command(AliControlCommands.VOLUME_DOWN).toJson())
        assertEquals("{\"type\":\"command\",\"action\":\"mute_toggle\"}", ProtocolMessage.command(AliControlCommands.MUTE_TOGGLE).toJson())
        assertEquals("{\"type\":\"command\",\"action\":\"play_pause\"}", ProtocolMessage.command(AliControlCommands.PLAY_PAUSE).toJson())
        assertEquals("{\"type\":\"command\",\"action\":\"open_terminal\"}", ProtocolMessage.command(AliControlCommands.OPEN_TERMINAL).toJson())
    }

    @Test
    fun parsesCanonicalInitialConnectedStateSentByMacOS() {
        val decoded = ProtocolMessage.fromJson("{\"type\":\"state\",\"status\":\"connected\"}")
        assertEquals(ProtocolMessage(type = ProtocolMessage.TYPE_STATE, status = "connected"), decoded)
    }

    @Test
    fun parsesCanonicalOkAckSentByMacOS() {
        // Exactly what macOS's ProtocolMessage.encoded() produces for a
        // successful volume_up ack — see ProtocolFixtureTests.swift.
        val decoded = ProtocolMessage.fromJson("{\"type\":\"state\",\"action\":\"volume_up\",\"status\":\"ok\"}")
        assertEquals("state", decoded?.type)
        assertEquals("volume_up", decoded?.action)
        assertEquals("ok", decoded?.status)
    }

    @Test
    fun parsesCanonicalErrorAckSentByMacOS() {
        val decoded = ProtocolMessage.fromJson("{\"type\":\"state\",\"action\":\"open_terminal\",\"status\":\"error\"}")
        assertEquals("error", decoded?.status)
        assertEquals("open_terminal", decoded?.action)
    }

    @Test
    fun malformedMessagesFailSafely() {
        assertNull(ProtocolMessage.fromJson(""))
        assertNull(ProtocolMessage.fromJson("not json"))
        assertNull(ProtocolMessage.fromJson("{\"action\":\"ping\"}"))
        // Exactly what the default 20-byte BLE ATT MTU used to truncate
        // {"type":"command","action":"volume_up"} down to — this is the
        // literal shape of the real bug, reproduced as a regression test.
        assertNull(ProtocolMessage.fromJson("{\"type\":\"command\",\"a"))
    }

    @Test
    fun protocolLayerDoesNotItselfValidateActionsAgainstTheAllowlist() {
        // Allowlist enforcement is CommandDispatcher's job on macOS (see
        // CommandDispatcherTests.testUnsupportedActionReturnsErrorWithoutCallingController),
        // not the message envelope's — parsing must not throw or reject an
        // unrecognized action, so a desktop agent can send back a clean
        // error state instead of the tablet just discarding it silently.
        val decoded = ProtocolMessage.fromJson("{\"type\":\"command\",\"action\":\"shutdown\"}")
        assertTrue(decoded != null)
        assertEquals("shutdown", decoded?.action)
    }
}
