package com.apps.naviai.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs [com.apps.naviai.ui.screens.MemoryScreen] -- the manual view/edit/delete UI for saved memories, alongside the voice commands [MemoryManager] handles. */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: ConversationMemoryRepository
) : ViewModel() {

    val memories: StateFlow<List<ConversationMemoryEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteMemory(id: Long) = viewModelScope.launch { repository.delete(id) }

    fun clearAll() = viewModelScope.launch { repository.deleteAll() }

    fun addMemory(content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            repository.save(trimmed, MemoryCategoryClassifier.classify(trimmed), MemoryImportanceClassifier.isImportant(trimmed))
        }
    }

    fun updateMemory(id: Long, newContent: String, category: MemoryCategory, isImportant: Boolean) {
        val trimmed = newContent.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { repository.update(id, trimmed, category, isImportant) }
    }
}
