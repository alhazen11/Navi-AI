package com.apps.naviai.detection.preprocessing

import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure letterbox math: fits a `srcWidth x srcHeight` image into a square
 * `targetSize x targetSize` canvas without distorting aspect ratio, padding
 * the shorter side. This mirrors exactly what native_detector.cpp does in
 * C++ before running inference -- kept here in Kotlin so the coordinate
 * restoration math has a unit-testable reference implementation independent
 * of the native library being loadable.
 */
data class LetterboxParams(
    val scale: Float,
    val padLeft: Int,
    val padTop: Int,
    val padRight: Int,
    val padBottom: Int,
    val resizedWidth: Int,
    val resizedHeight: Int
) {
    /** Maps a box from the padded `targetSize` model-space back to source-image pixel space. */
    fun restoreToSource(box: RectF, srcWidth: Int, srcHeight: Int): RectF {
        val x1 = ((box.left - padLeft) / scale).coerceIn(0f, srcWidth.toFloat())
        val y1 = ((box.top - padTop) / scale).coerceIn(0f, srcHeight.toFloat())
        val x2 = ((box.right - padLeft) / scale).coerceIn(0f, srcWidth.toFloat())
        val y2 = ((box.bottom - padTop) / scale).coerceIn(0f, srcHeight.toFloat())
        return RectF(x1, y1, x2, y2)
    }
}

object Letterbox {
    fun compute(srcWidth: Int, srcHeight: Int, targetSize: Int): LetterboxParams {
        require(srcWidth > 0 && srcHeight > 0 && targetSize > 0) {
            "dimensions must be positive: src=${srcWidth}x$srcHeight target=$targetSize"
        }
        val scale = min(targetSize.toFloat() / srcWidth, targetSize.toFloat() / srcHeight)
        val newW = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val newH = (srcHeight * scale).roundToInt().coerceAtLeast(1)
        val padLeft = (targetSize - newW) / 2
        val padTop = (targetSize - newH) / 2
        val padRight = targetSize - newW - padLeft
        val padBottom = targetSize - newH - padTop
        return LetterboxParams(scale, padLeft, padTop, padRight, padBottom, newW, newH)
    }
}
