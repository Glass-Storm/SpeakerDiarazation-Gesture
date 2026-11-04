package com.google.mediapipe.examples.gesturerecognizer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Mock-data coverage for the glasses S3 JPEG path.
 *
 * Frames are synthesised here (coloured quadrants on a plain [Bitmap]); no
 * camera, device, or captured asset is involved. Robolectric runs the real
 * Android graphics native runtime so `Bitmap.compress` emits a genuine JPEG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegEncoderTest {

    private val encoder = JpegEncoder()

    @Test
    fun `downscales 640x480 to 320x240 and preserves 4_3 aspect`() {
        val encoded = encoder.encode(syntheticBitmap(640, 480))

        assertEquals(320, encoded.width)
        assertEquals(240, encoded.height)
        assertJpegMarkers(encoded.bytes)

        val decoded = decode(encoded.bytes)
        assertTrue("decoded width ${decoded.width} > 320", decoded.width <= 320)
        assertTrue("decoded height ${decoded.height} > 240", decoded.height <= 240)
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)
        val aspectDriftPx = abs(decoded.width - decoded.height * 4.0 / 3.0)
        assertTrue("aspect drift $aspectDriftPx px exceeds 1 px", aspectDriftPx <= 1.0)
    }

    @Test
    fun `does not upscale a bitmap smaller than the bounding box`() {
        val encoded = encoder.encode(syntheticBitmap(160, 120))

        assertEquals(160, encoded.width)
        assertEquals(120, encoded.height)

        val decoded = decode(encoded.bytes)
        assertEquals(160, decoded.width)
        assertEquals(120, decoded.height)
    }

    @Test
    fun `reports a clear failure when Bitmap compress returns false`() {
        val failingEncoder = JpegEncoder(JpegEncoder.BitmapCompressor { _, _, _ -> false })
        val bitmap = syntheticBitmap(64, 48)

        val error = assertThrows(IllegalStateException::class.java) {
            failingEncoder.encode(bitmap)
        }

        assertTrue(
            "unexpected message: ${error.message}",
            error.message.orEmpty().contains("Bitmap.compress(JPEG) returned false")
        )
        assertTrue("input bitmap should be recycled even on failure", bitmap.isRecycled)
    }

    private fun syntheticBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val halfWidth = width / 2
        val halfHeight = height / 2

        paint.color = Color.RED
        canvas.drawRect(Rect(0, 0, halfWidth, halfHeight), paint)
        paint.color = Color.GREEN
        canvas.drawRect(Rect(halfWidth, 0, width, halfHeight), paint)
        paint.color = Color.BLUE
        canvas.drawRect(Rect(0, halfHeight, halfWidth, height), paint)
        paint.color = Color.YELLOW
        canvas.drawRect(Rect(halfWidth, halfHeight, width, height), paint)
        return bitmap
    }

    private fun assertJpegMarkers(bytes: ByteArray) {
        assertTrue("JPEG too short: ${bytes.size} bytes", bytes.size >= 4)
        assertEquals("missing SOI marker", 0xFF, bytes[0].toInt() and 0xFF)
        assertEquals("missing SOI marker", 0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(
            "missing EOI marker",
            0xFF,
            bytes[bytes.size - 2].toInt() and 0xFF
        )
        assertEquals(
            "missing EOI marker",
            0xD9,
            bytes[bytes.size - 1].toInt() and 0xFF
        )
    }

    private fun decode(bytes: ByteArray): Bitmap {
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "BitmapFactory could not decode ${bytes.size} JPEG bytes"
        }
    }
}
