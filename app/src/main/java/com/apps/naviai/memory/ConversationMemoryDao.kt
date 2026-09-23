package com.apps.naviai.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMemoryDao {
    @Insert
    suspend fun insert(memory: ConversationMemoryEntity): Long

    @Update
    suspend fun update(memory: ConversationMemoryEntity)

    @Query("SELECT * FROM conversation_memories ORDER BY isImportant DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationMemoryEntity>>

    @Query("SELECT * FROM conversation_memories ORDER BY isImportant DESC, updatedAt DESC")
    suspend fun getAll(): List<ConversationMemoryEntity>

    @Query("SELECT * FROM conversation_memories WHERE id = :id")
    suspend fun getById(id: Long): ConversationMemoryEntity?

    @Query("DELETE FROM conversation_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversation_memories")
    suspend fun deleteAll()
}
