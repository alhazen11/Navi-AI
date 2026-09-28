package com.apps.naviai.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class TtsAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

enum class AnnouncementLanguage(val locale: Locale) {
    INDONESIAN(Locale("id", "ID")),
    ENGLISH(Locale.US)
}

/**
 * Thin wrapper around [android.speech.tts.TextToSpeech]. Never throws on a
 * device with no TTS engine installed or a missing language pack --
 * [availability] reports [TtsAvailability.UNAVAILABLE] and every [speak]
 * call becomes a safe no-op, so the rest of the app degrades to
 * visual-only feedback instead of crashing.
 */
@Singleton
class TextToSpeechManager @Inject constructor(@ApplicationContext context: Context) : Speaker {

    private val _availability = MutableStateFlow(TtsAvailability.UNKNOWN)
    val availability: StateFlow<TtsAvailability> = _availability.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)

    /**
     * True while the engine is actually speaking, driven by
     * [UtteranceProgressListener] rather than a duration estimate.
     *
     * Pro Mode needs this: its microphone stays open continuously, so it has
     * to know exactly when NAVI's own voice is coming out of the speaker to
     * avoid feeding it back to the voice agent as if the user had said it
     * (see [com.apps.naviai.ui.viewmodel.ProModeViewModel]). The regular
     * pipeline's older workaround for the same problem was a fixed ~3s mute
     * window, which either cuts off early or mutes too long depending on how
     * much there was to say.
     */
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var engine: TextToSpeech? = null

    init {
        engine = try {
            TextToSpeech(context) { status ->
                _availability.value = if (status == TextToSpeech.SUCCESS) {
                    TtsAvailability.AVAILABLE
                } else {
                    Log.w(TAG, "TextToSpeech init failed, status=$status")
                    TtsAvailability.UNAVAILABLE
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech unavailable on this device", t)
            null
        }
        if (engine == null) {
            _availability.value = TtsAvailability.UNAVAILABLE
        } else {
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Superseded by onError(String, int), which the platform calls instead where available")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    _isSpeaking.value = false
                }
            })
        }
    }

    /** Returns false if the language/voice data isn't installed; caller should fall back. */
    fun setLanguage(language: AnnouncementLanguage): Boolean {
        val result = engine?.setLanguage(language.locale) ?: return false
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun setSpeechRate(rate: Float) {
        engine?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        engine?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    /**
     * @param flushQueue true for urgent/emergency announcements that should
     *   interrupt whatever is currently being spoken (QUEUE_FLUSH);
     *   false to queue behind pending speech (QUEUE_ADD).
     */
    override fun speak(text: String, flushQueue: Boolean, utteranceId: String) {
        if (_availability.value != TtsAvailability.AVAILABLE) return
        val mode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine?.speak(text, mode, null, utteranceId)
    }

    override fun stop() {
        engine?.stop()
        // stop() does not always deliver onStop for already-flushed utterances, so clear this
        // directly -- a stuck-true isSpeaking would keep Pro Mode's mic muted indefinitely.
        _isSpeaking.value = false
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        _isSpeaking.value = false
        _availability.value = TtsAvailability.UNAVAILABLE
    }

    private companion object {
        const val TAG = "TextToSpeechManager"
    }
}
