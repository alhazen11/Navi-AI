package com.apps.naviai.scene

/**
 * Recognizes an Object/Landmark Search voice command AND extracts the
 * object being searched for (e.g. "di mana botol?" -> "botol"), unlike the
 * other feature matchers which only need a yes/no match -- this feature
 * needs the free-form target text to put in the LLM prompt, so intent
 * detection and extraction are the same operation here. Simple
 * prefix-pattern matching, not full NLU -- same pragmatic approach as
 * [com.apps.naviai.audio.VoiceCommandParser]'s wake-word stripping.
 */
object ObjectSearchMatcher {
    private val PATTERNS = listOf(
        // Indonesian
        Regex("^(?:di\\s*mana|dimana)\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^cari(?:kan)?\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^temukan\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:apakah\\s+)?ada(?:kah)?\\s+(.+)$", RegexOption.IGNORE_CASE),
        // English
        Regex("^where(?:'s|\\s+is|\\s+are)\\s+(?:the\\s+|a\\s+|an\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^find\\s+(?:the\\s+|a\\s+|an\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^search\\s+for\\s+(?:the\\s+|a\\s+|an\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^is\\s+there\\s+(?:a\\s+|an\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^are\\s+there\\s+(?:any\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    )

    private val TRAILING_FILLERS = listOf(
        "di sini", "di sekitar sini", "di dekat sini", "di ruangan ini", "saya",
        "nearby", "around here", "near me", "in this room"
    )

    /**
     * @return the object/landmark being searched for -- trimmed, trailing
     *   punctuation and filler phrases like "di sini"/"nearby" stripped --
     *   or null if [command] doesn't look like an object-search request at
     *   all (so the caller can fall through to other intents/no match).
     */
    fun extractQuery(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null

        for (pattern in PATTERNS) {
            val match = pattern.find(trimmed) ?: continue
            val cleaned = cleanQuery(match.groupValues[1])
            if (cleaned.isNotBlank()) return cleaned
        }
        return null
    }

    private fun cleanQuery(raw: String): String {
        var result = raw.trim().trimEnd('?', '.', '!').trim()
        var strippedSomething: Boolean
        do {
            strippedSomething = false
            for (filler in TRAILING_FILLERS) {
                if (result.endsWith(filler, ignoreCase = true)) {
                    result = result.dropLast(filler.length).trim()
                    strippedSomething = true
                }
            }
        } while (strippedSomething && result.isNotEmpty())
        return result
    }
}
