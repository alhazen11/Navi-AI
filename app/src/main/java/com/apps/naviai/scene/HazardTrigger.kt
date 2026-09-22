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
        boxWidth: Float,
        boxHeight: Float,
        boxCenterX: Float,
        frameWidth: Int,
        frameHeight: Int,
        riskLevel: RiskLevel
    ): Boolean {
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
}
