package com.apps.naviai.detection.tracking

import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.detection.risk.RiskLevel

/**
 * A detection that has been associated with a stable identity across
 * frames, enriched with distance/motion/risk metadata.
 */
data class TrackedObject(
    val trackingId: Int,
    val detection: Detection,
    val estimatedDistanceMeters: Float?,
    val distanceConfidence: Float,
    val movementDirection: MovementDirection,
    val riskLevel: RiskLevel,
    val framesTracked: Int,
    val lastAnnouncedAtMs: Long?
)
