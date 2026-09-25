package com.apps.naviai.audio

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.apps.naviai.core.common.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceCommandStatus {
    /** Not started yet -- before the first [VoiceCommandManager.startContinuousListening] call. */
    IDLE,
    LISTENING,
    /** An utterance was captured and is being uploaded/transcribed -- see [AssemblyAiBatchTranscriber]. */
    TRANSCRIBING,
    RECOGNIZED,
    UNCLEAR,
    /** User explicitly paused listening (mic tapped off); won't auto-restart until resumed. */
    PAUSED,
    /** No AssemblyAI API key configured (Settings), or the microphone/capture failed to start. */
    UNAVAILABLE
}

data class VoiceCommandUiState(
    val status: VoiceCommandStatus = VoiceCommandStatus.IDLE,
    /** Exactly what the recognizer heard, unmodified -- always shown on screen once available. */
    val rawRecognizedText: String? = null,
    /** The text following the wake word, once one was detected. Null until then. */
    val command: String? = null,
    /** A status/prompt message for the UI (e.g. asking the user to repeat), or null when none applies. */
    val message: String? = null,
    /**
     * Monotonically increasing, set only when [status] becomes [VoiceCommandStatus.RECOGNIZED]
     * for a *newly* recognized command. Lets a dispatcher (e.g. matching
     * [command] against a known intent like Scene Understanding) tell a
     * fresh recognition apart from re-collecting the same still-current
     * state (a late [kotlinx.coroutines.flow.StateFlow] subscriber replays
     * the current value, which is not a new event to act on).
     */
    val eventId: Long = 0
)

/**
 * Always-on voice command capture. Once started, this keeps the microphone
 * continuously monitored via local voice-activity detection and sends each
 * detected utterance to AssemblyAI's async transcription API (see
 * [AssemblyAiBatchTranscriber] for why this isn't realtime streaming --
 * short version: AssemblyAI's realtime API doesn't support Indonesian at
 * all, and this app needs both Indonesian and English) -- no push button
 * needed.
 *
 * Only speech starting with a wake word is treated as an addressed command
 * (see [VoiceCommandParser]); ordinary silence and ambient speech that
 * never mentions the wake word are ignored quietly, both on screen and out
 * loud, so NAVI never nags. The spoken "please repeat" prompt is reserved
 * for speech that mentions the wake word but couldn't be cleanly parsed
 * into a command -- a genuine garbled attempt to address NAVI.
 *
 * This class does NOT execute commands -- it only captures and surfaces
 * the recognized text. Command dispatch is a future step.
 *
 * **Engine switching (Offline Mode)**: [startContinuousListening]'s
 * `offlineModeEnabled` parameter picks between two [VoiceTranscriber]
 * implementations -- [AssemblyAiBatchTranscriber] (cloud, the default) and
 * [AndroidSpeechRecognizerTranscriber] (on-device, Android 12+ only). Both
 * emit the same [BatchTranscriptEvent]s, so [handleEvent] and every wake-
 * word/dispatch concern below are completely unaware of which one is
 * active. Switching engines always stops the previously active one first
 * (see [startContinuousListening]) -- only one may hold the microphone at
 * a time.
 *
 * **Automatic fallback when there's no internet**: the *effective* offline
 * decision is `offlineModeEnabled || !`[NetworkMonitor.hasInternet] (see
 * [effectiveOfflineMode]), not just the raw setting -- a user who never
 * turned Offline Mode on but genuinely has no connectivity right now (a
 * dead zone, airplane mode, walking into a basement) gets the same
 * on-device fallback rather than a cloud engine that would just fail/hang.
 * [networkMonitor]'s listener re-runs [startContinuousListening] with
 * whatever language/API key/setting were last used whenever connectivity
 * actually flips (see [onConnectivityChanged]) -- this reacts to
 * connectivity changing *during* an active session, not only at the moment
 * listening starts, since that's the realistic way connectivity changes
 * for someone moving around outdoors.
 */
