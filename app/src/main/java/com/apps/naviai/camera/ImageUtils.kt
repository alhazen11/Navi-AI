package com.apps.naviai.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Converts a CameraX YUV_420_888 [ImageProxy] into a packed RGBA8888 byte
 * array, the format the native NCNN pipeline expects. Handles arbitrary
 * row/pixel strides (some devices pad rows), not just the tightly-packed
 * case.
 */
object ImageUtils {
    fun yuv420ToRgba(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 4)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val y = yPlane.buffer
        val u = uPlane.buffer
        val v = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        for (py in 0 until height) {
            val yRowOffset = py * yRowStride
            val uvRowY = py / 2
            val uRowOffset = uvRowY * uRowStride
            val vRowOffset = uvRowY * vRowStride

            for (px in 0 until width) {
                val yIndex = yRowOffset + px
                val uvX = px / 2
                val uIndex = uRowOffset + uvX * uPixelStride
                val vIndex = vRowOffset + uvX * vPixelStride

                val yy = y.getUnsigned(yIndex)
                val uu = u.getUnsigned(uIndex) - 128
                val vv = v.getUnsigned(vIndex) - 128

                val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)

                val i = (py * width + px) * 4
                out[i] = r.toByte()
                out[i + 1] = g.toByte()
                out[i + 2] = b.toByte()
                out[i + 3] = 255.toByte()
            }
        }
        return out
    }

    private fun ByteBuffer.getUnsigned(index: Int): Int = get(index).toInt() and 0xFF

    /**
     * Encodes a raw sensor-space RGBA buffer (as produced by [yuv420ToRgba])
     * into an upright, correctly-mirrored JPEG -- i.e. the same orientation
     * the user actually sees in the preview, matching [com.apps.naviai.detection.detector.Detection]'s
     * upright display-space coordinates. Used for the Scene Understanding
     * feature to hand a still frame to a vision LLM; NOT called per-frame
     * (only when a description is actually requested), since bitmap
     * creation + JPEG compression is too expensive to do on every frame.
     */
    fun rgbaToUprightJpeg(rgba: ByteArray, width: Int, height: Int, rotationDegrees: Int, mirror: Boolean, quality: Int = 80): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))

        val needsTransform = rotationDegrees != 0 || mirror
        val oriented = if (needsTransform) {
            val matrix = Matrix().apply {
                if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(source, 0, 0, width, height, matrix, true).also { source.recycle() }
        } else {
            source
        }

        val out = ByteArrayOutputStream()
        oriented.compress(Bitmap.CompressFormat.JPEG, quality, out)
        oriented.recycle()
        return out.toByteArray()
    }
}
