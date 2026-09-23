package com.apps.naviai.routenav

/**
 * Recognizes the "start navigation to a saved route" voice command AND
 * extracts the route name (e.g. "mulai navigasi kantor" -> "kantor"), same
 * extraction-while-matching approach as
 * [com.apps.naviai.scene.ObjectSearchMatcher] since the route name is
 * free-form text needed downstream, not just a yes/no intent.
 */
object NavigationCommandMatcher {
    private val START_PATTERNS = listOf(
        // Must come before the bare "mulai navigasi (.+)" pattern below:
        // for "mulai navigasi ke kantor", that bare pattern's greedy (.+)
        // would otherwise match first and capture "ke kantor" (including
        // the word "ke") as the route name instead of just "kantor".
        Regex("^mulai\\s+navigasi\\s+ke\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^mulai\\s+navigasi\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^navigasi\\s+ke\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^navigasi\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^start\\s+navigation\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^navigate\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
    )
    private val STOP_KEYWORDS = listOf("stop navigasi", "berhenti navigasi", "stop navigation")

    /** @return the route name to navigate to (trimmed, trailing punctuation stripped), or null if [command] isn't a "start navigation" request. */
    fun extractRouteName(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in START_PATTERNS) {
            val match = pattern.find(trimmed) ?: continue
            val name = match.groupValues[1].trim().trimEnd('.', '?', '!').trim()
            if (name.isNotBlank()) return name
        }
        return null
    }

    fun isStop(command: String): Boolean {
        val normalized = command.trim().lowercase()
        return normalized.isNotEmpty() && STOP_KEYWORDS.any { normalized.contains(it) }
    }
}
