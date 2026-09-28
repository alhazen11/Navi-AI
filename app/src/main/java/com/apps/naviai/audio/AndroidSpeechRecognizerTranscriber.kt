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
import java.util.Locale

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

    /** See [setUserSpeechListener]. */
    @Volatile private var userSpeechListener: (() -> Unit)? = null

    /**
     * Consecutive [SpeechRecognizer.ERROR_CLIENT]/[SpeechRecognizer.ERROR_RECOGNIZER_BUSY]/
     * [SpeechRecognizer.ERROR_SERVER_DISCONNECTED] failures since the last time a session actually
     * got as far as [RecognitionListener.onReadyForSpeech] -- drives [handleError]'s backoff. On-device
     * testing under Offline Mode found this device's on-device recognition service needs real
     * cumulative warm-up time after being (re)activated: it failed ~10 times in a row, roughly
     * every 1-2.5s, before suddenly stabilizing and working normally from then on. Retrying at the
     * SAME short fixed interval the whole time didn't shorten that warm-up -- if anything, tearing
     * down and recreating the recognizer that fast on every attempt may have been interrupting
     * whatever the service needed that time for. Backing off further with each consecutive failure
     * gives it progressively more breathing room instead.
     */
    @Volatile private var consecutiveTransientErrors = 0

    /**
     * Whether NAVI was already speaking at the moment [RecognitionListener.onBeginningOfSpeech]
     * fired for the CURRENT session -- i.e. whether the sound that opened this utterance was NAVI's
     * own voice rather than the user's. That is how self-voice rejection works for this engine now
     * (see [setMuted]): the session is DISCARDED at [RecognitionListener.onResults] instead of
     * being cancelled the moment TTS starts.
     */
    @Volatile private var speechBeganWhileMuted = false

    /** Which entry of [languageTagCandidates] is currently being tried -- see that function's doc. */
    @Volatile private var languageCandidateIndex = 0

    /**
     * Forces a fresh session when one has run [SESSION_TIMEOUT_MS] without delivering a result or
     * an error. [SpeechRecognizer] normally endpoints within a few seconds of silence, so a session
     * still alive well past that is stuck -- which on-device logs caught for real: with
     * [setMuted] no longer cancelling, NAVI's back-to-back hazard announcements fed the session
     * continuous audio it never found a silence gap in, and listening hung for 30 seconds until the
     * user happened to tap the mic button. Nothing else recovers from that on its own.
     */
    private val sessionWatchdog = Runnable {
        if (!active) return@Runnable
        Log.w(TAG, "Session exceeded ${SESSION_TIMEOUT_MS}ms with no result -- forcing a restart")
        restartListening()
    }

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Deliberately does NOT cancel the in-flight session any more, unlike
     * [AssemblyAiBatchTranscriber] (whose mute simply stops feeding chunks to its own VAD loop,
     * costing nothing). [SpeechRecognizer] has no such half-measure: the only way to stop it
     * hearing something is [SpeechRecognizer.cancel], which destroys the whole session.
     *
     * On-device logs showed why that is fatal here: on the Detection screen in Offline Mode,
     * [com.apps.naviai.audio.AnnouncementManager] speaks a hazard announcement roughly every 4
     * seconds for over a second at a time, so cancelling on every mute cancelled EVERY listening
     * session -- including ones the user had already started speaking into (a mute landed 41ms
     * before `onBeginningOfSpeech` in one captured case) -- leaving no window long enough to ever
     * finish saying a command. Letting the session run and rejecting NAVI's own voice at the
     * RESULT instead (see [speechBeganWhileMuted]) keeps the user's command intact, at the cost of
     * NAVI's overlapping words sometimes landing in a transcript the wake-word gate then filters.
     */
    override fun setMuted(value: Boolean) {
        val wasMuted = muted
        muted = value
        if (!value && wasMuted && active && recognizer == null) {
            // Only needed when nothing is currently listening (e.g. a mute arrived between
            // sessions); an in-flight session was left running and needs no restart.
            mainHandler.post { restartListening() }
        }
    }

    override fun setLanguage(value: AnnouncementLanguage) {
        if (value != language) languageCandidateIndex = 0
        language = value
    }

    override fun setWakeWordBoostEnabled(value: Boolean) {
        wakeWordBoostEnabled = value
    }

    override fun setUserSpeechListener(listener: (() -> Unit)?) {
        userSpeechListener = listener
    }

    /** Must be called from the main thread -- [SpeechRecognizer] requires it. */
    override fun start(apiKey: String?, language: AnnouncementLanguage, onEvent: (BatchTranscriptEvent) -> Unit) {
        stop()
        this.language = language
        this.onEvent = onEvent

        // isSupported() already requires SDK_INT >= S, but lint can't trace
        // that guard across the method boundary -- repeated inline so the
        // createOnDeviceSpeechRecognizer call in restartListening() below is provably guarded.
        if (!isSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onEvent(BatchTranscriptEvent.Error(unsupportedMessage(language), fatal = true))
            return
        }

        active = true
        muted = false
        consecutiveTransientErrors = 0
        languageCandidateIndex = 0
        restartListening()
    }

    override fun stop() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        onEvent = null
    }

    private fun destroyRecognizer() {
        recognizer?.apply {
            runCatching { cancel() }
            runCatching { destroy() }
        }
        recognizer = null
    }

    /**
     * Creates a FRESH [SpeechRecognizer] for every single listening session rather than reusing
     * one instance across repeated [SpeechRecognizer.startListening] calls (the original design,
     * matching the platform docs' own usage example) -- on-device testing under Offline Mode found
     * that reuse pattern reliably threw [SpeechRecognizer.ERROR_CLIENT] roughly 1-2 seconds into
     * EVERY session on this device, not just an occasional one: [handleError]'s retry (see its doc)
     * technically recovered each time, but left only a ~1-2s window per attempt to actually catch
     * the wake word, which in practice made voice commands almost never register. A fresh instance
     * per session is a known, documented workaround for this exact class of on-device-recognizer
     * flakiness on some OEM builds.
     *
     * [SpeechRecognizer.startListening] itself is deliberately posted with [RESTART_SETTLE_DELAY_MS],
     * not called inline right after [SpeechRecognizer.createOnDeviceSpeechRecognizer] (or a bare
     * [Handler.post], which was tried first and wasn't enough): on-device testing found the
     * recognition service needs real wall-clock time, not just the next message-loop tick, to
     * finish unbinding the just-destroyed previous session before a new one can bind -- skipping
     * that gap produced [SpeechRecognizer.ERROR_SERVER_DISCONNECTED] as fast as 8ms after
     * `startListening()` was called, i.e. the new instance was dead on arrival. The `recognizer
     * !== instance` check in the posted block drops this specific attempt if [stop]/[setMuted]/
     * another [restartListening] call already superseded it during the wait, rather than starting
     * a session nothing asked for any more.
     */
    @SuppressLint("MissingPermission")
    private fun restartListening() {
        // Deliberately not gated on `muted` any more: sessions now run straight through NAVI's
        // speech and reject it at the result instead (see setMuted's doc), so refusing to start
        // one while muted would just reintroduce the dead windows that fix removes.
        if (!active) return
        speechBeganWhileMuted = false
        // Re-arm the stuck-session watchdog for the session about to start (see sessionWatchdog).
        mainHandler.removeCallbacks(sessionWatchdog)
        mainHandler.postDelayed(sessionWatchdog, SESSION_TIMEOUT_MS)
        destroyRecognizer()
        val instance = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        instance.setRecognitionListener(createListener(instance))
        recognizer = instance
        mainHandler.postDelayed({
            if (!active || recognizer !== instance) return@postDelayed
            runCatching { instance.startListening(buildIntent()) }
                .onSuccess { Log.d(TAG, "startListening() called (language=${currentLanguageTag()})") }
                .onFailure { t ->
                    Log.w(TAG, "startListening failed, retrying shortly", t)
                    mainHandler.postDelayed({ restartListening() }, RETRY_DELAY_MS)
                }
        }, RESTART_SETTLE_DELAY_MS)
    }

    /**
     * Every BCP-47 tag worth trying for the currently selected [language], best first -- on-device
     * speech models are installed per exact region variant, not per "English"/"Indonesian" in the
     * abstract, so ONE hardcoded tag is a guess that a given device may simply not have:
     * 1. the DEVICE's own default locale, when its base language matches (e.g. the device is
     *    "en-IN" and the app wants English) -- whatever variant is installed there is the one the
     *    device's own voice typing already uses, so it is the likeliest to exist;
     * 2. this class's fixed [AnnouncementLanguage.locale] ("en-US" / "id-ID");
     * 3. the bare base language ("en" / "id"), which some recognizers resolve to whatever regional
     *    model they do have.
     *
     * [handleError] walks this list on [SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE]/
     * [SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED] via [languageCandidateIndex] instead of
     * giving up permanently on the first rejection, which is what it used to do -- observed
     * on-device first for a hardcoded "en-US" on a device whose English model was another region,
     * then again for "id-ID" with the app set to Indonesian.
     */
    private fun languageTagCandidates(): List<String> {
        val desired = language.locale
        val systemDefault = Locale.getDefault()
        return buildList {
            if (systemDefault.language == desired.language) add(systemDefault.toLanguageTag())
            add(desired.toLanguageTag())
            add(desired.language)
        }.distinct()
    }

    private fun currentLanguageTag(): String {
        val candidates = languageTagCandidates()
        return candidates.getOrNull(languageCandidateIndex) ?: candidates.last()
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageTag())
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

    /**
     * [owner] is the exact [SpeechRecognizer] instance this listener was registered on -- every
     * callback checks `recognizer !== owner` first and bails out if so. Without this, a callback
     * queued for an about-to-be-superseded instance (e.g. [destroyRecognizer] cancelling it right
     * as [restartListening] creates the next one) could still fire after [recognizer] already
     * points elsewhere, observed as the SAME error delivered twice at the identical timestamp --
     * once for the dying instance, once for the fresh one -- each independently triggering its own
     * retry/restart and compounding the churn this class is already trying to avoid.
     */
    private fun createListener(owner: SpeechRecognizer): RecognitionListener = object : RecognitionListener {
        private fun isStale() = recognizer !== owner

        override fun onReadyForSpeech(params: Bundle?) {
            if (isStale()) return
            Log.d(TAG, "onReadyForSpeech")
            // A session actually got this far -- whatever backoff handleError built up no longer
            // applies (see consecutiveTransientErrors' doc).
            consecutiveTransientErrors = 0
            onEvent?.invoke(BatchTranscriptEvent.Listening)
        }

        override fun onBeginningOfSpeech() {
            if (isStale()) return
            // Sound that starts while NAVI is talking is NAVI -- see speechBeganWhileMuted's doc.
            speechBeganWhileMuted = muted
            Log.d(TAG, "onBeginningOfSpeech -- mic picked up sound (whileNaviSpeaking=$speechBeganWhileMuted)")
            // Only real user speech is reported -- see setUserSpeechListener's doc.
            if (!speechBeganWhileMuted) userSpeechListener?.invoke()
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (isStale()) return
            Log.d(TAG, "onEndOfSpeech")
            onEvent?.invoke(BatchTranscriptEvent.Transcribing)
        }

        override fun onError(error: Int) {
            if (isStale()) return
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            if (isStale()) return
            val allResults = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = allResults?.firstOrNull()
            Log.d(TAG, "onResults: $allResults")
            // Self-voice rejection for this engine (see setMuted's doc): the utterance opened while
            // NAVI was talking, so this is NAVI's own voice coming back in, not a command.
            if (speechBeganWhileMuted) {
                Log.d(TAG, "  ...discarded: this utterance started while NAVI was speaking")
            } else if (!text.isNullOrBlank()) {
                onEvent?.invoke(BatchTranscriptEvent.Result(text))
            }
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
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                // All three are usually transient, local recognizer-session hiccups -- ERROR_CLIENT
                // a timing race between startListening() and the recognition service still finishing
                // setup/teardown from the previous session (see restartListening()'s doc);
                // ERROR_SERVER_DISCONNECTED the on-device recognizer's own internal service
                // connection dropping (this is on-device recognition -- "server" here means that
                // bound service, not a network server) -- neither is a real, permanent failure.
                // Treating them as fatal (the old behavior, falling through to the generic branch
                // below) silently killed continuous listening on the very first hiccup.
                //
                // Exponential backoff (see consecutiveTransientErrors' doc), not a fixed
                // RETRY_DELAY_MS every time: 500ms, 1000ms, 2000ms, 4000ms, capped at
                // MAX_RETRY_DELAY_MS -- gives the recognition service progressively more real
                // wall-clock time to finish whatever warm-up a fixed short interval kept interrupting.
                consecutiveTransientErrors++
                val delayMs = (RETRY_DELAY_MS shl (consecutiveTransientErrors - 1).coerceAtMost(4))
                    .coerceAtMost(MAX_RETRY_DELAY_MS)
                mainHandler.postDelayed({ restartListening() }, delayMs)
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                Log.w(TAG, "On-device recognizer attempted network use (error=$error) -- refusing to continue under Offline Mode")
                active = false
                onEvent?.invoke(BatchTranscriptEvent.Error(networkFallbackMessage(language), fatal = true))
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                // Try the next tag for this same language before declaring it unsupported -- see
                // languageTagCandidates()'s doc for why one rejected tag doesn't mean the language
                // itself is missing, only that this exact variant is.
                val candidates = languageTagCandidates()
                val rejected = currentLanguageTag()
                if (languageCandidateIndex + 1 < candidates.size) {
                    languageCandidateIndex++
                    Log.w(TAG, "Recognizer rejected language tag \"$rejected\" -- retrying as \"${currentLanguageTag()}\"")
                    mainHandler.postDelayed({ restartListening() }, RETRY_DELAY_MS)
                } else {
                    Log.w(TAG, "Recognizer rejected every candidate tag for ${language.name}: $candidates")
                    active = false
                    onEvent?.invoke(BatchTranscriptEvent.Error(languageUnsupportedMessage(language), fatal = true))
                }
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
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
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
        AnnouncementLanguage.INDONESIAN ->
            "Perangkat ini belum punya model suara offline Bahasa Indonesia. Unduh dulu lewat Pengaturan HP " +
                "(Pengenalan suara di perangkat), atau matikan Mode Offline untuk memakai perintah suara."
        AnnouncementLanguage.ENGLISH ->
            "This device has no offline voice model for the selected language. Download it in your phone's " +
                "Settings (on-device speech recognition), or turn Offline Mode off to use voice commands."
    }

    private fun genericErrorMessage(language: AnnouncementLanguage, error: Int): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Pengenalan suara offline gagal (kode $error)."
        AnnouncementLanguage.ENGLISH -> "On-device voice recognition failed (code $error)."
    }

    private companion object {
        const val TAG = "AndroidSpeechRecognizer"

        /** Base retry delay -- see handleError's transient-error branch for the exponential backoff built on top of it. */
        const val RETRY_DELAY_MS = 500L

        /** Ceiling for handleError's exponential backoff -- see consecutiveTransientErrors' doc. */
        const val MAX_RETRY_DELAY_MS = 8000L

        /** See [restartListening]'s doc -- real wall-clock time given to the recognition service to finish unbinding the previous session before starting a new one. */
        const val RESTART_SETTLE_DELAY_MS = 200L

        /** See [sessionWatchdog] -- how long one session may run with no result before it's treated as stuck. Comfortably longer than the recognizer's own ~5s silence endpointing. */
        const val SESSION_TIMEOUT_MS = 15_000L
    }
}
