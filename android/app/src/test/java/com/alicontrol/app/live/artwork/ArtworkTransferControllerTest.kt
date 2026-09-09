package com.alicontrol.app.live.artwork

import org.junit.Assert.*
import org.junit.Test

class ArtworkTransferControllerTest {
    private data class Image(override val byteCount: Int = 100) : ArtworkImage {
        override val width = 160
        override val height = 160
    }

    private class Fixture {
        val sent = mutableListOf<ByteArray>()
        val decoded = mutableListOf<ByteArray>()
        val image = Image()
        val cache = ArtworkCache(maxEntries = 8, maxBytes = 1000)
        val controller = ArtworkTransferController(cache, decoder = { data, mime ->
            if (mime == "image/jpeg") image.also { decoded += data.copyOf() } else null
        }, sendRequest = { sent += it })

        fun begin(id: String, size: Int, chunks: Int) = controller.handleIncoming(
            "{\"type\":\"artwork_begin\",\"artworkId\":\"$id\",\"size\":$size,\"chunks\":$chunks,\"mime\":\"image/jpeg\"}".toByteArray())
        fun end(id: String) = controller.handleIncoming(
            "{\"type\":\"artwork_end\",\"artworkId\":\"$id\"}".toByteArray())
        fun chunk(id: String, index: Int, payload: ByteArray): Boolean {
            val idBytes = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return controller.handleIncoming(byteArrayOf(0xAC.toByte(), 0x01) + idBytes + byteArrayOf((index ushr 8).toByte(), index.toByte()) + payload)
        }
    }

    @Test fun requestsOnlyOnCacheMissAndNotOnRepeatedTrackTicks() {
        val f = Fixture()
        val id = "0123456789abcdef"
        f.controller.onTrackArtworkChanged(id)
        f.controller.onTrackArtworkChanged(id)
        assertEquals(1, f.sent.size)

        val cached = Fixture()
        cached.cache.put(id, cached.image)
        assertEquals(ArtworkStatus.READY, cached.controller.onTrackArtworkChanged(id).status)
        assertTrue(cached.sent.isEmpty())
    }

    @Test fun validSequentialTransferIsHashedDecodedCachedAndPublishedOnlyAtEnd() {
        val f = Fixture()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val id = ArtworkHash.idFor(bytes)
        f.controller.onTrackArtworkChanged(id)
        f.begin(id, bytes.size, 2)
        f.chunk(id, 0, bytes.copyOfRange(0, 3))
        assertEquals(ArtworkStatus.RECEIVING, f.controller.state.status)
        assertNull(f.controller.state.image)
        f.chunk(id, 1, bytes.copyOfRange(3, 5))
        f.end(id)
        assertEquals(ArtworkStatus.READY, f.controller.state.status)
        assertSame(f.image, f.cache.get(id))
        assertArrayEquals(bytes, f.decoded.single())
    }

    @Test fun rejectsOversizeBeforeAllocationAndWrongChunkOrder() {
        val f = Fixture()
        val id = "0123456789abcdef"
        f.controller.onTrackArtworkChanged(id)
        f.begin(id, ArtworkTransferController.MAX_COMPRESSED_BYTES + 1, 1)
        assertEquals(ArtworkStatus.FAILED, f.controller.state.status)

        val g = Fixture()
        g.controller.onTrackArtworkChanged(id)
        g.begin(id, 2, 2)
        g.chunk(id, 1, byteArrayOf(1))
        assertEquals(ArtworkStatus.FAILED, g.controller.state.status)
    }

    @Test fun incompleteOrHashMismatchedTransferNeverDecodes() {
        val f = Fixture()
        val id = "0123456789abcdef"
        f.controller.onTrackArtworkChanged(id)
        f.begin(id, 2, 2)
        f.chunk(id, 0, byteArrayOf(1))
        f.end(id)
        assertEquals(ArtworkStatus.FAILED, f.controller.state.status)
        assertTrue(f.decoded.isEmpty())

        val g = Fixture()
        g.controller.onTrackArtworkChanged(id)
        g.begin(id, 1, 1)
        g.chunk(id, 0, byteArrayOf(1))
        g.end(id)
        assertEquals(ArtworkStatus.FAILED, g.controller.state.status)
        assertTrue(g.decoded.isEmpty())
    }

    @Test fun duplicateChunkFailsAndAValidatedButUndecodableImageFailsSafely() {
        val f = Fixture()
        val bytes = byteArrayOf(1, 2)
        val id = ArtworkHash.idFor(bytes)
        f.controller.onTrackArtworkChanged(id)
        f.begin(id, 2, 2)
        f.chunk(id, 0, byteArrayOf(1))
        f.chunk(id, 0, byteArrayOf(2))
        assertEquals(ArtworkStatus.FAILED, f.controller.state.status)

        val cache = ArtworkCache()
        val rejecting = ArtworkTransferController(cache, decoder = { _, _ -> null }, sendRequest = {})
        rejecting.onTrackArtworkChanged(id)
        rejecting.handleIncoming("{\"type\":\"artwork_begin\",\"artworkId\":\"$id\",\"size\":2,\"chunks\":1,\"mime\":\"image/jpeg\"}".toByteArray())
        val idBytes = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        rejecting.handleIncoming(byteArrayOf(0xAC.toByte(), 0x01) + idBytes + byteArrayOf(0, 0) + bytes)
        rejecting.handleIncoming("{\"type\":\"artwork_end\",\"artworkId\":\"$id\"}".toByteArray())
        assertEquals(ArtworkStatus.FAILED, rejecting.state.status)
        assertFalse(cache.contains(id))
    }

    @Test fun trackChangeCancelsOldTransferAndIgnoresLateFrames() {
        val f = Fixture()
        val oldBytes = byteArrayOf(1, 2)
        val oldId = ArtworkHash.idFor(oldBytes)
        val newId = "fedcba9876543210"
        f.controller.onTrackArtworkChanged(oldId)
        f.begin(oldId, 2, 1)
        f.controller.onTrackArtworkChanged(newId)
        f.chunk(oldId, 0, oldBytes)
        f.end(oldId)
        assertEquals(newId, f.controller.state.artworkId)
        assertEquals(ArtworkStatus.REQUESTED, f.controller.state.status)
        assertTrue(f.decoded.isEmpty())
        assertEquals(2, f.sent.size)
    }

    @Test fun disconnectDropsReceiveBufferButRetainsReusableCache() {
        val f = Fixture()
        val id = "0123456789abcdef"
        f.cache.put(id, f.image)
        f.controller.onTrackArtworkChanged(id)
        f.controller.disconnect()
        assertEquals(MediaArtworkState.NONE, f.controller.state)
        assertSame(f.image, f.cache.get(id))
        f.controller.onTrackArtworkChanged(id)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun uiSelectionNeverReturnsArtworkForDifferentTrack() {
        val image = Image()
        val state = MediaArtworkState("0123456789abcdef", ArtworkStatus.READY, image)
        assertSame(image, state.imageFor("0123456789abcdef"))
        assertNull(state.imageFor("fedcba9876543210"))
    }
}
