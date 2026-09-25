package com.apps.naviai.voiceagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProModeCommandMatcherTest {

    @Test
    fun `recognizes enter phrasing in Indonesian and English`() {
        assertTrue(ProModeCommandMatcher.isEnter("mode pro"))
        assertTrue(ProModeCommandMatcher.isEnter("pro mode"))
        assertTrue(ProModeCommandMatcher.isEnter("Mode Pro."))
    }

    @Test
    fun `recognizes exit phrasing in Indonesian and English`() {
        assertTrue(ProModeCommandMatcher.isExit("matikan mode pro"))
        assertTrue(ProModeCommandMatcher.isExit("keluar dari mode pro"))
        assertTrue(ProModeCommandMatcher.isExit("turn off pro mode"))
        assertTrue(ProModeCommandMatcher.isExit("exit pro mode!"))
    }

    @Test
    fun `does not match unrelated commands`() {
        assertFalse(ProModeCommandMatcher.isEnter("jelaskan lingkungan saya"))
        assertFalse(ProModeCommandMatcher.isExit("mulai navigasi kantor"))
        assertFalse(ProModeCommandMatcher.isEnter(""))
        assertFalse(ProModeCommandMatcher.isExit(""))
    }
}
