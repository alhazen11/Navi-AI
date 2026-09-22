package com.apps.naviai.camera

import androidx.camera.core.ImageProxy
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
}
