package com.alicontrol.app.live.artwork

import org.junit.Assert.*
import org.junit.Test

class ArtworkCacheTest {
    private data class Image(override val byteCount: Int) : ArtworkImage {
        override val width = 160
        override val height = 160
    }

    @Test fun evictsLeastRecentlyUsedByEntryCount() {
        val cache = ArtworkCache(maxEntries = 2, maxBytes = 100)
        cache.put("0000000000000001", Image(10))
        cache.put("0000000000000002", Image(10))
        cache.get("0000000000000001")
        cache.put("0000000000000003", Image(10))
        assertTrue(cache.contains("0000000000000001"))
        assertFalse(cache.contains("0000000000000002"))
        assertEquals(2, cache.size())
    }

    @Test fun boundsDecodedMemoryAndRejectsSingleOversizedBitmap() {
        val cache = ArtworkCache(maxEntries = 8, maxBytes = 25)
        assertTrue(cache.put("0000000000000001", Image(15)))
        assertTrue(cache.put("0000000000000002", Image(15)))
        assertEquals(15, cache.byteSize())
        assertFalse(cache.put("0000000000000003", Image(26)))
        assertEquals(1, cache.size())
    }
}
