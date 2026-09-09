package com.alicontrol.app.live.artwork

/** A tiny access-ordered memory cache bounded by both entry count and decoded bytes. */
class ArtworkCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES
) {
    private val entries = LinkedHashMap<String, ArtworkImage>(maxEntries, 0.75f, true)
    private var bytes = 0

    @Synchronized
    fun get(artworkId: String): ArtworkImage? = entries[artworkId]

    @Synchronized
    fun put(artworkId: String, image: ArtworkImage): Boolean {
        if (!ArtworkProtocol.isValidArtworkId(artworkId) || image.byteCount <= 0 || image.byteCount > maxBytes) return false
        entries.remove(artworkId)?.let { bytes -= it.byteCount }
        entries[artworkId] = image
        bytes += image.byteCount
        trim()
        return entries.containsKey(artworkId)
    }

    @Synchronized
    fun contains(artworkId: String): Boolean = entries.containsKey(artworkId)

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun byteSize(): Int = bytes

    private fun trim() {
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || bytes > maxBytes) && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= entry.value.byteCount
            iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 8
        const val DEFAULT_MAX_BYTES = 1024 * 1024
    }
}
