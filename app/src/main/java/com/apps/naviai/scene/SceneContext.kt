package com.apps.naviai.scene

import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.detection.tracking.MovementDirection

/** One on-device-detected object, summarized for the LLM prompt -- see [ScenePromptBuilder]. */
class DetectedObjectSummary(
    val label: String,
    val distanceMeters: Float?,
    val movement: MovementDirection,
    val confidence: Float,
    val riskLevel: RiskLevel,
    /**
     * Coarse left/center/right position in the frame (see
     * [HorizontalPositionClassifier]) -- only populated by features that
     * need spatial grounding (currently just Object Search, to ground its
     * "kiri/tengah/kanan" answer in real geometry); null elsewhere since
     * Scene Understanding/Hazard Awareness don't use it.
     */
    val horizontalPosition: HorizontalPosition? = null
)

/**
 * Everything the Scene Understanding feature sends to the vision LLM for
 * one description request: the current camera view plus what the on-device
 * pipeline already knows, so the model doesn't have to (and shouldn't)
 * guess distances/motion from the image alone.
 *
 * Not a data class: [jpeg] is a large byte array that's never compared for
 * equality, so a generated equals()/hashCode() over it would be both wrong
 * (reference vs content semantics readers might assume) and wasteful.
 */
class SceneContext(
    val jpeg: ByteArray,
    val objects: List<DetectedObjectSummary>,
    val highestRisk: RiskLevel,
    val userCommandText: String
)
