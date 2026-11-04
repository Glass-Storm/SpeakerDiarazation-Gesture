package com.google.mediapipe.examples.gesturerecognizer

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure, CameraX-free JPEG encoding for the hub's S3 still uplink.
 *
 * The runner owns capture and rotation; this class only owns the deterministic
 * part: aspect-preserving downscale to a 4:3 bounding box, then JPEG
 * compression. Keeping it free of [androidx.camera.core.ImageProxy] makes the
 * S3 path unit-testable with synthetic bitmaps.
 *
 * Ownership: like the inline code it replaces, [encode] recycles the supplied
 * bitmap (and any intermediate scaled bitmap) before returning.
 */
class JpegEncoder(
    private val compressor: BitmapCompressor = BitmapCompressor { bitmap, quality, output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    },
) {

    /** Injectable so the `Bitmap.compress` failure branch is testable. */
    fun interface BitmapCompressor {
        fun compress(bitmap: Bitmap, quality: Int, output: OutputStream): Boolean
    }

    data class EncodedJpeg(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    /**
     * Downscales [bitmap] only when it exceeds [maxWidth] or [maxHeight],
     * preserving its aspect ratio, then encodes it as JPEG at [quality].
     *
     * @throws IllegalStateException when the platform compressor reports failure.
     */
    fun encode(
        bitmap: Bitmap,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        maxHeight: Int = DEFAULT_MAX_HEIGHT,
        quality: Int = DEFAULT_QUALITY,
    ): EncodedJpeg {
        require(maxWidth > 0 && maxHeight > 0) {
            "maxWidth and maxHeight must be positive: ${maxWidth}x$maxHeight"
        }
        require(quality in 0..100) { "quality must be in 0..100: $quality" }

        var current = bitmap
        if (current.width > maxWidth || current.height > maxHeight) {
            val scale =
                min(
                    maxWidth.toFloat() / current.width,
                    maxHeight.toFloat() / current.height
                )
            val scaledWidth = max(1, (current.width * scale).roundToInt())
            val scaledHeight = max(1, (current.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(current, scaledWidth, scaledHeight, true)
            if (scaled !== current) {
                current.recycle()
                current = scaled
            }
        }

        val width = current.width
        val height = current.height
        val output = ByteArrayOutputStream()
        try {
            if (!compressor.compress(current, quality, output)) {
                throw IllegalStateException("Bitmap.compress(JPEG) returned false")
            }
        } finally {
            current.recycle()
        }
        return EncodedJpeg(output.toByteArray(), width, height)
    }

    companion object {
        const val DEFAULT_MAX_WIDTH = 320
        const val DEFAULT_MAX_HEIGHT = 240
        const val DEFAULT_QUALITY = 80
    }
}
