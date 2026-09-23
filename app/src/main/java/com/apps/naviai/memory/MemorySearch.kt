package com.apps.naviai.memory

/**
 * Finds which stored memories are relevant to a recall/forget query via
 * simple keyword overlap (tokenize both, count shared non-stop-word
 * tokens) -- not embeddings/semantic search, deliberately: this app has no
 * on-device NLU model, and full-text search is enough for "di mana rumah
 * saya" to match a memory containing "rumah ... berada di Depok".
 */
object MemorySearch {
    private val STOP_WORDS = setOf(
        "di", "ke", "dari", "yang", "saya", "kamu", "kau", "anda", "itu", "ini", "apa", "mana", "siapa",
        "kapan", "bagaimana", "kenapa", "mengapa", "berapa", "adalah", "dan", "atau", "akan", "bahwa", "kalau",
        "the", "is", "are", "my", "me", "you", "what", "where", "who", "when", "how", "why", "do", "does",
        "did", "a", "an", "of", "that", "about"
    )

    fun tokenize(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{Nd}]+"))
        .filter { it.isNotBlank() && it !in STOP_WORDS }
        .toSet()

    /** @return [memories] whose content shares at least one keyword with [query], best (most-overlap) first. */
    fun search(query: String, memories: List<ConversationMemoryEntity>): List<ConversationMemoryEntity> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()
        return memories
            .map { it to tokenize(it.content).intersect(queryTokens).size }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
