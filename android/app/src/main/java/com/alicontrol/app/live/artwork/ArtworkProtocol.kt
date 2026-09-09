package com.alicontrol.app.live.artwork

import com.alicontrol.app.ble.JsonFields
import java.nio.charset.StandardCharsets

/** TASK-009A's closed, artwork-only wire protocol. No path, filename or URL fields exist. */
object ArtworkProtocol {
    const val TYPE_REQUEST = "artwork_request"
    const val TYPE_BEGIN = "artwork_begin"
    const val TYPE_END = "artwork_end"
    const val MIME_JPEG = "image/jpeg"

    const val ID_HEX_LENGTH = 16
    const val CHUNK_MAGIC: Byte = 0xAC.toByte()
    const val CHUNK_FRAME_TYPE: Byte = 0x01
    const val CHUNK_HEADER_BYTES = 12

    fun isValidArtworkId(id: String?): Boolean =
        id != null && id.length == ID_HEX_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    fun requestBytes(artworkId: String): ByteArray? {
        if (!isValidArtworkId(artworkId)) return null
        return "{\"type\":\"$TYPE_REQUEST\",\"artworkId\":\"$artworkId\"}"
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun controlMessage(data: ByteArray): ArtworkControlMessage? {
        if (data.isEmpty() || data[0] == CHUNK_MAGIC) return null
        val json = String(data, StandardCharsets.UTF_8).trim()
        if (!JsonFields.isCompleteObject(json)) return null
        return when (JsonFields.string(json, "type")) {
            TYPE_BEGIN -> ArtworkControlMessage.Begin(
                artworkId = JsonFields.string(json, "artworkId") ?: return null,
                size = JsonFields.int(json, "size") ?: return null,
                chunks = JsonFields.int(json, "chunks") ?: return null,
                mimeType = JsonFields.string(json, "mime") ?: return null
            )
            TYPE_END -> ArtworkControlMessage.End(
                artworkId = JsonFields.string(json, "artworkId") ?: return null
            )
            else -> null
        }
    }

    fun chunkFrame(data: ByteArray): ArtworkChunkFrame? {
        if (data.size <= CHUNK_HEADER_BYTES || data[0] != CHUNK_MAGIC || data[1] != CHUNK_FRAME_TYPE) return null
        val id = buildString(ID_HEX_LENGTH) {
            for (index in 2 until 10) append(String.format("%02x", data[index].toInt() and 0xff))
        }
        val chunkIndex = ((data[10].toInt() and 0xff) shl 8) or (data[11].toInt() and 0xff)
        return ArtworkChunkFrame(id, chunkIndex, data.copyOfRange(CHUNK_HEADER_BYTES, data.size))
    }
}

sealed class ArtworkControlMessage {
    data class Begin(val artworkId: String, val size: Int, val chunks: Int, val mimeType: String) : ArtworkControlMessage()
    data class End(val artworkId: String) : ArtworkControlMessage()
}

data class ArtworkChunkFrame(val artworkId: String, val chunkIndex: Int, val payload: ByteArray)
