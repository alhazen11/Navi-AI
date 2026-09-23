package com.apps.naviai.memory

/**
 * Recognizes the memory voice commands (save/recall/forget/clear-all) and,
 * where relevant, extracts the free-form content -- same pragmatic
 * regex/keyword approach as this app's other voice matchers (see
 * [com.apps.naviai.scene.ObjectSearchMatcher] for the extraction style,
 * [com.apps.naviai.routenav.NavigationCommandMatcher] for the "stop X"
 * keyword style). Not full NLU.
 */
object MemoryCommandMatcher {
    private val SAVE_PATTERNS = listOf(
        Regex("^ingat(?:lah)?\\s+(?:bahwa|kalau)\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^tolong\\s+ingat(?:kan)?\\s+(?:bahwa|kalau)?\\s*(.+)$", RegexOption.IGNORE_CASE),
        Regex("^ingat\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^remember\\s+that\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^remember\\s+(.+)$", RegexOption.IGNORE_CASE)
    )

    private val FORGET_PATTERNS = listOf(
        Regex("^lupakan\\s+(?:bahwa\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^tolong\\s+lupakan\\s+(?:bahwa\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^forget\\s+that\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^forget\\s+(.+)$", RegexOption.IGNORE_CASE)
    )

    private val CLEAR_ALL_KEYWORDS = listOf(
        "hapus semua ingatan", "hapus semua memori", "bersihkan semua ingatan", "hapus seluruh ingatan",
        "clear all memories", "delete all memories", "forget everything"
    )

    private val GENERAL_RECALL_KEYWORDS = listOf(
        "apa yang kamu ingat tentang saya", "apa yang kau ingat tentang saya", "apa yang anda ingat tentang saya",
        "ingatan saya apa saja", "apa saja yang kamu ingat", "apa saja yang kamu ingat tentang saya",
        "what do you remember about me", "what do you know about me"
    )

    private val QUESTION_STARTERS = listOf(
        "di mana", "dimana", "apa", "siapa", "kapan", "bagaimana", "kenapa", "mengapa", "berapa",
        "where", "what", "who", "when", "how", "why"
    )

    /** @return the content to save, or null if [command] isn't a "remember X" request. */
    fun extractToRemember(command: String): String? = firstMatch(SAVE_PATTERNS, command)

    /** @return the content to search for and delete, or null if [command] isn't a "forget X" request. */
    fun extractToForget(command: String): String? = firstMatch(FORGET_PATTERNS, command)

    fun isClearAll(command: String): Boolean = containsAny(command, CLEAR_ALL_KEYWORDS)

    /** True for the general "what do you remember about me" phrasing -- list everything, as opposed to a specific question. */
    fun isGeneralRecall(command: String): Boolean = containsAny(command, GENERAL_RECALL_KEYWORDS)

    /**
     * A question-shaped recall query, e.g. "di mana rumah saya" -> the
     * command itself (search matching against stored memory content
     * happens elsewhere, see [MemorySearch] -- this only decides whether
     * [command] looks like a question worth searching memories for at
     * all). Null if it doesn't start with a recognizable question word.
     */
    fun extractRecallQuery(command: String): String? {
        val normalized = command.trim().trimEnd('?', '.', '!')
        if (normalized.isEmpty()) return null
        val lower = normalized.lowercase()
        return if (QUESTION_STARTERS.any { lower.startsWith(it) }) normalized else null
    }

    private fun containsAny(command: String, keywords: List<String>): Boolean {
        val normalized = command.trim().lowercase()
        return normalized.isNotEmpty() && keywords.any { normalized.contains(it) }
    }

    private fun firstMatch(patterns: List<Regex>, command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in patterns) {
            val match = pattern.find(trimmed) ?: continue
            val content = match.groupValues[1].trim().trimEnd('.', '?', '!').trim()
            if (content.isNotBlank()) return content
        }
        return null
    }
}
