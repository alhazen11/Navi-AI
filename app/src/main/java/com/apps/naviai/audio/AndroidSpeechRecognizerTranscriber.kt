package com.apps.naviai.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device speech-to-text via Android's built-in [SpeechRecognizer], used
 * only while Offline Mode is on (see [VoiceCommandManager]) -- unlike
 * [AssemblyAiBatchTranscriber], no audio or transcript this produces ever
 * leaves the device.
 *
 * **Why this requires Android 12 (API 31)**: [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 * is the only API that actually *guarantees* on-device-only recognition.
 * The plain [SpeechRecognizer.createSpeechRecognizer] plus
 * [RecognizerIntent.EXTRA_PREFER_OFFLINE] (available since API 23) is only
 * ever a *hint* -- several OEM/Google recognizer services silently fall
 * back to a network call if no offline language model is downloaded, which
 * would defeat the entire point of Offline Mode. Below API 31 there is no
 * public API that gives that guarantee at all, so [isSupported] returns
 * false and [VoiceCommandManager] leaves voice commands unavailable on
 * those devices instead of risking a silent network call while the user
 * explicitly asked to stay offline. As a defense-in-depth measure even on
 * 31+, [handleError] still treats [SpeechRecognizer.ERROR_NETWORK] /
 * [SpeechRecognizer.ERROR_NETWORK_TIMEOUT] as fatal rather than retrying,
 * in case a device's on-device recognizer implementation doesn't honor
 * that contract.
 *
 * **Why this isn't as smooth as the AssemblyAI path**: [SpeechRecognizer]
 * has no "always listening" mode -- one [SpeechRecognizer.startListening]
 * call captures at most one utterance and then finishes (result or error).
 * "Continuous" listening here means restarting it after every
 * result/silence/error via [restartListening] -- the same restart-loop
 * pattern this app's *original* voice implementation used before it was
 * replaced by AssemblyAI's continuous capture specifically because that
 * loop could clip the very start of the next utterance during the brief
 * restart gap (see this repo's README, section 9). That tradeoff is
 * accepted here because it's the only way to get an always-on voice
 * command experience that is also genuinely, verifiably offline.
 *
 * Wake-word matching itself is unchanged -- [VoiceCommandManager] runs the
 * exact same [VoiceCommandParser] over whatever text this engine (or
 * AssemblyAI's) produces, so "NAVI, ..." commands behave identically
 * regardless of which engine transcribed them.
 */
class AndroidSpeechRecognizerTranscriber(private val context: Context) : VoiceTranscriber {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var active = false
    @Volatile private var muted = false
    @Volatile private var wakeWordBoostEnabled = true
    @Volatile private var language: AnnouncementLanguage = AnnouncementLanguage.INDONESIAN
    private var onEvent: ((BatchTranscriptEvent) -> Unit)? = null

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isRecognitionAvailable(context)

    override fun setMuted(value: Boolean) {
        val wasMuted = muted
        muted = value
        if (value && !wasMuted) {
            // Cancel any in-progress capture immediately -- same reason
            // AssemblyAiBatchTranscriber mutes during TTS playback: don't
            // let NAVI pick up its own spoken prompt as a new "command".
            recognizer?.let { runCatching { it.cancel() } }
        } else if (!value && wasMuted && active) {
            mainHandler.post { restartListening() }
        }
    }

    override fun setLanguage(value: AnnouncementLanguage) {
        language = value
    }

    override fun setWakeWordBoostEnabled(value: Boolean) {
        wakeWordBoostEnabled = value
    }

    /** Must be called from the main thread -- [SpeechRecognizer] requires it. */
    @SuppressLint("MissingPermission")
    override fun start(apiKey: String?, language: AnnouncementLanguage, onEvent: (BatchTranscriptEvent) -> Unit) {
        stop()
        this.language = language
        this.onEvent = onEvent

        // isSupported() already requires SDK_INT >= S, but lint can't trace
        // that guard across the method boundary -- repeated inline so the
        // createOnDeviceSpeechRecognizer call below is provably guarded.
        if (!isSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onEvent(BatchTranscriptEvent.Error(unsupportedMessage(language), fatal = true))
            return
        }

        Log.d(TAG, "start() -- creating on-device recognizer, language=${language.locale.toLanguageTag()}")
        val instance = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        instance.setRecognitionListener(createListener())
        recognizer = instance
        active = true
        muted = false
        restartListening()
    }

    override fun stop() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.apply {
            runCatching { cancel() }
            runCatching { destroy() }
        }
        recognizer = null
        onEvent = null
    }

    private fun restartListening() {
        if (!active || muted) return
        val instance = recognizer ?: return
        runCatching { instance.startListening(buildIntent()) }
            .onSuccess { Log.d(TAG, "startListening() called") }
            .onFailure { t ->
                Log.w(TAG, "startListening failed, retrying shortly", t)
                mainHandler.postDelayed({ restartListening() }, RETRY_DELAY_MS)
            }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        if (wakeWordBoostEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Same rationale as AssemblyAiBatchTranscriber's word_boost --
            // bias toward the wake word ("NAVI" reliably misheard
            // otherwise). Only available from API 33; a no-op below that.
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, arrayListOf("navi", "navy"))
        }
    }

    private fun createListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            onEvent?.invoke(BatchTranscriptEvent.Listening)
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech -- mic picked up sound")
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            onEvent?.invoke(BatchTranscriptEvent.Transcribing)
        }

        override fun onError(error: Int) = handleError(error)

        override fun onResults(results: Bundle?) {
            val allResults = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = allResults?.firstOrNull()
            Log.d(TAG, "onResults: $allResults")
            if (!text.isNullOrBlank()) onEvent?.invoke(BatchTranscriptEvent.Result(text))
            // Posted, not called inline -- restarting from within the very
            // callback the recognizer just delivered is flaky on some OEM
            // recognizer implementations.
            mainHandler.post { restartListening() }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleError(error: Int) {
        Log.d(TAG, "onError: ${errorName(error)} ($error)")
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Ordinary silence/ambient noise with nothing recognizable
                // -- not a real error, just keep listening quietly, same as
                // AssemblyAiBatchTranscriber's VAD loop finding no speech.
                // NOTE: on a device where the on-device model for [language]
                // was never downloaded (Settings > System > Languages > On-
                // device speech recognition), some OEM implementations
                // report THIS error repeatedly instead of
                // ERROR_LANGUAGE_NOT_SUPPORTED/ERROR_LANGUAGE_UNAVAILABLE --
                // i.e. it silently never recognizes anything rather than
                // failing loudly. If onBeginningOfSpeech never logs despite
                // the user visibly talking, or this fires on every single
                // attempt with nothing ever recognized, that's the likely
                // cause -- not a bug in this class.
                mainHandler.post { restartListening() }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                mainHandler.postDelayed({ restartListening() }, RETRY_DELAY_MS)
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                Log.w(TAG, "On-device recognizer attempted network use (error=$error) -- refusing to continue under Offline Mode")
                active = false
                onEvent?.invoke(BatchTranscriptEvent.Error(networkFallbackMessage(language), fatal = true))
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                active = false
                onEvent?.invoke(BatchTranscriptEvent.Error(languageUnsupportedMessage(language), fatal = true))
            }
            else -> {
                Log.w(TAG, "SpeechRecognizer error=$error")
                active = false
                onEvent?.invoke(BatchTranscriptEvent.Error(genericErrorMessage(language, error), fatal = true))
            }
        }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        else -> "UNKNOWN"
    }

    private fun unsupportedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Pengenalan suara offline butuh Android 12 ke atas dan tidak tersedia di perangkat ini."
        AnnouncementLanguage.ENGLISH -> "On-device voice recognition needs Android 12+ and isn't available on this device."
    }

    private fun networkFallbackMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Pengenal suara perangkat ini mencoba memakai internet, jadi dihentikan demi Mode Offline."
        AnnouncementLanguage.ENGLISH -> "This device's recognizer tried to use the internet, so it was stopped to honor Offline Mode."
    }

    private fun languageUnsupportedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Pengenalan suara offline perangkat ini tidak mendukung bahasa yang dipilih."
        AnnouncementLanguage.ENGLISH -> "This device's on-device recognizer doesn't support the selected language."
    }

    private fun genericErrorMessage(language: AnnouncementLanguage, error: Int): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Pengenalan suara offline gagal (kode $error)."
        AnnouncementLanguage.ENGLISH -> "On-device voice recognition failed (code $error)."
    }

    private companion object {
        const val TAG = "AndroidSpeechRecognizer"
        const val RETRY_DELAY_MS = 500L
    }
}
