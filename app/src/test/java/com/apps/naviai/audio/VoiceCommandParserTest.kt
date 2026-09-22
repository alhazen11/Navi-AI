package com.apps.naviai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun `extracts the command following the wake word`() {
        assertEquals("turn on the flashlight", VoiceCommandParser.extractCommand("NAVI turn on the flashlight"))
    }

    @Test
    fun `wake word match is case insensitive`() {
        assertEquals("stop", VoiceCommandParser.extractCommand("navi stop"))
        assertEquals("stop", VoiceCommandParser.extractCommand("Navi stop"))
        assertEquals("stop", VoiceCommandParser.extractCommand("NAVI stop"))
    }

    @Test
    fun `strips a comma or colon separator after the wake word`() {
        assertEquals("apa itu di depan", VoiceCommandParser.extractCommand("NAVI, apa itu di depan"))
        assertEquals("what is ahead", VoiceCommandParser.extractCommand("NAVI: what is ahead"))
    }

    @Test
    fun `returns blank, not null, when the wake word is said with nothing after it`() {
        assertEquals("", VoiceCommandParser.extractCommand("NAVI"))
        assertEquals("", VoiceCommandParser.extractCommand("navi   "))
    }

    @Test
    fun `returns null when speech does not start with the wake word`() {
        assertNull(VoiceCommandParser.extractCommand("hello there"))
        assertNull(VoiceCommandParser.extractCommand("turn on NAVI mode"))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(VoiceCommandParser.extractCommand(""))
        assertNull(VoiceCommandParser.extractCommand("   "))
    }

    @Test
    fun `a custom wake word list can be supplied`() {
        assertEquals("go", VoiceCommandParser.extractCommand("assistant go", wakeWords = listOf("assistant")))
        assertNull(VoiceCommandParser.extractCommand("NAVI go", wakeWords = listOf("assistant")))
    }

    @Test
    fun `Navy is accepted as an alias for NAVI`() {
        // Speech recognizers reliably mis-transcribe "NAVI" as the real
        // English word "Navy" -- confirmed on-device, this is the actual
        // fix for that, not a hypothetical.
        assertEquals("turn on the flashlight", VoiceCommandParser.extractCommand("Navy turn on the flashlight"))
        assertEquals("turn on the flashlight", VoiceCommandParser.extractCommand("navy turn on the flashlight"))
    }

    @Test
    fun `mentionsWakeWord also recognizes the Navy alias`() {
        assertTrue(VoiceCommandParser.mentionsWakeWord("uh navy turn on"))
    }

    @Test
    fun `leading and trailing whitespace on the whole utterance is ignored`() {
        assertEquals("stop", VoiceCommandParser.extractCommand("  NAVI stop  "))
    }

    @Test
    fun `mentionsWakeWord finds a clean prefix`() {
        assertTrue(VoiceCommandParser.mentionsWakeWord("NAVI stop"))
    }

    @Test
    fun `mentionsWakeWord finds the wake word mid-sentence`() {
        assertTrue(VoiceCommandParser.mentionsWakeWord("uh navi turn on the light"))
        assertTrue(VoiceCommandParser.mentionsWakeWord("hey NAVI"))
    }

    @Test
    fun `mentionsWakeWord does not match a substring inside another word`() {
        assertFalse(VoiceCommandParser.mentionsWakeWord("navigation is broken"))
        assertFalse(VoiceCommandParser.mentionsWakeWord("naviai app"))
    }

    @Test
    fun `mentionsWakeWord is false for ambient speech unrelated to NAVI`() {
        assertFalse(VoiceCommandParser.mentionsWakeWord("what time is it"))
        assertFalse(VoiceCommandParser.mentionsWakeWord(""))
    }
}
