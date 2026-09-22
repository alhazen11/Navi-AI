package com.apps.naviai.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObjectSearchMatcherTest {

    @Test
    fun `extracts the query from Indonesian phrasings`() {
        assertEquals("botol", ObjectSearchMatcher.extractQuery("di mana botol?"))
        assertEquals("botol", ObjectSearchMatcher.extractQuery("dimana botol"))
        assertEquals("pintu", ObjectSearchMatcher.extractQuery("Ada pintu?"))
        assertEquals("tangga", ObjectSearchMatcher.extractQuery("Cari tangga."))
        assertEquals("kursi", ObjectSearchMatcher.extractQuery("carikan kursi"))
        assertEquals("meja", ObjectSearchMatcher.extractQuery("temukan meja"))
    }

    @Test
    fun `extracts the query from English phrasings, stripping a leading article`() {
        assertEquals("bottle", ObjectSearchMatcher.extractQuery("where is the bottle"))
        assertEquals("door", ObjectSearchMatcher.extractQuery("is there a door?"))
        assertEquals("stairs", ObjectSearchMatcher.extractQuery("find the stairs"))
        assertEquals("chairs", ObjectSearchMatcher.extractQuery("are there any chairs"))
        assertEquals("bottle", ObjectSearchMatcher.extractQuery("search for a bottle"))
    }

    @Test
    fun `strips trailing filler phrases`() {
        assertEquals("botol", ObjectSearchMatcher.extractQuery("di mana botol di sini"))
        assertEquals("door", ObjectSearchMatcher.extractQuery("where is the door nearby"))
    }

    @Test
    fun `returns null for commands that are not an object search`() {
        assertNull(ObjectSearchMatcher.extractQuery("jelaskan lingkungan saya"))
        assertNull(ObjectSearchMatcher.extractQuery("stop"))
        assertNull(ObjectSearchMatcher.extractQuery("turn on the flashlight"))
        assertNull(ObjectSearchMatcher.extractQuery(""))
        assertNull(ObjectSearchMatcher.extractQuery("   "))
    }
}
