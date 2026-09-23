package com.apps.naviai.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MemoryCategory { PERSONAL_PREFERENCE, ROUTE_INFORMATION, NAVIGATION_PREFERENCE, GENERAL_MEMORY }

@Entity(tableName = "conversation_memories")
data class ConversationMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: MemoryCategory,
    val createdAt: Long,
    val updatedAt: Long,
    val isImportant: Boolean
)
