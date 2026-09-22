package com.apps.naviai.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
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
 */
@Singleton
class VoiceCommandManager @Inject constructor(
    private val speaker: Speaker,
    httpClient: OkHttpClient
) {
    private val transcriber = AssemblyAiBatchTranscriber(httpClient)

    private val _state = MutableStateFlow(VoiceCommandUiState())
    val state: StateFlow<VoiceCommandUiState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var language = AnnouncementLanguage.INDONESIAN
    private var apiKey: String? = null
    private var active = false
    private var recognizedEventCounter = 0L

    /**
     * Must be called from the main thread. Idempotent while already active
     * with the same key -- but if [apiKey] differs from the one currently
     * connected with (e.g. the key just arrived from a settings load that
     * raced with an earlier call made before it was ready, or the user
     * edited it), reconnects with the new one instead of silently no-oping.
     */
    fun startContinuousListening(language: AnnouncementLanguage, apiKey: String?) {
        val keyChanged = apiKey != this.apiKey
        this.language = language
        this.apiKey = apiKey

        if (apiKey.isNullOrBlank()) {
            active = false
            _state.value = VoiceCommandUiState(status = VoiceCommandStatus.UNAVAILABLE, message = missingApiKeyMessage(language))
            return
        }

        if (active && !keyChanged) return
        active = true
        transcriber.start(apiKey) { event -> mainHandler.post { handleEvent(event) } }
    }

    /** Stops listening entirely; no further auto-restart until [startContinuousListening] is called again. */
    fun stopListening() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        transcriber.stop()
        _state.update { current ->
            if (current.status == VoiceCommandStatus.UNAVAILABLE) current else current.copy(status = VoiceCommandStatus.PAUSED, message = null)
        }
    }

    fun reset() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
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

    private fun handleFinalTranscript(text: String) {
        Log.i(TAG, "Transcript: \"$text\"")

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
        val message = if (command.isBlank()) emptyCommandMessage(language) else recognizedCommandMessage(language, command)

        // Same reasoning as promptRepeat(): stop forwarding mic audio while
        // NAVI speaks, so it doesn't pick up its own voice as a new
        // "command".
        transcriber.setMuted(true)
        speaker.speak(message, flushQueue = false, utteranceId = "voice_command_recognized")
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

    private fun recognizedCommandMessage(language: AnnouncementLanguage, command: String): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Saya dengar: $command"
        AnnouncementLanguage.ENGLISH -> "I heard: $command"
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

    private companion object {
        const val TAG = "VoiceCommandManager"

        /** How long to keep the mic muted after speaking a prompt/confirmation. */
        const val MUTE_DURING_PROMPT_MS = 3000L
    }
}
