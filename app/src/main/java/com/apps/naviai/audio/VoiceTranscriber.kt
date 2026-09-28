package com.apps.naviai.audio

/**
 * Common shape both speech-to-text engines this app can use expose to
 * [VoiceCommandManager], which switches between them based on the Offline
 * Mode setting: [AssemblyAiBatchTranscriber] (cloud, any Android version,
 * needs an API key) and [AndroidSpeechRecognizerTranscriber] (on-device,
 * Android 12+ only, no key needed). [VoiceCommandManager] talks to whichever
 * is active through this interface and never needs to know which one it is
 * beyond that switch.
 */
interface VoiceTranscriber {

    /** Whether this engine can be used at all on this device -- checked before [start]. */
    fun isSupported(): Boolean

    /**
     * Starts (or restarts) continuous capture. [apiKey] is ignored by an
     * on-device engine that doesn't need one; a null/blank key is only ever
     * meaningful to [AssemblyAiBatchTranscriber].
     */
    fun start(apiKey: String?, language: AnnouncementLanguage, onEvent: (BatchTranscriptEvent) -> Unit)

    fun stop()
    fun setMuted(value: Boolean)
    fun setLanguage(value: AnnouncementLanguage)
    fun setWakeWordBoostEnabled(value: Boolean)

    /**
     * Registers a callback fired the moment THE USER (not NAVI -- an engine must not report its own
     * muted-playback audio here) starts speaking, before any transcript exists. Drives
     * [VoiceCommandManager.userSpeaking], which the rest of the app uses to avoid talking over a
     * command already in progress -- see that property's doc.
     *
     * Default no-op: only [AndroidSpeechRecognizerTranscriber] implements it, because that is where
     * it matters. [AssemblyAiBatchTranscriber] mutes its own capture for the whole time it is
     * uploading an utterance, so nothing talks over it mid-command the same way.
     */
    fun setUserSpeechListener(listener: (() -> Unit)?) = Unit
}
