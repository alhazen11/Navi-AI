package com.apps.naviai.memory

/**
 * Heuristic keyword-based category classification for a memory's content
 * (checked in priority order: a route-ish memory wins over a vague
 * "preference" match even if both keyword sets happen to appear, since a
 * concrete location is more specifically useful than a general like/dislike).
 * Not ML/NLU -- same pragmatic approach as the rest of this app's matchers.
 */
object MemoryCategoryClassifier {
    private val ROUTE_KEYWORDS = listOf(
        "rumah", "alamat", "kantor", "rute", "jalan ke", "lokasi", "berada di",
        "address", "route", "home", "office", "located"
    )
    private val NAVIGATION_PREFERENCE_KEYWORDS = listOf(
        "navigasi", "arah", "belok", "kecepatan jalan", "jalur",
        "navigation", "walking speed", "turn", "pace"
    )
    private val PERSONAL_PREFERENCE_KEYWORDS = listOf(
        "suka", "tidak suka", "favorit", "alergi", "benci",
        "like", "dislike", "favorite", "allergic", "prefer", "hate"
    )

    fun classify(content: String): MemoryCategory {
        val normalized = content.lowercase()
        return when {
            ROUTE_KEYWORDS.any { normalized.contains(it) } -> MemoryCategory.ROUTE_INFORMATION
            NAVIGATION_PREFERENCE_KEYWORDS.any { normalized.contains(it) } -> MemoryCategory.NAVIGATION_PREFERENCE
            PERSONAL_PREFERENCE_KEYWORDS.any { normalized.contains(it) } -> MemoryCategory.PERSONAL_PREFERENCE
            else -> MemoryCategory.GENERAL_MEMORY
        }
    }
}

/** Heuristic: did the user explicitly flag this as important? Not inferred from content length/topic. */
object MemoryImportanceClassifier {
    private val KEYWORDS = listOf("penting", "darurat", "important", "critical", "emergency")

    fun isImportant(content: String): Boolean {
        val normalized = content.lowercase()
        return KEYWORDS.any { normalized.contains(it) }
    }
}
