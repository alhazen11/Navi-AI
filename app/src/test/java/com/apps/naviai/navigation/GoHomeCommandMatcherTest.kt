package com.apps.naviai.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoHomeCommandMatcherTest {

    @Test
    fun `recognizes the bare and full phrasing in Indonesian and English`() {
        assertTrue(GoHomeCommandMatcher.isMatch("kembali"))
        assertTrue(GoHomeCommandMatcher.isMatch("Kembali."))
        assertTrue(GoHomeCommandMatcher.isMatch("kembali ke home"))
        assertTrue(GoHomeCommandMatcher.isMatch("kembali ke beranda"))
        assertTrue(GoHomeCommandMatcher.isMatch("go home"))
        assertTrue(GoHomeCommandMatcher.isMatch("go to home"))
        assertTrue(GoHomeCommandMatcher.isMatch("Go Home!"))
    }

    @Test
    fun `does not match unrelated commands`() {
        assertFalse(GoHomeCommandMatcher.isMatch("mulai merekam jalan"))
        assertFalse(GoHomeCommandMatcher.isMatch("jelaskan lingkungan saya"))
        assertFalse(GoHomeCommandMatcher.isMatch(""))
    }
}
