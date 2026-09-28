package com.apps.naviai.voiceagent

/**
 * Which `input.language_codes` values AssemblyAI's Voice Agent API actually
 * accepts -- the pure, unit-testable half of a bug that cost a lot of live
 * debugging time (see [VoiceAgentClient]'s class doc for the full trail).
 *
 * **Why this exists.** Pro Mode used to send `["id", "en"]` outright, because
 * this app's users speak Indonesian and AssemblyAI's *speech-to-text* product
 * does support Indonesian. The Voice Agent API is a different pipeline, and
 * its own published language list names only English, Spanish, French,
 * German, Italian and Portuguese. Sending `"id"` was syntactically valid
 * (an array of strings), so:
 * - `session.update` was accepted with no `session.error`,
 * - `session.ready` echoed the config back verbatim, including `["id","en"]`,
 * - the agent's own greeting played normally (the LLM/TTS half of the
 *   pipeline doesn't depend on the input language at all),
 * - but `input.speech.started` and `transcript.user` never fired even once,
 *   and the session's own server-side recording had a completely empty input
 *   channel -- because, per the API's events reference, `language_codes` is
 *   "applied on the next STT connect", and that STT connect never succeeded
 *   for an unsupported language.
 *
 * Every audio-level hypothesis chased before this one (sample rate, PCM
 * framing, base64 padding, chunk pacing, VAD thresholds, mic gain, even
 * playing back the exact bytes sent as a WAV) came back clean, which fits:
 * the audio was always fine, there was simply no transcriber attached to it.
 *
 * So an unsupported code here is worse than no code at all -- the API
 * documents `language_codes` as optional, with automatic detection when it's
 * omitted. [filterSupported] therefore drops anything not known-supported,
 * and [VoiceAgentClient] omits the field entirely when nothing is left,
 * falling back to that documented auto-detection rather than forcing a
 * language the server will choke on.
 *
 * Codes are matched case-insensitively and region suffixes are tolerated
 * (`en-US`/`en_us` both count as `en`), since the API's own reference only
 * ever shows bare two-letter codes and this app has no way to verify which
 * regional variants are accepted.
 */
object VoiceAgentLanguages {

    /**
     * Two-letter codes for the languages AssemblyAI lists as supported by the
     * Voice Agent API (as opposed to its broader speech-to-text product).
     * Add to this list only against AssemblyAI's own current documentation --
     * an unsupported code here silently disables speech recognition for the
     * entire session rather than failing loudly, which is exactly what made
     * the original bug so expensive to find.
     */
    private val SUPPORTED = setOf("en", "es", "fr", "de", "it", "pt")

    /** True if [code] names a language the Voice Agent API's STT can handle. */
    fun isSupported(code: String): Boolean = normalize(code) in SUPPORTED

    /**
     * [codes] minus anything unsupported, de-duplicated, order preserved, and
     * normalized to the bare two-letter form the API's reference shows. An
     * empty result means the caller should omit `language_codes` entirely
     * (automatic detection) -- see this object's doc.
     */
    fun filterSupported(codes: List<String>): List<String> =
        codes.map { normalize(it) }.filter { it in SUPPORTED }.distinct()

    private fun normalize(code: String): String =
        code.trim().lowercase().substringBefore('-').substringBefore('_')
}
