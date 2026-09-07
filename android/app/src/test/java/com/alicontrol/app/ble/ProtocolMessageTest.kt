package com.alicontrol.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolMessageTest {

    @Test
    fun commandEncodesTypeAndAction() {
        val message = ProtocolMessage.command("ping")
        assertEquals("{\"type\":\"command\",\"action\":\"ping\"}", message.toJson())
    }

    @Test
    fun stateEncodesTypeAndStatus() {
        val message = ProtocolMessage.state("connected")
        assertEquals("{\"type\":\"state\",\"status\":\"connected\"}", message.toJson())
    }

    @Test
    fun roundTripsThroughJson() {
        val original = ProtocolMessage.command("ping")
        val decoded = ProtocolMessage.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsThroughBytes() {
        val original = ProtocolMessage.state("connected")
        val decoded = ProtocolMessage.fromBytes(original.toBytes())
        assertEquals(original, decoded)
    }

    @Test
    fun parsesFieldsRegardlessOfOrder() {
        val json = "{\"status\":\"connected\",\"type\":\"state\"}"
        val decoded = ProtocolMessage.fromJson(json)
        assertEquals(ProtocolMessage(type = "state", status = "connected"), decoded)
    }

    @Test
    fun parsesFieldsWithExtraWhitespace() {
        val json = "{ \"type\" : \"command\" , \"action\" : \"ping\" }"
        val decoded = ProtocolMessage.fromJson(json)
        assertEquals(ProtocolMessage(type = "command", action = "ping"), decoded)
    }

    @Test
    fun missingTypeFieldReturnsNull() {
        val decoded = ProtocolMessage.fromJson("{\"action\":\"ping\"}")
        assertNull(decoded)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(ProtocolMessage.fromJson("not json at all"))
        assertNull(ProtocolMessage.fromJson(""))
    }

    @Test
    fun roundTripsEscapedCharacters() {
        val original = ProtocolMessage.command("say \"hi\" \\ bye")
        val decoded = ProtocolMessage.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun ackStateEchoesActionAlongsideStatus() {
        val original = ProtocolMessage(type = ProtocolMessage.TYPE_STATE, action = AliControlCommands.VOLUME_UP, status = "ok")
        val decoded = ProtocolMessage.fromJson(original.toJson())
        assertEquals(original, decoded)
        assertEquals("ok", decoded?.status)
        assertEquals(AliControlCommands.VOLUME_UP, decoded?.action)
    }
}
