package com.apps.naviai.audio

/**
 * Minimal speech-output contract. [AnnouncementManager] depends on this
 * instead of [TextToSpeechManager] directly so its cooldown/dedup/sentence
 * logic can be unit tested with a fake, without touching
 * android.speech.tts.TextToSpeech.
 */
interface Speaker {
    fun speak(text: String, flushQueue: Boolean, utteranceId: String)
    fun stop()
}
