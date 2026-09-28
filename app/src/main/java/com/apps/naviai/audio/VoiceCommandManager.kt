package com.apps.naviai.audio

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.apps.naviai.core.common.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 *
 * **Self-voice rejection**: [init] subscribes to [speaker]'s [Speaker.isSpeaking] and
 * auto-mutes/unmutes the active [transcriber] to match, in real time -- so NAVI's own voice never
 * gets fed back into the mic as if the user had said it, regardless of which call site triggered
 * the speech, including every OTHER `ttsManager.speak(...)` call in the app (Scene
 * Understanding/Text Reading/Object Search/Hazard results, route-not-found messages, etc.) that
 * never went through this class at all. This is the SOLE muting mechanism now -- the four call
 * sites that need to run something once NAVI finishes talking
 * ([speakMuted]/[speakThenAwaitRawUtterance]/[promptRepeat]/[speakRecognizedCommand]) use
 * [afterSpeaking] to wait for the real end of speech instead of guessing a fixed duration and
 * calling `setMuted(false)` themselves: that used to unmute early on anything longer than the old
 * ~2s guess (e.g. the "please repeat" prompt), reopening the mic mid-sentence and letting NAVI's
 * own tail end of speech get transcribed as if the user had said it -- the self-voice rejection
 * this class exists to prevent, happening from inside the very code meant to prevent it.
 */
