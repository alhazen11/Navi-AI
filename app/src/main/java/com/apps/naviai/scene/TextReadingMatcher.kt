package com.apps.naviai.scene

/**
 * Recognizes whether a voice command is asking NAVI to read text visible in
 * the camera view aloud (a medicine label, sign, business card, etc). Same
 * simple-keyword-matching approach as [SceneDescriptionMatcher] -- see its
 * doc for why this is a pure, unit-testable function rather than logic
 * embedded in the dispatching ViewModel.
 */
object TextReadingMatcher {
    private val KEYWORDS = listOf(
        // Indonesian
        "bacakan tulisan", "bacakan teks", "baca tulisan", "baca teks", "apa tulisan ini",
        "apa isi tulisan", "apa isi teks", "tolong bacakan",
        // English
        "read this text", "read the text", "read this sign", "read this label",
        "what does this say", "read that text"
    )

    fun matches(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (normalized.isEmpty()) return false
        return KEYWORDS.any { normalized.contains(it) }
    }
}
