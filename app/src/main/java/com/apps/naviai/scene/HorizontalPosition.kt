package com.apps.naviai.scene

enum class HorizontalPosition { LEFT, CENTER, RIGHT }

/**
 * Pure geometry classifier for where in the frame an object's bounding box
 * sits, coarsely bucketed into left/center/right (center band wider than a
 * plain three-way split, so a nearly-centered object doesn't flip-flop
 * between "center" and "left"/"right" frame to frame). Used to ground the
 * Object Search feature's "kiri/tengah/kanan" location claim in actual
 * detector geometry instead of leaving it entirely to the vision LLM to
 * guess from pixels. Same Robolectric-free-testing rationale as
 * [HazardTrigger]: takes a plain center-x float, not a RectF.
 */
object HorizontalPositionClassifier {
    fun classify(boxCenterX: Float, frameWidth: Int): HorizontalPosition {
        if (frameWidth <= 0) return HorizontalPosition.CENTER
        val fraction = boxCenterX / frameWidth
        return when {
            fraction < LEFT_THRESHOLD -> HorizontalPosition.LEFT
            fraction > RIGHT_THRESHOLD -> HorizontalPosition.RIGHT
            else -> HorizontalPosition.CENTER
        }
    }

    private const val LEFT_THRESHOLD = 0.4f
    private const val RIGHT_THRESHOLD = 0.6f
}
