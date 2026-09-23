package com.apps.naviai.memory

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface ConversationMemoryRepository {
    fun observeAll(): Flow<List<ConversationMemoryEntity>>
    suspend fun getAllOnce(): List<ConversationMemoryEntity>

    /** @return the new memory's id. */
    suspend fun save(content: String, category: MemoryCategory, isImportant: Boolean): Long

    suspend fun update(id: Long, content: String, category: MemoryCategory, isImportant: Boolean)
    suspend fun delete(id: Long)
    suspend fun deleteAll()
}

@Singleton
class ConversationMemoryRepositoryImpl @Inject constructor(
    private val dao: ConversationMemoryDao
) : ConversationMemoryRepository {

    override fun observeAll(): Flow<List<ConversationMemoryEntity>> = dao.observeAll()

    override suspend fun getAllOnce(): List<ConversationMemoryEntity> = dao.getAll()

    override suspend fun save(content: String, category: MemoryCategory, isImportant: Boolean): Long {
        val now = System.currentTimeMillis()
        return dao.insert(ConversationMemoryEntity(content = content, category = category, createdAt = now, updatedAt = now, isImportant = isImportant))
    }

    override suspend fun update(id: Long, content: String, category: MemoryCategory, isImportant: Boolean) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.copy(content = content, category = category, isImportant = isImportant, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun deleteAll() = dao.deleteAll()
}
