package com.apps.naviai.navigation

/**
 * Recognizes "NAVI, kembali" / "NAVI, kembali ke home" -- a global reset:
 * stop whatever feature is currently running and return to
 * [com.apps.naviai.ui.screens.DetectionScreen], NAVI's home screen. Checked
 * from every place that currently owns a voice-command collector
 * ([com.apps.naviai.ui.viewmodel.DetectionViewModel],
 * [com.apps.naviai.recording.RecordingViewModel],
 * [com.apps.naviai.routenav.NavigationController]) plus Pro Mode's own
 * separate transcript source ([com.apps.naviai.voiceagent.ProModeCommandMatcher]
 * checks the same phrases independently, since Pro Mode's audio pipeline
 * isn't [com.apps.naviai.audio.VoiceCommandManager] at all) -- see
 * [GoHomeSignal]'s doc for why stopping things is each caller's own job,
 * not this matcher's.
 */
object GoHomeCommandMatcher {
    private val PHRASES = listOf(
        "kembali ke home", "kembali ke beranda", "balik ke home", "balik ke beranda", "kembali",
        "go home", "go to home", "go to the home screen", "back to home", "back home"
    )

    fun isMatch(command: String): Boolean {
        val normalized = command.trim().lowercase().trim('.', '!', '?')
        return normalized.isNotEmpty() && PHRASES.any { normalized == it || normalized.startsWith(it) || normalized.endsWith(it) }
    }
}
