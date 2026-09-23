package com.apps.naviai.audio

/**
 * Short spoken acknowledgment for when NAVI has accepted a request that
 * takes more than a moment to answer (an LLM vision/text call) -- purely
 * an audible "I'm working on it" cue for a user who can't see the
 * on-screen "Processing…"/"Analyzing…" text, distinct from the actual
 * result, which is spoken separately once it's ready. Not used for
 * Hazard Awareness (deliberately silent/unobtrusive -- see
 * [com.apps.naviai.ui.viewmodel.DetectionViewModel.checkForHazard]) or
 * quick local operations (Conversation Memory) that don't need it.
 */
object ProcessingCue {
    fun message(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Memproses..."
        AnnouncementLanguage.ENGLISH -> "Processing..."
    }
}
