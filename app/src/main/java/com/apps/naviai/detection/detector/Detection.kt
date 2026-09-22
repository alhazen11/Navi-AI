package com.apps.naviai.detection.detector

import android.graphics.RectF

/**
 * A single detected object for one analyzed frame, in upright display-space
 * pixel coordinates (matches what CameraX's PreviewView shows, including
 * front-camera mirroring).
 */
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val timestamp: Long
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in [0,1], was $confidence" }
    }

    val isValidClass: Boolean get() = classId in 0 until NUM_COCO_CLASSES

    companion object {
        const val NUM_COCO_CLASSES = 80
    }
}