@Singleton
class VoiceCommandManager @Inject constructor(
    private val speaker: Speaker,
    private val networkMonitor: NetworkMonitor,
    httpClient: OkHttpClient,
    @ApplicationContext context: Context
) {
    private val cloudTranscriber: VoiceTranscriber = AssemblyAiBatchTranscriber(httpClient)
    private val onDeviceTranscriber: VoiceTranscriber = AndroidSpeechRecognizerTranscriber(context)

    /** Whichever engine is currently active -- see the class doc's "Engine switching" note. */
    private var transcriber: VoiceTranscriber = cloudTranscriber

    private val _state = MutableStateFlow(VoiceCommandUiState())
    val state: StateFlow<VoiceCommandUiState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var language = AnnouncementLanguage.INDONESIAN
    private var apiKey: String? = null
    private var active = false
    private var recognizedEventCounter = 0L
    private var pendingRawUtteranceCallback: ((String) -> Unit)? = null

    // ---- Automatic no-internet fallback -- see the class doc's matching section. ----
    /** Exactly the arguments [startContinuousListening] was last called with, unconditionally -- unlike [apiKey] (only meaningfully updated on the cloud-engine branch), these let [onConnectivityChanged] reconstruct the same call regardless of which engine was active at the time. */
    private var lastLanguageArgument = AnnouncementLanguage.INDONESIAN
    private var lastApiKeyArgument: String? = null
    private var lastOfflineModeSetting = false
    private val networkCallback: ConnectivityManager.NetworkCallback? =
        networkMonitor.registerListener { mainHandler.post { onConnectivityChanged() } }

    /** True when Offline Mode should actually be treated as on right now -- the user's own setting, OR there's genuinely no internet connectivity regardless of what they set. */
    private fun effectiveOfflineMode(offlineModeEnabled: Boolean): Boolean =
        offlineModeEnabled || !networkMonitor.hasInternet()

    /** Connectivity actually changed (see [networkMonitor]'s listener) -- re-run with whatever this was last started with, so an active session picks up the new effective engine choice without the caller needing to do anything. */
    private fun onConnectivityChanged() {
        if (!active) return
        Log.d(TAG, "Connectivity changed, hasInternet=${networkMonitor.hasInternet()} -- re-evaluating voice command engine")
        startContinuousListening(lastLanguageArgument, lastApiKeyArgument, lastOfflineModeSetting)
    }

    /**
     * Must be called from the main thread. Idempotent while already active
     * with the same key/engine -- but if [apiKey] differs from the one
     * currently connected with (e.g. the key just arrived from a settings
     * load that raced with an earlier call made before it was ready, or the
     * user edited it), reconnects with the new one instead of silently
     * no-oping.
     *
     * [offlineModeEnabled] is the raw Settings toggle, not necessarily the
     * engine actually used -- see [effectiveOfflineMode] (the class doc's
     * "Automatic fallback" section): true, OR no real internet connectivity
     * right now regardless of the toggle, routes through [onDeviceTranscriber]
     * ([AndroidSpeechRecognizerTranscriber], Android 12+ only -- see its
     * class doc for why older devices can't offer a verified-offline engine
     * at all); otherwise uses [cloudTranscriber] as before. A change either
     * way always stops whichever engine was previously active first -- only
     * one may hold the microphone at a time.
     */
    fun startContinuousListening(language: AnnouncementLanguage, apiKey: String?, offlineModeEnabled: Boolean = false) {
        this.language = language
        lastLanguageArgument = language
        lastApiKeyArgument = apiKey
        lastOfflineModeSetting = offlineModeEnabled

        val effectiveOffline = effectiveOfflineMode(offlineModeEnabled)
        val fallenBackDueToNoInternet = !offlineModeEnabled && effectiveOffline
        val targetTranscriber: VoiceTranscriber = if (effectiveOffline) onDeviceTranscriber else cloudTranscriber
        if (targetTranscriber !== transcriber) {
            transcriber.stop()
            transcriber = targetTranscriber
            active = false
        }

        if (effectiveOffline) {
            if (!transcriber.isSupported()) {
                active = false
                val message = if (fallenBackDueToNoInternet) noInternetUnsupportedMessage(language) else onDeviceUnsupportedMessage(language)
                _state.value = VoiceCommandUiState(status = VoiceCommandStatus.UNAVAILABLE, message = message)
                return
            }
            if (active) {
                // Already listening on-device -- just make sure a
                // mid-session language change in Settings takes effect on
                // the next utterance.
                transcriber.setLanguage(language)
                return
            }
            active = true
            if (fallenBackDueToNoInternet) Log.d(TAG, "No internet connectivity detected -- using on-device recognizer even though Offline Mode isn't manually on")
            transcriber.start(null, language) { event -> mainHandler.post { handleEvent(event) } }
            return
        }

        val keyChanged = apiKey != this.apiKey
        this.apiKey = apiKey

        if (apiKey.isNullOrBlank()) {
            active = false
            _state.value = VoiceCommandUiState(status = VoiceCommandStatus.UNAVAILABLE, message = missingApiKeyMessage(language))
            return
        }

        if (active && !keyChanged) {
            // Already connected -- but the language may have just changed
            // in Settings, so make sure the next utterance uses it even
            // without a full reconnect.
            transcriber.setLanguage(language)
            return
        }
        active = true
        transcriber.start(apiKey, language) { event -> mainHandler.post { handleEvent(event) } }
    }

    /** Stops listening entirely; no further auto-restart until [startContinuousListening] is called again. */
    fun stopListening() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        pendingRawUtteranceCallback = null
        transcriber.setWakeWordBoostEnabled(true)
        transcriber.stop()
        _state.update { current ->
            if (current.status == VoiceCommandStatus.UNAVAILABLE) current else current.copy(status = VoiceCommandStatus.PAUSED, message = null)
        }
    }

    fun reset() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        pendingRawUtteranceCallback = null
        transcriber.setWakeWordBoostEnabled(true)
        transcriber.stop()
        _state.value = VoiceCommandUiState()
    }

    private fun handleEvent(event: BatchTranscriptEvent) {
        if (!active) return
        when (event) {
            is BatchTranscriptEvent.Listening -> _state.update { it.copy(status = VoiceCommandStatus.LISTENING, message = null) }
            is BatchTranscriptEvent.Transcribing -> _state.update { it.copy(status = VoiceCommandStatus.TRANSCRIBING, message = null) }
            is BatchTranscriptEvent.Result -> handleFinalTranscript(event.transcript)
            is BatchTranscriptEvent.Error -> {
                Log.w(TAG, "Transcriber error (fatal=${event.fatal}): ${event.message}")
                // A non-fatal (per-utterance) error is always immediately
                // followed by a Listening event from the capture loop --
                // nothing to do here beyond the log. A fatal error means the
                // mic/capture loop itself never started, so surface it the
                // same way a missing API key is surfaced: UNAVAILABLE, with
                // the existing "tap mic to retry" affordance in the UI.
                if (event.fatal) {
                    active = false
                    _state.value = VoiceCommandUiState(status = VoiceCommandStatus.UNAVAILABLE, message = event.message)
                }
            }
        }
    }

    /**
     * One-shot: the NEXT transcribed utterance is passed to [onUtterance]
     * raw (trimmed, but not wake-word-checked or otherwise parsed),
     * instead of going through the normal wake-word-gated command dispatch
     * -- used for a short free-form dictation step inside a voice dialogue
     * (e.g. "what should I name this route?" -> whatever the user says
     * next is the name, without needing to repeat "NAVI"). Must be called
     * from the main thread while already listening; overwrites any
     * previously pending callback.
     */
    fun awaitNextRawUtterance(onUtterance: (String) -> Unit) {
        // Free-form dictation (e.g. a route name) must NOT have the
        // wake-word list ("navi"/"navy") boosted -- that boost is tuned for
        // catching the wake word in ambient speech, but it skews
        // transcription of unrelated words in a short one-off utterance,
        // which is exactly what produced mismatched route names.
        transcriber.setWakeWordBoostEnabled(false)
        pendingRawUtteranceCallback = onUtterance
    }

    /** Cancels a pending [awaitNextRawUtterance] callback without waiting for an utterance -- e.g. the dialogue was abandoned. */
    fun cancelPendingRawUtterance() {
        pendingRawUtteranceCallback = null
        transcriber.setWakeWordBoostEnabled(true)
    }

    /**
     * Speaks [prompt] with the mic muted (so the batch transcriber can't
     * pick up NAVI's own prompt audio and mistake it for the user's
     * answer -- the same problem [promptRepeat] solves for its own
     * internal prompt), then unmutes and starts listening for the NEXT
     * utterance via [awaitNextRawUtterance] once the mute window has
     * elapsed. For external callers that need a "ask a question via TTS,
     * then capture the spoken answer" dialogue step -- Route Recording's
     * "what should I name this route?" and Conversation Memory's "are you
     * sure you want to delete everything?" both used to call
     * [awaitNextRawUtterance] immediately after speaking, with no mute at
     * all, so the mic reliably captured NAVI's own prompt as the "answer"
     * instead of waiting for the user.
     *
     * The mute window is sized to [prompt]'s length, not a single fixed
     * constant like [MUTE_DURING_PROMPT_MS] -- these dialogue prompts run
     * noticeably longer than the short acknowledgements that constant was
     * tuned for, and there's no actual TTS-completion callback wired up
     * (see [Speaker]'s minimal interface) to know precisely when speech
     * really finishes.
     */
    fun speakThenAwaitRawUtterance(prompt: String, onUtterance: (String) -> Unit) {
        transcriber.setMuted(true)
        speaker.speak(prompt, flushQueue = false, utteranceId = "voice_dialogue_prompt")
        mainHandler.postDelayed({
            transcriber.setMuted(false)
            awaitNextRawUtterance(onUtterance)
        }, estimatedSpeechDurationMs(prompt))
    }

    /** Rough length-based estimate of how long TTS will take to speak [text] -- see [speakThenAwaitRawUtterance]'s doc for why this isn't exact. */
    private fun estimatedSpeechDurationMs(text: String): Long =
        (text.length * MS_PER_CHARACTER_ESTIMATE).coerceIn(MIN_PROMPT_MUTE_MS, MAX_PROMPT_MUTE_MS)

    /**
     * Speaks [text] with the mic muted for roughly as long as it takes to
     * say it, then unmutes -- for a one-off status/confirmation message
     * that ISN'T asking a question (no [awaitNextRawUtterance] callback
     * registered afterward; see [speakThenAwaitRawUtterance] for the
     * "asking a question" case). Without this, e.g. Route Recording's "Rute
     * X berhasil disimpan." confirmation got picked up by the still-active
     * mic and transcribed as new (ambient, harmlessly ignored, but wasted)
     * input the moment it finished saving -- same self-listening problem
     * as the dialogue prompts, just on the "I'm done" side instead of the
     * "here's my question" side.
     */
    fun speakMuted(text: String) {
        transcriber.setMuted(true)
        speaker.speak(text, flushQueue = false, utteranceId = "voice_muted_message")
        mainHandler.postDelayed({ transcriber.setMuted(false) }, estimatedSpeechDurationMs(text))
    }

    private fun handleFinalTranscript(text: String) {
        Log.i(TAG, "Transcript: \"$text\"")

        val rawCallback = pendingRawUtteranceCallback
        if (rawCallback != null) {
            pendingRawUtteranceCallback = null
            transcriber.setWakeWordBoostEnabled(true)
            _state.update { it.copy(status = VoiceCommandStatus.LISTENING, message = null) }
            rawCallback(text.trim())
            return
        }

        val command = VoiceCommandParser.extractCommand(text)
        when {
            command != null -> {
                Log.i(TAG, "Wake word matched. command=\"$command\"")
                _state.value = VoiceCommandUiState(
                    status = VoiceCommandStatus.RECOGNIZED,
                    rawRecognizedText = text,
                    command = command,
                    eventId = ++recognizedEventCounter
                )
                speakRecognizedCommand(command)
            }
            VoiceCommandParser.mentionsWakeWord(text) -> {
                Log.i(TAG, "Wake word mentioned but not a clean prefix: \"$text\"")
                promptRepeat(rawText = text)
            }
            else -> {
                // Ambient speech never addressed to NAVI at all -- ignore
                // silently and keep listening.
                _state.update { it.copy(status = VoiceCommandStatus.LISTENING, message = null) }
            }
        }
    }

    /**
     * Speaks back what NAVI heard after the wake word, so a user who isn't
     * looking at the screen gets confirmation the command registered --
     * command *execution* is still a future step (see class doc), this is
     * only an audible acknowledgement that recognition worked.
     */
    private fun speakRecognizedCommand(command: String) {
        // Same reasoning as promptRepeat(): stop forwarding mic audio while
        // NAVI speaks, so it doesn't pick up its own voice as a new
        // "command".

        transcriber.setMuted(true)
        if (command.isBlank()){
            speaker.speak( emptyCommandMessage(language), flushQueue = false, utteranceId = "voice_command_recognized")
        }
        mainHandler.postDelayed({ transcriber.setMuted(false) }, MUTE_DURING_PROMPT_MS)
    }

    private fun promptRepeat(rawText: String?) {
        val message = repeatPromptMessage(language)
        _state.value = VoiceCommandUiState(status = VoiceCommandStatus.UNCLEAR, rawRecognizedText = rawText, message = message)

        // Stop forwarding mic audio while NAVI speaks the prompt, so it
        // doesn't pick up its own voice as new input -- reduces, doesn't
        // eliminate (no true echo cancellation), that risk.
        transcriber.setMuted(true)
        speaker.speak(message, flushQueue = false, utteranceId = "voice_command_repeat_prompt")
        mainHandler.postDelayed({
            transcriber.setMuted(false)
            if (active) {
                _state.update { if (it.status == VoiceCommandStatus.UNCLEAR) it.copy(status = VoiceCommandStatus.LISTENING, message = null) else it }
            }
        }, MUTE_DURING_PROMPT_MS)
    }


    private fun emptyCommandMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Ya? Silakan lanjutkan perintahnya."
        AnnouncementLanguage.ENGLISH -> "Yes? Go ahead with your command."
    }

    private fun repeatPromptMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Maaf, tidak terdengar jelas. Silakan ulangi, mulai dengan kata NAVI."
        AnnouncementLanguage.ENGLISH -> "Sorry, I didn't catch that clearly. Please repeat, starting with NAVI."
    }

    private fun missingApiKeyMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Tambahkan kunci API AssemblyAI di Pengaturan untuk mengaktifkan perintah suara."
        AnnouncementLanguage.ENGLISH -> "Add your AssemblyAI API key in Settings to enable voice commands."
    }

    /** Shown when Offline Mode is on but this device can't offer a verified-offline engine -- see [AndroidSpeechRecognizerTranscriber]'s class doc. */
    private fun onDeviceUnsupportedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Perintah suara butuh Android 12 ke atas untuk berfungsi dalam Mode Offline; perangkat ini tidak didukung."
        AnnouncementLanguage.ENGLISH -> "Voice commands need Android 12+ to work in Offline Mode; this device isn't supported."
    }

    /** Same on-device-unsupported situation as [onDeviceUnsupportedMessage], but reached via the automatic no-internet fallback rather than Offline Mode being manually on -- worded so the user isn't confused about a setting they never touched. */
    private fun noInternetUnsupportedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Tidak ada koneksi internet, dan perintah suara offline butuh Android 12 ke atas; perangkat ini tidak didukung."
        AnnouncementLanguage.ENGLISH -> "No internet connection, and offline voice commands need Android 12+; this device isn't supported."
    }

    private companion object {
        const val TAG = "VoiceCommandManager"

        /** How long to keep the mic muted after speaking a prompt/confirmation. */
        const val MUTE_DURING_PROMPT_MS = 2000L

        /** Rough average speaking pace used to size [speakThenAwaitRawUtterance]'s mute window -- not exact, just a reasonable per-character estimate. */
        const val MS_PER_CHARACTER_ESTIMATE = 90L
        const val MIN_PROMPT_MUTE_MS = 2000L
        const val MAX_PROMPT_MUTE_MS = 8000L
    }
}
