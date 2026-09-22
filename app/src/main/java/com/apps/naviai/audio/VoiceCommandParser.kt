package com.apps.naviai.audio

/**
 * Pure wake-word extraction: only speech recognized as starting with a wake
 * word is treated as an addressed command, so ambient conversation/
 * background speech picked up by the microphone is never accidentally acted
 * on. Kept free of android.speech dependencies so it has a fast,
 * native-library-free unit test surface (see the matching logic in
 * [VoiceCommandManager], which is Android-framework-coupled and cannot be
 * unit tested the same way).
 */
object VoiceCommandParser {
    /**
     * The user-facing wake word is "NAVI", but speech recognizers
     * (especially non-English locales) reliably mis-transcribe it as the
     * real English word "Navy" -- confirmed by on-device testing, where
     * genuine "NAVI, ..." attempts were being silently ignored as ambient
     * speech because the recognizer never produced the literal text
     * "navi". "Navy" is accepted as an equivalent alias for exactly that
     * reason; it is not a second word users need to remember to say.
     */
    val DEFAULT_WAKE_WORDS = listOf("navi", "navy","nafi", "na vi", "na fi", "na vy")

    /**
     * @return the command text following whichever wake word matched
     *   (possibly blank, if the user said only the wake word with nothing
     *   after it), or null if [recognizedText] does not start with any of
     *   [wakeWords].
     */
    fun extractCommand(recognizedText: String, wakeWords: List<String> = DEFAULT_WAKE_WORDS): String? {
        val trimmed = recognizedText.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val matchedWakeWord = wakeWords.firstOrNull { lower.startsWith(it.lowercase()) } ?: return null

        val rest = trimmed.substring(matchedWakeWord.length)
        // Strip a leading separator between the wake word and the command
        // (comma, colon, or whitespace), e.g. "NAVI, turn on the light" ->
        // "turn on the light".
        return rest.trimStart { it == ',' || it == ':' || it.isWhitespace() }
    }

    /**
     * True if any wake word in [wakeWords] appears anywhere in [text] as a
     * whole word, not necessarily as a clean leading prefix. Used only to
     * tell apart two very different situations in continuous listening:
     * ambient speech that was never meant for NAVI at all (ignore silently,
     * don't nag the user) versus a garbled attempt to address NAVI, e.g.
     * "uh navi turn on" or noise mangling the prefix (worth a spoken
     * "please repeat").
     */
    fun mentionsWakeWord(text: String, wakeWords: List<String> = DEFAULT_WAKE_WORDS): Boolean {
        val lower = text.lowercase()
        return wakeWords.any { wakeWord ->
            Regex("\\b${Regex.escape(wakeWord.lowercase())}\\b").containsMatchIn(lower)
        }
    }
}
