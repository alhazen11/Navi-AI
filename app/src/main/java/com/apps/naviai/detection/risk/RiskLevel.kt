package com.apps.naviai.detection.risk

/**
 * Coarse obstacle-risk classification used to drive TTS urgency and the UI
 * risk indicator. This is a heuristic aid, not a collision-avoidance
 * guarantee -- see RiskAssessmentEngine's class doc.
 */
enum class RiskLevel(val priority: Int) {
    SAFE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4)
}
