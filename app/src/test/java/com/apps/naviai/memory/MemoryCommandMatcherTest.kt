package com.apps.naviai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCommandMatcherTest {

    @Test
    fun `extracts content from Indonesian save phrasings`() {
        assertEquals("rumah saya berada di Depok", MemoryCommandMatcher.extractToRemember("ingat bahwa rumah saya berada di Depok"))
        assertEquals("saya sering berjalan ke kantor", MemoryCommandMatcher.extractToRemember("ingat bahwa saya sering berjalan ke kantor"))
        assertEquals("saya suka kopi", MemoryCommandMatcher.extractToRemember("ingat saya suka kopi"))
    }

    @Test
    fun `extracts content from English save phrasings`() {
        assertEquals("I live in Depok", MemoryCommandMatcher.extractToRemember("remember that I live in Depok"))
        assertEquals("I like coffee", MemoryCommandMatcher.extractToRemember("remember I like coffee"))
    }

    @Test
    fun `extracts content from forget phrasings`() {
        assertEquals("alamat rumah saya", MemoryCommandMatcher.extractToForget("lupakan alamat rumah saya"))
        assertEquals("I live in Depok", MemoryCommandMatcher.extractToForget("forget that I live in Depok"))
    }

    @Test
    fun `recognizes clear-all commands`() {
        assertTrue(MemoryCommandMatcher.isClearAll("hapus semua ingatan saya"))
        assertTrue(MemoryCommandMatcher.isClearAll("clear all memories"))
        assertFalse(MemoryCommandMatcher.isClearAll("ingat bahwa saya suka kopi"))
    }

    @Test
    fun `recognizes general recall commands`() {
        assertTrue(MemoryCommandMatcher.isGeneralRecall("apa yang kamu ingat tentang saya?"))
        assertTrue(MemoryCommandMatcher.isGeneralRecall("what do you remember about me"))
        assertFalse(MemoryCommandMatcher.isGeneralRecall("di mana rumah saya"))
    }

    @Test
    fun `extracts a specific recall query from a question`() {
        assertEquals("di mana rumah saya", MemoryCommandMatcher.extractRecallQuery("di mana rumah saya?"))
        assertEquals("where do I live", MemoryCommandMatcher.extractRecallQuery("where do I live?"))
    }

    @Test
    fun `non-question commands do not extract a recall query`() {
        assertNull(MemoryCommandMatcher.extractRecallQuery("mulai merekam jalan"))
        assertNull(MemoryCommandMatcher.extractRecallQuery(""))
    }

    @Test
    fun `save and forget do not cross-match`() {
        assertNull(MemoryCommandMatcher.extractToForget("ingat bahwa saya suka kopi"))
        assertNull(MemoryCommandMatcher.extractToRemember("lupakan alamat rumah saya"))
    }
}