@Singleton
class VoiceCommandManager @Inject constructor(
    private val speaker: Speaker,
    private val networkMonitor: NetworkMonitor,
    httpClient: OkHttpClient,
    @ApplicationContext context: Context
) {
    // Dispatchers.Main.immediate: setMuted() below must reach whichever VoiceTranscriber is
    // currently active without waiting for a thread hop, same reasoning as
    // com.apps.naviai.routenav.NavigationController's own scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cloudTranscriber: VoiceTranscriber = AssemblyAiBatchTranscriber(httpClient)
    private val onDeviceTranscriber: VoiceTranscriber = AndroidSpeechRecognizerTranscriber(context)

    /** Whichever engine is currently active -- see the class doc's "Engine switching" note. */
    private var transcriber: VoiceTranscriber = cloudTranscriber

    init {
        // See the class doc's "Self-voice rejection" section. Reads the CURRENT transcriber var
        // (not a captured reference) on every emission, so this stays correct across an engine
        // switch (Offline Mode toggling mid-session) too.
        scope.launch {
            speaker.isSpeaking.collect { speaking ->
                Log.d(TAG, "TTS isSpeaking=$speaking -- ${if (speaking) "muting" else "unmuting"} the mic")
                transcriber.setMuted(speaking)
            }
        }
    }

    /**
     * Runs [action] once [speaker] genuinely finishes speaking whatever was just queued --
     * waits for [Speaker.isSpeaking] to go true (confirms playback actually started, so a
     * currently-false read isn't mistaken for "already done" before TTS even begins) and then
     * false (confirms it ended), instead of the old fixed-duration guess. See the class doc's
     * "Self-voice rejection" section for why that guess was actively wrong for anything longer
     * than it assumed.
     */
    private fun afterSpeaking(action: () -> Unit) {
        scope.launch {
            speaker.isSpeaking.first { it }
            speaker.isSpeaking.first { !it }
            action()
        }
    }

    private val _state = MutableStateFlow(VoiceCommandUiState())
    val state: StateFlow<VoiceCommandUiState> = _state.asStateFlow()

    private val _userSpeaking = MutableStateFlow(false)

    /**
     * True from the moment the active [transcriber] reports the USER has started speaking (see
     * [VoiceTranscriber.setUserSpeechListener]) until that utterance resolves. Exists so the rest
     * of the app can avoid speaking over a command already in progress -- specifically
     * [com.apps.naviai.ui.viewmodel.DetectionViewModel], which otherwise kept announcing detected
     * objects every few seconds straight through whatever the user was saying. On-device logs of
     * Offline Mode showed exactly that killing every command: the user would start a sentence, two
     * hazard announcements would play over it, and the recognizer returned ERROR_NO_MATCH on the
     * garbled overlap every time.
     *
     * Cleared by the terminal event of the utterance ([BatchTranscriptEvent.Result]/
     * [BatchTranscriptEvent.Error]/[BatchTranscriptEvent.Listening]), plus a
     * [MAX_USER_SPEECH_MS] failsafe so a dropped terminal event can never leave the app
     * permanently silent.
     */
    val userSpeaking: StateFlow<Boolean> = _userSpeaking.asStateFlow()

    /**
     * True while NAVI itself is talking, i.e. while the mic is muted by self-voice rejection (see
     * the class doc's matching section). Re-exposed straight from [speaker] so UI can show that
     * state honestly -- [VoiceCommandStatus] has no value for it, so a panel reading only [state]
     * would keep claiming "Listening" throughout every announcement.
     */
    val naviSpeaking: StateFlow<Boolean> = speaker.isSpeaking

    private val userSpeechTimeout = Runnable { _userSpeaking.value = false }

    private fun onUserSpeechStarted() {
        _userSpeaking.value = true
        mainHandler.removeCallbacks(userSpeechTimeout)
        mainHandler.postDelayed(userSpeechTimeout, MAX_USER_SPEECH_MS)
    }

    private fun clearUserSpeaking() {
        mainHandler.removeCallbacks(userSpeechTimeout)
        _userSpeaking.value = false
    }

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
            transcriber.setUserSpeechListener { mainHandler.post { onUserSpeechStarted() } }
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
        transcriber.setUserSpeechListener { mainHandler.post { onUserSpeechStarted() } }
        transcriber.start(apiKey, language) { event -> mainHandler.post { handleEvent(event) } }
    }

    /** Stops listening entirely; no further auto-restart until [startContinuousListening] is called again. */
    fun stopListening() {
        active = false
        clearUserSpeaking()
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
        clearUserSpeaking()
        mainHandler.removeCallbacksAndMessages(null)
        pendingRawUtteranceCallback = null
        transcriber.setWakeWordBoostEnabled(true)
        transcriber.stop()
        _state.value = VoiceCommandUiState()
    }

    private fun handleEvent(event: BatchTranscriptEvent) {
        if (!active) return
        when (event) {
            is BatchTranscriptEvent.Listening -> {
                clearUserSpeaking()
                _state.update { it.copy(status = VoiceCommandStatus.LISTENING, message = null) }
            }
            is BatchTranscriptEvent.Transcribing -> _state.update { it.copy(status = VoiceCommandStatus.TRANSCRIBING, message = null) }
            is BatchTranscriptEvent.Result -> {
                clearUserSpeaking()
                handleFinalTranscript(event.transcript)
            }
            is BatchTranscriptEvent.Error -> {
                clearUserSpeaking()
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
     * Speaks [prompt] (the [init] auto-mute collector keeps the mic muted for as long as it's
     * actually playing -- see the class doc), then starts listening for the NEXT utterance via
     * [awaitNextRawUtterance] once speech genuinely finishes (see [afterSpeaking]). For external
     * callers that need a "ask a question via TTS, then capture the spoken answer" dialogue step
     * -- Route Recording's "what should I name this route?" and Conversation Memory's "are you
     * sure you want to delete everything?" both used to call [awaitNextRawUtterance] immediately
     * after speaking, with no mute at all, so the mic reliably captured NAVI's own prompt as the
     * "answer" instead of waiting for the user.
     */
    fun speakThenAwaitRawUtterance(prompt: String, onUtterance: (String) -> Unit) {
        speaker.speak(prompt, flushQueue = false, utteranceId = "voice_dialogue_prompt")
        afterSpeaking { awaitNextRawUtterance(onUtterance) }
    }

    /**
     * Speaks [text] -- for a one-off status/confirmation message that ISN'T asking a question (no
     * [awaitNextRawUtterance] callback needed after; see [speakThenAwaitRawUtterance] for the
     * "asking a question" case). Muting while it plays is handled entirely by [init]'s auto-mute
     * collector now (see the class doc) -- this wrapper exists mainly so call sites read as
     * intentionally-muted-by-design rather than a bare [Speaker.speak] call.
     */
    fun speakMuted(text: String) {
        speaker.speak(text, flushQueue = false, utteranceId = "voice_muted_message")
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
        // Muting while this plays is handled by init's auto-mute collector (see the class doc);
        // nothing to wait for afterward since no dialogue step follows.
        if (command.isBlank()) {
            speaker.speak(emptyCommandMessage(language), flushQueue = false, utteranceId = "voice_command_recognized")
        }
    }

    private fun promptRepeat(rawText: String?) {
        val message = repeatPromptMessage(language)
        _state.value = VoiceCommandUiState(status = VoiceCommandStatus.UNCLEAR, rawRecognizedText = rawText, message = message)

        // Muting while this plays is handled by init's auto-mute collector (see the class doc).
        speaker.speak(message, flushQueue = false, utteranceId = "voice_command_repeat_prompt")
        afterSpeaking {
            if (active) {
                _state.update { if (it.status == VoiceCommandStatus.UNCLEAR) it.copy(status = VoiceCommandStatus.LISTENING, message = null) else it }
            }
        }
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

        /** Failsafe clearing [userSpeaking] if an utterance's terminal event never arrives -- see that property's doc. Comfortably longer than any single spoken command. */
        const val MAX_USER_SPEECH_MS = 12_000L
    }
}
