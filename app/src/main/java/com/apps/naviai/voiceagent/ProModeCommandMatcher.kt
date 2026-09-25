package com.apps.naviai.voiceagent

/**
 * Recognizes the two phrases that switch NAVI AI in and out of Pro Mode
 * (Conversation Mode). [isEnter] runs through the regular wake-word-gated
 * pipeline (`DetectionViewModel.handleVoiceCommand`), same as every other
 * command. [isExit] is checked client-side against Pro Mode's own Voice
 * Agent session transcripts instead of being modeled as a tool the agent's
 * LLM might choose to call -- exiting is safety/accessibility-relevant
 * enough (it's the only way back to normal wake-word listening and
 * automatic hazard announcements) that it shouldn't depend on the LLM
 * reliably picking the right tool.
 */
object ProModeCommandMatcher {
    private val ENTER_PHRASES = listOf("mode pro", "pro mode")
    private val EXIT_PHRASES = listOf(
        "matikan mode pro", "keluar mode pro", "keluar dari mode pro", "nonaktifkan mode pro",
        "turn off pro mode", "exit pro mode", "disable pro mode", "stop pro mode"
    )

    /** @param command the text following the wake word (see [com.apps.naviai.audio.VoiceCommandParser]). */
    fun isEnter(command: String): Boolean = matches(command, ENTER_PHRASES)

    /** @param text raw recognized text -- checked without requiring a wake-word prefix, since Pro Mode's own session isn't wake-word-gated. */
    fun isExit(text: String): Boolean = matches(text, EXIT_PHRASES)

    private fun matches(text: String, phrases: List<String>): Boolean {
        val normalized = text.trim().lowercase().trim('.', '!', '?')
        return phrases.any { normalized == it || normalized.endsWith(it) || normalized.startsWith(it) }
    }
}
