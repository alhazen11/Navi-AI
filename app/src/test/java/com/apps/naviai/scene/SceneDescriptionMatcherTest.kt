package com.apps.naviai.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDescriptionMatcherTest {

    @Test
    fun `matches Indonesian trigger phrases`() {
        assertTrue(SceneDescriptionMatcher.matches("jelaskan lingkungan saya"))
        assertTrue(SceneDescriptionMatcher.matches("deskripsikan sekitar saya"))
        assertTrue(SceneDescriptionMatcher.matches("apa yang ada di depan"))
        assertTrue(SceneDescriptionMatcher.matches("di mana saya sekarang"))
    }

    @Test
    fun `matches English trigger phrases`() {
        assertTrue(SceneDescriptionMatcher.matches("describe my surroundings"))
        assertTrue(SceneDescriptionMatcher.matches("what's in front of me"))
        assertTrue(SceneDescriptionMatcher.matches("where am I"))
        assertTrue(SceneDescriptionMatcher.matches("what's in this room"))
    }

    @Test
    fun `matches the room-focused phrasing use case`() {
        assertTrue(SceneDescriptionMatcher.matches("terangkan apa yang ada di ruangan ini"))
        assertTrue(SceneDescriptionMatcher.matches("apa yang ada di ruangan ini"))
        assertTrue(SceneDescriptionMatcher.matches("apa isi ruangan ini"))
        assertTrue(SceneDescriptionMatcher.matches("jelaskan ruangan ini"))
        assertTrue(SceneDescriptionMatcher.matches("describe this room"))
    }

    @Test
    fun `match is case insensitive`() {
        assertTrue(SceneDescriptionMatcher.matches("JELASKAN LINGKUNGAN SAYA"))
        assertTrue(SceneDescriptionMatcher.matches("Describe My Surroundings"))
    }

    @Test
    fun `unrelated commands do not match`() {
        assertFalse(SceneDescriptionMatcher.matches("turn on the flashlight"))
        assertFalse(SceneDescriptionMatcher.matches("stop"))
        assertFalse(SceneDescriptionMatcher.matches(""))
        assertFalse(SceneDescriptionMatcher.matches("   "))
    }
}
