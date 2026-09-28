package com.apps.naviai.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentLanguagesTest {

    @Test
    fun `accepts the languages the Voice Agent API documents`() {
        listOf("en", "es", "fr", "de", "it", "pt").forEach {
            assertTrue("$it should be supported", VoiceAgentLanguages.isSupported(it))
        }
    }

    /** The exact bug this object exists for -- see its class doc. */
    @Test
    fun `rejects Indonesian, which the Voice Agent pipeline does not transcribe`() {
        assertFalse(VoiceAgentLanguages.isSupported("id"))
        assertEquals(listOf("en"), VoiceAgentLanguages.filterSupported(listOf("id", "en")))
    }

    @Test
    fun `filters to empty so the caller omits the field and lets the server auto-detect`() {
        assertEquals(emptyList<String>(), VoiceAgentLanguages.filterSupported(listOf("id")))
        assertEquals(emptyList<String>(), VoiceAgentLanguages.filterSupported(emptyList()))
    }

    @Test
    fun `tolerates casing and region suffixes`() {
        assertTrue(VoiceAgentLanguages.isSupported("EN"))
        assertTrue(VoiceAgentLanguages.isSupported("en-US"))
        assertTrue(VoiceAgentLanguages.isSupported("en_us"))
        assertTrue(VoiceAgentLanguages.isSupported(" pt-BR "))
        assertEquals(listOf("en"), VoiceAgentLanguages.filterSupported(listOf("en-US", "en_GB")))
    }

    @Test
    fun `preserves order and drops duplicates`() {
        assertEquals(listOf("fr", "en"), VoiceAgentLanguages.filterSupported(listOf("fr", "id", "en", "fr")))
    }
}
