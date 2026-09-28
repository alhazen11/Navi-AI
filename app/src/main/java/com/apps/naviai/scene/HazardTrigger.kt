package com.apps.naviai.scene

import com.apps.naviai.detection.risk.RiskLevel
import kotlin.math.abs

/**
 * Pure geometry heuristic deciding whether a detected object is a
 * plausible walking-path hazard worth an LLM-generated caution (see
 * Hazard Awareness's acceptance criteria: "trigger jika ada hasil object
 * yang besar yang dapat menghalangi orang jalan"). Kept free of
 * android.graphics.RectF (unlike [com.apps.naviai.detection.detector.Detection],
 * which uses it) so this has a fast, Robolectric-free unit test surface --
 * callers pass the bounding box's plain width/height/center-x instead of
 * the RectF itself, same rationale as [SceneDescriptionMatcher].
 *
 * Deliberately conservative (large AND centered AND at least MEDIUM risk)
 * since a true match here fires a real network call to the user's LLM --
 * unlike the existing on-device [com.apps.naviai.audio.AnnouncementManager]
 * path, which stays fast/local at every risk level and remains the primary
 * safety mechanism; this is a slower, richer *supplement* to it, not a
 * replacement.
 */
object HazardTrigger {
    fun isBlockingHazard(
        label: String,
        boxWidth: Float,
        boxHeight: Float,
        boxCenterX: Float,
        frameWidth: Int,
        frameHeight: Int,
        riskLevel: RiskLevel
    ): Boolean {
        if (label !in HAZARD_ELIGIBLE_LABELS) return false
        if (frameWidth <= 0 || frameHeight <= 0) return false
        if (riskLevel.priority < RiskLevel.MEDIUM.priority) return false

        val frameArea = frameWidth.toFloat() * frameHeight.toFloat()
        val boxArea = boxWidth * boxHeight
        val areaFraction = if (frameArea > 0f) boxArea / frameArea else 0f
        if (areaFraction < MIN_AREA_FRACTION) return false

        val pathHalfWidth = frameWidth * PATH_WIDTH_FRACTION / 2f
        return abs(boxCenterX - frameWidth / 2f) <= pathHalfWidth
    }

    /** Large enough to plausibly block the whole walking path, not just clutter at the frame edge. */
    private const val MIN_AREA_FRACTION = 0.12f

    /** Same "directly ahead" width fraction RiskAssessmentEngine's default sensitivity uses. */
    private const val PATH_WIDTH_FRACTION = 0.45f

    /**
     * Only these COCO labels (see assets/models/coco.names) can ever fire a
     * hazard supplement, regardless of size/position/risk -- product
     * requirement is to keep the LLM call limited to plausible walking-path
     * obstacles/traffic actors/animals, not every large centered detection
     * (e.g. a "dining table" or "bed" should never trigger this). Two labels
     * requested alongside these ("scooter", "trash/bin") aren't part of the
     * model's 80-class COCO output, so they can't be matched here.
     */
    private val HAZARD_ELIGIBLE_LABELS = setOf(
        "person", "bicycle", "motorcycle", "car", "bus", "truck",
        "bench", "chair", "couch", "dog", "horse",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "potted plant",
        "backpack", "suitcase", "umbrella", "handbag",
        "skateboard", "sports ball", "kite", "surfboard",
        "bird", "cat", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "tv",
        "refrigerator"
    )
}
