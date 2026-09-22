package com.apps.naviai.scene

/**
 * Recognizes whether a voice command (the text *after* the "NAVI" wake word,
 * see [com.apps.naviai.audio.VoiceCommandParser]) is asking for a
 * description of the user's surroundings. Simple keyword matching, not full
 * NLU -- kept as a pure, unit-testable function (mirrors
 * [com.apps.naviai.audio.VoiceCommandParser]'s style) rather than embedded
 * in [com.apps.naviai.ui.viewmodel.DetectionViewModel] so the trigger
 * phrases can be tested and extended without touching the dispatch code.
 */
object SceneDescriptionMatcher {
    private val KEYWORDS = listOf(
        // Indonesian
        "jelaskan lingkungan", "deskripsikan lingkungan", "jelaskan sekitar", "deskripsikan sekitar",
        "apa yang ada di sekitar", "apa yang ada di depan", "situasi sekitar", "keadaan sekitar",
        "di mana saya",
        // "NAVI, terangkan apa yang ada di ruangan ini" -- a room-focused
        // phrasing distinct from the more general "sekitar" (surroundings)
        // ones above.
        "terangkan apa yang ada", "apa yang ada di ruangan", "apa isi ruangan",
        "terangkan ruangan", "jelaskan ruangan", "deskripsikan ruangan", "ada apa di ruangan",
        // English
        "describe my surroundings", "describe the environment", "describe surroundings",
        "what's around me", "what is around me", "what's in front of me", "what is in front of me",
        "where am i", "what's in this room", "what is in this room", "describe this room"
    )

    fun matches(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (normalized.isEmpty()) return false
        return KEYWORDS.any { normalized.contains(it) }
    }
}
