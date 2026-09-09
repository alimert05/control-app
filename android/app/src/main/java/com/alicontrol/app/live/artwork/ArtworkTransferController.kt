package com.alicontrol.app.live.artwork

import java.security.MessageDigest

enum class ArtworkStatus { NONE, REQUESTED, RECEIVING, READY, FAILED }

data class MediaArtworkState(
    val artworkId: String? = null,
    val status: ArtworkStatus = ArtworkStatus.NONE,
    val image: ArtworkImage? = null
) {
    fun imageFor(mediaArtworkId: String?): ArtworkImage? =
        image.takeIf { status == ArtworkStatus.READY && artworkId != null && artworkId == mediaArtworkId }

    companion object { val NONE = MediaArtworkState() }
}

/**
 * Strict sequential receiver for one artwork at a time. The only allocation
 * proportional to wire input is a pre-sized buffer capped at 20 KiB.
 */
class ArtworkTransferController(
    private val cache: ArtworkCache,
    private val decoder: ArtworkDecoder,
    private val sendRequest: (ByteArray) -> Unit
) {
    private data class Transfer(
        val artworkId: String,
        val mimeType: String,
        val expectedSize: Int,
        val expectedChunks: Int,
        val buffer: ByteArray,
        var receivedBytes: Int = 0,
        var nextChunkIndex: Int = 0
    )

    var state: MediaArtworkState = MediaArtworkState.NONE
        private set

    private var transfer: Transfer? = null

    fun onTrackArtworkChanged(artworkId: String?): MediaArtworkState {
        val validId = artworkId?.takeIf(ArtworkProtocol::isValidArtworkId)
        if (validId == state.artworkId) return state

        transfer = null
        if (validId == null) {
            state = MediaArtworkState.NONE
            return state
        }

        val cached = cache.get(validId)
        if (cached != null) {
            state = MediaArtworkState(validId, ArtworkStatus.READY, cached)
            return state
        }

        state = MediaArtworkState(validId, ArtworkStatus.REQUESTED)
        ArtworkProtocol.requestBytes(validId)?.let(sendRequest)
        return state
    }

    /** Returns true when [data] belongs to the artwork protocol, even if rejected. */
    fun handleIncoming(data: ByteArray): Boolean {
        if (data.firstOrNull() == ArtworkProtocol.CHUNK_MAGIC) {
            handleChunk(ArtworkProtocol.chunkFrame(data))
            return true
        }
        return when (val message = ArtworkProtocol.controlMessage(data)) {
            is ArtworkControlMessage.Begin -> { handleBegin(message); true }
            is ArtworkControlMessage.End -> { handleEnd(message); true }
            null -> false
        }
    }

    fun disconnect() {
        transfer = null
        state = MediaArtworkState.NONE
    }

    private fun handleBegin(begin: ArtworkControlMessage.Begin) {
        if (begin.artworkId != state.artworkId || state.status == ArtworkStatus.READY) return
        if (transfer != null || state.status != ArtworkStatus.REQUESTED ||
            !ArtworkProtocol.isValidArtworkId(begin.artworkId) ||
            begin.mimeType != ArtworkProtocol.MIME_JPEG ||
            begin.size !in 1..MAX_COMPRESSED_BYTES ||
            begin.chunks !in 1..MAX_CHUNKS || begin.chunks > begin.size
        ) {
            failCurrent(begin.artworkId)
            return
        }
        transfer = Transfer(begin.artworkId, begin.mimeType, begin.size, begin.chunks, ByteArray(begin.size))
        state = MediaArtworkState(begin.artworkId, ArtworkStatus.RECEIVING)
    }

    private fun handleChunk(frame: ArtworkChunkFrame?) {
        val active = transfer
        if (frame == null) {
            if (active != null) failCurrent(active.artworkId)
            return
        }
        if (active == null || frame.artworkId != active.artworkId || frame.artworkId != state.artworkId) return
        if (frame.chunkIndex != active.nextChunkIndex || active.nextChunkIndex >= active.expectedChunks ||
            frame.payload.isEmpty() || frame.payload.size > active.expectedSize - active.receivedBytes
        ) {
            failCurrent(active.artworkId)
            return
        }

        System.arraycopy(frame.payload, 0, active.buffer, active.receivedBytes, frame.payload.size)
        active.receivedBytes += frame.payload.size
        active.nextChunkIndex++
    }

    private fun handleEnd(end: ArtworkControlMessage.End) {
        val active = transfer ?: return
        if (end.artworkId != active.artworkId || end.artworkId != state.artworkId) return
        transfer = null

        if (active.nextChunkIndex != active.expectedChunks || active.receivedBytes != active.expectedSize ||
            ArtworkHash.idFor(active.buffer) != active.artworkId
        ) {
            state = MediaArtworkState(active.artworkId, ArtworkStatus.FAILED)
            return
        }

        val image = decoder.decode(active.buffer, active.mimeType)
        if (image == null || !cache.put(active.artworkId, image)) {
            state = MediaArtworkState(active.artworkId, ArtworkStatus.FAILED)
            return
        }
        state = MediaArtworkState(active.artworkId, ArtworkStatus.READY, image)
    }

    private fun failCurrent(artworkId: String) {
        if (artworkId != state.artworkId) return
        transfer = null
        state = MediaArtworkState(artworkId, ArtworkStatus.FAILED)
    }

    companion object {
        const val MAX_COMPRESSED_BYTES = 20 * 1024
        const val MAX_CHUNKS = 512
    }
}

object ArtworkHash {
    fun idFor(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
