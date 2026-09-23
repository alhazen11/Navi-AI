package com.apps.naviai.routenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCommandMatcherTest {

    @Test
    fun `extracts the route name from Indonesian phrasing`() {
        assertEquals("kantor", NavigationCommandMatcher.extractRouteName("mulai navigasi kantor"))
        assertEquals("rumah", NavigationCommandMatcher.extractRouteName("navigasi ke rumah"))
    }

    @Test
    fun `extracts the route name from English phrasing`() {
        assertEquals("office", NavigationCommandMatcher.extractRouteName("start navigation office"))
        assertEquals("office", NavigationCommandMatcher.extractRouteName("start navigation to office"))
        assertEquals("home", NavigationCommandMatcher.extractRouteName("navigate to home"))
    }

    @Test
    fun `strips trailing punctuation from the route name`() {
        assertEquals("kantor", NavigationCommandMatcher.extractRouteName("mulai navigasi kantor."))
        assertEquals("kantor", NavigationCommandMatcher.extractRouteName("mulai navigasi kantor?"))
    }

    @Test
    fun `recognizes the stop command`() {
        assertTrue(NavigationCommandMatcher.isStop("stop navigasi"))
        assertTrue(NavigationCommandMatcher.isStop("berhenti navigasi"))
        assertTrue(NavigationCommandMatcher.isStop("stop navigation"))
    }

    @Test
    fun `returns null for unrelated commands`() {
        assertNull(NavigationCommandMatcher.extractRouteName("jelaskan lingkungan saya"))
        assertNull(NavigationCommandMatcher.extractRouteName(""))
        assertNull(NavigationCommandMatcher.extractRouteName("stop navigasi"))
    }
}
