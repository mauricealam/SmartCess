package com.smartcess.app.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Cost controls for vision input: compress captures under 1MB (hard cap 2MB),
 * first PDF page only for MVP. Only the final image ever goes to Spark.
 */
object ImageCompressor {
    const val TARGET_MAX_BYTES = 1_000_000
    const val HARD_MAX_BYTES = 2_000_000
    const val MAX_EDGE_PX = 1600
    /** MVP imports the first page only; multi-page arrives later. */
    const val MAX_PDF_PAGES = 1

    fun compress(jpegBytes: ByteArray): ByteArray {
        if (jpegBytes.size <= TARGET_MAX_BYTES) return jpegBytes
        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
        bitmap = downscale(bitmap)
        var quality = 85
        var out = encode(bitmap, quality)
        while (out.size > TARGET_MAX_BYTES && quality > 30) {
            quality -= 10
            out = encode(bitmap, quality)
        }
        if (out.size > HARD_MAX_BYTES) {
            val half = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            if (half != bitmap) bitmap.recycle()
            out = encode(half, 60)
            half.recycle()
        } else if (out.size != jpegBytes.size) {
            bitmap.recycle()
        }
        return out
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MAX_EDGE_PX) return bitmap
        val scale = MAX_EDGE_PX.toFloat() / edge
        val scaled = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true,
        )
        bitmap.recycle()
        return scaled
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
