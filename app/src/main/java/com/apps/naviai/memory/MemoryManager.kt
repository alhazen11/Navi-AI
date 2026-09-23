package com.apps.naviai.memory

import android.util.Log
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.audio.VoiceCommandManager
import com.apps.naviai.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the actual save/recall/forget/clear-all logic for Conversation
 * Memory and speaks every response via TTS -- the "brain" behind the
 * memory voice commands, analogous to how [com.apps.naviai.routenav.NavigationController]
 * owns route-navigation logic. Not tied to any screen; [handleCommand] is
 * called from wherever a recognized voice command is dispatched (see
 * [com.apps.naviai.ui.viewmodel.DetectionViewModel], this app's single
 * screen for every voice-triggered feature).
 *
 * Modularity/extensibility (per the feature's own requirement): all
 * command *recognition* lives in [MemoryCommandMatcher] and content
 * *classification* in [MemoryCategoryClassifier]/[MemoryImportanceClassifier]
 * -- both pure, swappable functions with no dependency on this class or on
 * Room. A future smarter (e.g. LLM-backed) classifier or matcher can
 * replace either without touching [MemoryManager]'s orchestration, and
 * [ConversationMemoryRepository] is an interface so the storage backend
 * itself is swappable too.
 *
 * [handleCommand] is `suspend` specifically so the free-form recall-question
 * branch (e.g. "di mana rumah saya?") can actually look the query up
 * *before* deciding whether to claim the command -- it only speaks/returns
 * true when a relevant memory is genuinely found. Otherwise it returns
 * false so the caller can fall through to other handlers: a bare question
 * is exactly the same shape [com.apps.naviai.scene.ObjectSearchMatcher]
 * matches ("di mana X" -> is X visible right now), so without this check
 * memory would wrongly swallow every "di mana ...''/"where is ..." Object
 * Search question, not just ones actually about a saved memory.
 */
@Singleton
class MemoryManager @Inject constructor(
    private val repository: ConversationMemoryRepository,
    private val voiceCommandManager: VoiceCommandManager,
    settingsRepository: SettingsRepository
) {
    // Dispatchers.Main.immediate, not a bare SupervisorJob: speak() (via
    // VoiceCommandManager.speakMuted()) calls transcriber.setMuted(), which
    // for the on-device engine calls SpeechRecognizer.cancel() -- an API
    // that must be called from the main thread. A bare SupervisorJob
    // defaults to Dispatchers.Default, a background thread pool, which
    // silently violated that contract every time a voice-triggered
    // save/forget/recall/clear-all response tried to mute the mic.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var language = AnnouncementLanguage.INDONESIAN

    init {
        scope.launch {
            settingsRepository.settings.collect { language = it.speechLanguage }
        }
    }

    /**
     * @return true if [command] was a memory-related command (and has been
     *   fully handled, including speaking a response) -- false if it
     *   wasn't a memory command at all, or was a bare question that didn't
     *   actually match any saved memory (see class doc), so the caller can
     *   try other intents.
     */
    suspend fun handleCommand(command: String): Boolean {
        val toRemember = MemoryCommandMatcher.extractToRemember(command)
        val toForget = MemoryCommandMatcher.extractToForget(command)

        return when {
            MemoryCommandMatcher.isClearAll(command) -> {
                confirmClearAll()
                true
            }
            toRemember != null -> {
                saveMemory(toRemember)
                true
            }
            toForget != null -> {
                forgetMemory(toForget)
                true
            }
            MemoryCommandMatcher.isGeneralRecall(command) -> {
                recallAll()
                true
            }
            else -> {
                val query = MemoryCommandMatcher.extractRecallQuery(command) ?: return false
                recallQuery(query)
            }
        }
    }

    private fun saveMemory(rawContent: String) {
        val content = rawContent.trim()
        if (content.isBlank()) {
            speak(nothingToRememberMessage(language))
            return
        }
        val category = MemoryCategoryClassifier.classify(content)
        val important = MemoryImportanceClassifier.isImportant(content)
        scope.launch {
            repository.save(content, category, important)
            Log.i(TAG, "Saved memory (category=$category, important=$important): \"$content\"")
            speak(memorySavedMessage(language))
        }
    }

    private fun forgetMemory(rawContent: String) {
        val content = rawContent.trim()
        scope.launch {
            val best = MemorySearch.search(content, repository.getAllOnce()).firstOrNull()
            if (best == null) {
                speak(memoryNotFoundMessage(language))
                return@launch
            }
            repository.delete(best.id)
            Log.i(TAG, "Deleted memory id=${best.id}: \"${best.content}\"")
            speak(memoryDeletedMessage(language))
        }
    }

    private fun recallAll() {
        scope.launch {
            val memories = repository.getAllOnce()
            if (memories.isEmpty()) {
                speak(noMemoriesMessage(language))
                return@launch
            }
            speak(memories.joinToString(" ") { it.content.trimEnd('.') + "." })
        }
    }

    /** @return true (and speaks the match) only if a relevant memory was actually found -- see class doc for why this matters for the caller. */
    private suspend fun recallQuery(query: String): Boolean {
        val best = MemorySearch.search(query, repository.getAllOnce()).firstOrNull() ?: return false
        speak(best.content)
        return true
    }

    /**
     * "Hapus semua ingatan saya" requires spoken confirmation first --
     * reuses [VoiceCommandManager.speakThenAwaitRawUtterance] (the same
     * mute-until-NAVI-finishes-speaking dialogue step Route Recording's
     * naming step uses), not a plain speak()+awaitNextRawUtterance() pair:
     * without the mute, the mic captured NAVI's own confirmation prompt as
     * if it were the user's "ya"/"yes" answer.
     */
    private fun confirmClearAll() {
        voiceCommandManager.speakThenAwaitRawUtterance(confirmClearAllMessage(language)) { raw ->
            if (isAffirmative(raw)) {
                scope.launch {
                    repository.deleteAll()
                    Log.i(TAG, "All memories cleared (user confirmed)")
                    speak(allMemoriesClearedMessage(language))
                }
            } else {
                speak(clearAllCancelledMessage(language))
            }
        }
    }

    private fun isAffirmative(text: String): Boolean =
        text.trim().trimEnd('.', '!').lowercase() in AFFIRMATIVE_WORDS

    // voiceCommandManager.speakMuted(), not ttsManager.speak() directly:
    // without muting, the always-on mic picks up NAVI's own responses
    // (recalled memory content included) as new input the instant they're
    // spoken -- same self-listening problem Route Recording's confirmation
    // had.
    private fun speak(message: String) = voiceCommandManager.speakMuted(message)

    private fun nothingToRememberMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Sepertinya tidak ada yang perlu diingat."
        AnnouncementLanguage.ENGLISH -> "There doesn't seem to be anything to remember there."
    }

    private fun memorySavedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Baik, saya akan mengingatnya."
        AnnouncementLanguage.ENGLISH -> "Okay, I'll remember that."
    }

    private fun memoryDeletedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Informasi tersebut telah dihapus."
        AnnouncementLanguage.ENGLISH -> "That information has been deleted."
    }

    private fun memoryNotFoundMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Maaf, saya tidak menemukan ingatan itu."
        AnnouncementLanguage.ENGLISH -> "Sorry, I couldn't find that memory."
    }

    private fun noMemoriesMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Saya belum menyimpan ingatan apa pun."
        AnnouncementLanguage.ENGLISH -> "I don't have any memories saved yet."
    }

    private fun confirmClearAllMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Anda yakin ingin menghapus semua ingatan? Katakan 'ya' untuk konfirmasi."
        AnnouncementLanguage.ENGLISH -> "Are you sure you want to delete all memories? Say 'yes' to confirm."
    }

    private fun allMemoriesClearedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Semua ingatan telah dihapus."
        AnnouncementLanguage.ENGLISH -> "All memories have been deleted."
    }

    private fun clearAllCancelledMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Baik, ingatan tidak dihapus."
        AnnouncementLanguage.ENGLISH -> "Okay, memories were not deleted."
    }

    private companion object {
        const val TAG = "MemoryManager"
        val AFFIRMATIVE_WORDS = setOf("ya", "iya", "yes", "benar", "betul", "ok", "oke", "okay")
    }
}
