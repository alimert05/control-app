package com.alicontrol.app.live.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Small abstraction keeps transfer/cache tests independent from Android's native Bitmap runtime. */
interface ArtworkImage {
    val byteCount: Int
    val width: Int
    val height: Int
}

class BitmapArtworkImage(val bitmap: Bitmap) : ArtworkImage {
    override val byteCount: Int get() = bitmap.byteCount
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

fun interface ArtworkDecoder {
    fun decode(data: ByteArray, mimeType: String): ArtworkImage?
}

/** Decodes only the completed, hash-validated JPEG and rejects decompression-size surprises first. */
object AndroidArtworkDecoder : ArtworkDecoder {
    override fun decode(data: ByteArray, mimeType: String): ArtworkImage? {
        if (mimeType != ArtworkProtocol.MIME_JPEG) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth !in 1..MAX_DECODE_DIMENSION || bounds.outHeight !in 1..MAX_DECODE_DIMENSION) return null

        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        return BitmapArtworkImage(bitmap)
    }

    private const val MAX_DECODE_DIMENSION = 320
}
