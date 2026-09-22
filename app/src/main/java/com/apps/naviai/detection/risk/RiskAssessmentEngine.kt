package com.apps.naviai.detection.risk

import android.graphics.RectF
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.TrackedObject
import kotlin.math.abs

enum class WalkingPathRegion { CENTER, PERIPHERAL_LEFT, PERIPHERAL_RIGHT }

/**
 * User-tunable knobs, sourced from Settings. [pathWidthFraction] is the
 * fraction of frame width considered "directly ahead"; [cautionMultiplier]
 * scales the whole risk score (Settings' "risk sensitivity" slider).
 */
data class RiskSensitivity(
    val pathWidthFraction: Float = 0.45f,
    val cautionMultiplier: Float = 1.0f
)

/**
 * Heuristic obstacle-risk scorer. This combines estimated distance, whether
 * the object sits in the user's likely walking path, its movement trend,
 * detection confidence, and how many frames it has persisted (to damp
 * one-frame flicker) into a single [RiskLevel].
 *
 * This is a heuristic aid for prioritizing what to announce, and does not
 * guarantee obstacle or collision avoidance -- distance is approximate,
 * the camera has a limited field of view, and objects outside the frame
 * are never seen at all.
 */
class RiskAssessmentEngine(var sensitivity: RiskSensitivity = RiskSensitivity()) {

    fun assess(tracked: TrackedObject, frameWidth: Int): RiskLevel {
        if (frameWidth <= 0) return RiskLevel.SAFE

        val region = regionOf(tracked.detection.boundingBox, frameWidth)
        val distance = tracked.estimatedDistanceMeters

        var score = if (distance != null && tracked.distanceConfidence > 0.2f) {
            distanceScore(distance)
        } else {
            // Uncertain distance is treated with moderate default caution
            // rather than either ignored or treated as imminent danger.
            UNCERTAIN_DISTANCE_SCORE
        }

        score += when (region) {
            WalkingPathRegion.CENTER -> 1.0f
            else -> 0.3f
        }

        score += when (tracked.movementDirection) {
            MovementDirection.APPROACHING -> 1.0f
            MovementDirection.MOVING_LEFT_TO_RIGHT, MovementDirection.MOVING_RIGHT_TO_LEFT ->
                if (region == WalkingPathRegion.CENTER) 0.6f else 0.2f
            MovementDirection.RECEDING -> -0.5f
            MovementDirection.STATIONARY, MovementDirection.UNKNOWN -> 0f
        }

        // Damp brand-new tracks so a single noisy frame can't spike to CRITICAL.
        if (tracked.framesTracked < 2) score *= 0.5f

        // Low-confidence detections carry through as lower urgency.
        score *= (0.5f + 0.5f * tracked.detection.confidence)

        score *= sensitivity.cautionMultiplier

        return levelFor(score)
    }

    private fun regionOf(box: RectF, frameWidth: Int): WalkingPathRegion {
        val frameCenterX = frameWidth / 2f
        val pathHalfWidth = frameWidth * sensitivity.pathWidthFraction / 2f
        val centerX = box.centerX()
        return when {
            abs(centerX - frameCenterX) <= pathHalfWidth -> WalkingPathRegion.CENTER
            centerX < frameCenterX -> WalkingPathRegion.PERIPHERAL_LEFT
            else -> WalkingPathRegion.PERIPHERAL_RIGHT
        }
    }

    private fun distanceScore(distanceMeters: Float): Float = when {
        distanceMeters < 0.8f -> 2.4f
        distanceMeters < 1.5f -> 1.4f
        distanceMeters < 3.0f -> 0.8f
        distanceMeters < 5.0f -> 0.3f
        else -> 0.0f
    }

    private fun levelFor(score: Float): RiskLevel = when {
        score >= 3.0f -> RiskLevel.CRITICAL
        score >= 2.0f -> RiskLevel.HIGH
        score >= 1.2f -> RiskLevel.MEDIUM
        score >= 0.5f -> RiskLevel.LOW
        else -> RiskLevel.SAFE
    }

    private companion object {
        const val UNCERTAIN_DISTANCE_SCORE = 0.6f
    }
}
