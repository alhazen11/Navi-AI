package com.apps.naviai.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal speech-output contract. [AnnouncementManager] depends on this
 * instead of [TextToSpeechManager] directly so its cooldown/dedup/sentence
 * logic can be unit tested with a fake, without touching
 * android.speech.tts.TextToSpeech.
 */
interface Speaker {
    fun speak(text: String, flushQueue: Boolean, utteranceId: String)
    fun stop()

    /**
     * True while this is actually speaking, driven by real playback progress
     * rather than a duration estimate -- see [TextToSpeechManager.isSpeaking]'s
     * doc. [VoiceCommandManager] subscribes to this to auto-mute the mic
     * whenever NAVI's own voice is coming out of the speaker, so it doesn't
     * get fed back in as if the user had said it.
     */
    val isSpeaking: StateFlow<Boolean>
}
