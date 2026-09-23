package com.apps.naviai.recording

/** A parsed "rename route X to Y" voice command. */
data class RouteRenameRequest(val oldName: String, val newName: String)

/**
 * Recognizes the "rename a saved route" voice command and extracts both the
 * existing route's name and the new name (e.g. "ganti nama rute kantor
 * menjadi rumah" -> kantor/rumah), same extraction-while-matching approach
 * as [com.apps.naviai.routenav.NavigationCommandMatcher] since both names
 * are free-form text needed downstream, not just a yes/no intent.
 */
object RouteRenameMatcher {
    private val PATTERNS = listOf(
        Regex("^ganti\\s+nama\\s+rute\\s+(.+?)\\s+(?:menjadi|jadi)\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^ubah\\s+nama\\s+rute\\s+(.+?)\\s+(?:menjadi|jadi)\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^rename\\s+(?:the\\s+)?route\\s+(.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^change\\s+(?:the\\s+)?(?:name\\s+of\\s+)?route\\s+(.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
    )

    /** @return the old/new route names, or null if [command] isn't a "rename route" request. */
    fun extract(command: String): RouteRenameRequest? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in PATTERNS) {
            val match = pattern.find(trimmed) ?: continue
            val oldName = match.groupValues[1].trim().trimEnd('.', '?', '!').trim()
            val newName = match.groupValues[2].trim().trimEnd('.', '?', '!').trim()
            if (oldName.isNotBlank() && newName.isNotBlank()) return RouteRenameRequest(oldName, newName)
        }
        return null
    }
}
