package com.apps.naviai.detection.distance

import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.domain.model.CameraParameters
import javax.inject.Inject

/** How a [DistanceEstimate] was derived -- surfaced so the UI/TTS can be honest about accuracy. */
enum class DistanceMethod {
    /** Calibrated focal length + known reference object height. Best case, still approximate. */
    CALIBRATED_KNOWN_HEIGHT,

    /** Uncalibrated default focal length + known reference object height. Rougher. */
    UNCALIBRATED_KNOWN_HEIGHT
}

/**
 * A single distance estimate. [confidence] is a 0..1 heuristic, not a
 * statistical error bound -- always present the value to the user as
 * approximate (e.g. "about 2 meters"), never as a precise measurement.
 */
data class DistanceEstimate(
    val distanceMeters: Float,
    val confidence: Float,
    val method: DistanceMethod
)

interface DistanceEstimator {
    /** Returns null when there isn't enough information to even guess (unknown class, degenerate box). */
    fun estimateDistance(detection: Detection, cameraParameters: CameraParameters): DistanceEstimate?
}

/**
 * Classic monocular "known object height" estimator:
 *
 *   distance = (realObjectHeightMeters * focalLengthPixels) / boundingBoxPixelHeight
 *
 * This assumes the object is roughly upright and fully in frame, viewed
 * close to head-on -- occlusion, unusual poses, and oblique/side views all
 * degrade accuracy, which is reflected in [DistanceEstimate.confidence]
 * rather than hidden.
 */
class MonocularHeightDistanceEstimator @Inject constructor() : DistanceEstimator {

    override fun estimateDistance(detection: Detection, cameraParameters: CameraParameters): DistanceEstimate? {
        val dims = ObjectDimensions.forLabel(detection.label) ?: return null

        val box = detection.boundingBox
        val boxHeightPx = box.height()
        if (boxHeightPx <= MIN_BOX_HEIGHT_PX) return null

        val rawDistance = (dims.typicalHeightMeters * cameraParameters.focalLengthPixels) / boxHeightPx
        if (!rawDistance.isFinite() || rawDistance <= 0f) return null
        val distance = rawDistance.coerceIn(MIN_PLAUSIBLE_METERS, MAX_PLAUSIBLE_METERS)

        var confidence = when (dims.reliability) {
            DimensionReliability.HIGH -> 0.85f
            DimensionReliability.MEDIUM -> 0.65f
            DimensionReliability.LOW -> 0.40f
        }

        // Calibration state.
        if (!cameraParameters.isCalibrated) confidence *= 0.7f

        // Small/far or noisy boxes are less trustworthy even if not rejected outright.
        if (boxHeightPx < SMALL_BOX_HEIGHT_PX) confidence *= 0.6f

        // Object touching a frame edge is likely partially occluded/cropped, which
        // biases the apparent height and therefore the distance estimate.
        val touchesTopOrBottom = box.top <= FRAME_EDGE_EPSILON_PX ||
            box.bottom >= cameraParameters.frameHeight - FRAME_EDGE_EPSILON_PX
        if (touchesTopOrBottom) confidence *= 0.5f

        // Unusually wide-for-tall boxes often indicate a side-on / non-frontal
        // view for classes whose reference height assumes a frontal pose.
        val aspectRatio = box.width() / boxHeightPx
        if (dims.reliability != DimensionReliability.LOW && aspectRatio > UNUSUAL_ASPECT_RATIO) {
            confidence *= 0.7f
        }

        // Low detector confidence compounds distance uncertainty.
        if (detection.confidence < 0.5f) confidence *= 0.85f

        val method = if (cameraParameters.isCalibrated) {
            DistanceMethod.CALIBRATED_KNOWN_HEIGHT
        } else {
            DistanceMethod.UNCALIBRATED_KNOWN_HEIGHT
        }

        return DistanceEstimate(distance, confidence.coerceIn(0.05f, 1f), method)
    }

    private companion object {
        const val MIN_BOX_HEIGHT_PX = 4f
        const val SMALL_BOX_HEIGHT_PX = 24f
        const val FRAME_EDGE_EPSILON_PX = 2f
        const val UNUSUAL_ASPECT_RATIO = 2.5f
        const val MIN_PLAUSIBLE_METERS = 0.15f
        const val MAX_PLAUSIBLE_METERS = 60f
    }
}
