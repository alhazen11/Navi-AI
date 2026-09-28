package com.apps.naviai.recording

/**
 * Recognizes the "start/stop recording a route" voice commands. Same
 * pragmatic keyword-matching approach as the other feature matchers in
 * this app (e.g. [com.apps.naviai.scene.SceneDescriptionMatcher]) -- not
 * full NLU.
 */
object RouteRecordingMatcher {
    private val START_KEYWORDS = listOf(
        "mulai merekam jalan", "mulai rekam jalan", "mulai merekam rute", "mulai rekam rute",
        "start recording route", "start route recording", "start recording the route",
        "start recording road", "start road recording", "start recording the road"
    )
    private val STOP_KEYWORDS = listOf(
        "stop merekam jalan", "stop merekam rute", "berhenti merekam jalan", "berhenti merekam rute",
        "stop recording route", "stop route recording", "stop recording the route",
        "stop recording road", "stop road recording", "stop recording the road"
    )

    fun isStart(command: String): Boolean {
        val normalized = command.trim().lowercase()
        return normalized.isNotEmpty() && START_KEYWORDS.any { normalized.contains(it) }
    }

    fun isStop(command: String): Boolean {
        val normalized = command.trim().lowercase()
        return normalized.isNotEmpty() && STOP_KEYWORDS.any { normalized.contains(it) }
    }
}
