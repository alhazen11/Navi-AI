package com.apps.naviai.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReadingMatcherTest {

    @Test
    fun `matches Indonesian trigger phrases`() {
        assertTrue(TextReadingMatcher.matches("bacakan tulisan ini"))
        assertTrue(TextReadingMatcher.matches("baca teks di kartu ini"))
        assertTrue(TextReadingMatcher.matches("apa tulisan ini"))
        assertTrue(TextReadingMatcher.matches("tolong bacakan"))
    }

    @Test
    fun `matches English trigger phrases`() {
        assertTrue(TextReadingMatcher.matches("read this text"))
        assertTrue(TextReadingMatcher.matches("what does this say"))
        assertTrue(TextReadingMatcher.matches("read this label"))
    }

    @Test
    fun `match is case insensitive`() {
        assertTrue(TextReadingMatcher.matches("BACAKAN TULISAN INI"))
        assertTrue(TextReadingMatcher.matches("Read This Text"))
    }

    @Test
    fun `unrelated commands do not match`() {
        assertFalse(TextReadingMatcher.matches("jelaskan lingkungan saya"))
        assertFalse(TextReadingMatcher.matches("turn on the flashlight"))
        assertFalse(TextReadingMatcher.matches(""))
        assertFalse(TextReadingMatcher.matches("   "))
    }

    @Test
    fun `does not collide with the scene description intent`() {
        assertFalse(SceneDescriptionMatcher.matches("bacakan tulisan ini"))
        assertFalse(TextReadingMatcher.matches("jelaskan lingkungan saya"))
    }
}
