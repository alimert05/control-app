package com.alicontrol.app.live.artwork

import org.junit.Assert.*
import org.junit.Test

class ArtworkProtocolTest {
    @Test fun requestContainsOnlyTypeAndValidatedId() {
        val bytes = ArtworkProtocol.requestBytes("0123456789abcdef")!!
        assertEquals("{\"type\":\"artwork_request\",\"artworkId\":\"0123456789abcdef\"}", String(bytes))
        assertNull(ArtworkProtocol.requestBytes("/tmp/picture.jpg"))
        assertNull(ArtworkProtocol.requestBytes("0123456789ABCDEF"))
    }

    @Test fun parsesBeginAndEndControls() {
        assertEquals(
            ArtworkControlMessage.Begin("0123456789abcdef", 123, 2, "image/jpeg"),
            ArtworkProtocol.controlMessage("{\"type\":\"artwork_begin\",\"artworkId\":\"0123456789abcdef\",\"size\":123,\"chunks\":2,\"mime\":\"image/jpeg\"}".toByteArray())
        )
        assertEquals(
            ArtworkControlMessage.End("0123456789abcdef"),
            ArtworkProtocol.controlMessage("{\"type\":\"artwork_end\",\"artworkId\":\"0123456789abcdef\"}".toByteArray())
        )
    }

    @Test fun parsesBinaryHeaderAndPayloadWithoutBase64() {
        val data = byteArrayOf(
            0xAC.toByte(), 0x01, 0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
            0x12, 0x34, 9, 8, 7
        )
        val frame = ArtworkProtocol.chunkFrame(data)!!
        assertEquals("0123456789abcdef", frame.artworkId)
        assertEquals(0x1234, frame.chunkIndex)
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.payload)
    }

    @Test fun rejectsMalformedBinaryFrames() {
        assertNull(ArtworkProtocol.chunkFrame(ByteArray(12)))
        assertNull(ArtworkProtocol.chunkFrame(byteArrayOf(0xAC.toByte(), 0x02) + ByteArray(11)))
    }

    @Test fun contentHashFixtureMatchesMacOSStrategy() {
        assertEquals("f09bb5200116bd26", ArtworkHash.idFor("ali-control-artwork-fixture".toByteArray()))
    }
}
