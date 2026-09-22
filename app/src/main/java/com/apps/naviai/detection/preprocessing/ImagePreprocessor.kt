package com.apps.naviai.detection.preprocessing

/**
 * Computes the upright (post-rotation) display dimensions for a sensor
 * buffer, matching the rotation the native layer applies before inference.
 * Used by the camera/overlay layers to size the bounding-box canvas
 * correctly without duplicating the rotation decision in two places.
 */
object ImagePreprocessor {
    fun uprightSize(sensorWidth: Int, sensorHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return if (normalized == 90 || normalized == 270) {
            sensorHeight to sensorWidth
        } else {
            sensorWidth to sensorHeight
        }
    }
}
