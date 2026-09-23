package com.apps.naviai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchTest {

    private fun memory(content: String, id: Long = 1L) = ConversationMemoryEntity(
        id = id, content = content, category = MemoryCategory.GENERAL_MEMORY,
        createdAt = 0L, updatedAt = 0L, isImportant = false
    )

    @Test
    fun `finds a memory sharing keywords with the query`() {
        val memories = listOf(memory("rumah saya berada di Depok", id = 1))
        val results = MemorySearch.search("di mana rumah saya", memories)
        assertEquals(1, results.size)
        assertEquals(1L, results[0].id)
    }

    @Test
    fun `ranks the best-overlapping memory first`() {
        val memories = listOf(
            memory("saya suka kopi", id = 1),
            memory("rumah saya berada di Depok dekat kantor", id = 2)
        )
        val results = MemorySearch.search("di mana rumah dan kantor saya", memories)
        assertEquals(2L, results[0].id)
    }

    @Test
    fun `returns empty when nothing overlaps`() {
        val memories = listOf(memory("saya suka kopi"))
        val results = MemorySearch.search("kapan ulang tahun kucing saya", memories)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `a query with only stop words matches nothing`() {
        val memories = listOf(memory("saya suka kopi"))
        assertTrue(MemorySearch.search("apa yang di mana", memories).isEmpty())
    }

    @Test
    fun `tokenize strips punctuation and stop words`() {
        val tokens = MemorySearch.tokenize("Rumah saya berada di Depok!")
        assertTrue(tokens.contains("rumah"))
        assertTrue(tokens.contains("depok"))
        assertTrue(!tokens.contains("saya"))
        assertTrue(!tokens.contains("di"))
    }
}
