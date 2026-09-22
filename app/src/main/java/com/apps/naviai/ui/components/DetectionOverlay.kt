package com.apps.naviai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apps.naviai.detection.tracking.TrackedObject
import kotlin.math.roundToInt

/**
 * Draws bounding boxes for [trackedObjects] over the camera preview.
 *
 * [imageWidth]/[imageHeight] must be the *upright* display-space dimensions
 * (see ImagePreprocessor.uprightSize) -- the same space the native detector
 * already returns coordinates in (rotation and front-camera mirroring are
 * both resolved before boxes reach this composable), so this only needs a
 * uniform width/height scale from image-space to the Canvas's own size.
 */
@Composable
fun DetectionOverlay(
    trackedObjects: List<TrackedObject>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas

        val scaleX = size.width / imageWidth.toFloat()
        val scaleY = size.height / imageHeight.toFloat()
        val strokeWidth = 3.dp.toPx()
        val textSizePx = 14.sp.toPx()

        val textPaint = android.graphics.Paint().apply {
            this.textSize = textSizePx
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        trackedObjects.forEach { tracked ->
            val box = tracked.detection.boundingBox
            val left = box.left.coerceIn(0f, imageWidth.toFloat()) * scaleX
            val top = box.top.coerceIn(0f, imageHeight.toFloat()) * scaleY
            val right = box.right.coerceIn(0f, imageWidth.toFloat()) * scaleX
            val bottom = box.bottom.coerceIn(0f, imageHeight.toFloat()) * scaleY
            if (right <= left || bottom <= top) return@forEach

            val color = tracked.riskLevel.color()

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = strokeWidth)
            )

            val distanceSuffix = tracked.estimatedDistanceMeters?.let { " ~${formatDistance(it)}" } ?: ""
            val labelText = "${tracked.detection.label} ${(tracked.detection.confidence * 100).roundToInt()}%$distanceSuffix"

            textPaint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                labelText,
                left,
                (top - 8f).coerceAtLeast(textSizePx),
                textPaint
            )
        }
    }
}

private fun formatDistance(distanceMeters: Float): String =
    if (distanceMeters < 1f) "<1m" else "${"%.1f".format(distanceMeters)}m"
