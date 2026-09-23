package com.apps.naviai.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteRenameMatcherTest {

    @Test
    fun `extracts old and new names from Indonesian phrasing`() {
        assertEquals(RouteRenameRequest("kantor", "rumah"), RouteRenameMatcher.extract("ganti nama rute kantor menjadi rumah"))
        assertEquals(RouteRenameRequest("kantor", "rumah"), RouteRenameMatcher.extract("ubah nama rute kantor jadi rumah"))
    }

    @Test
    fun `extracts old and new names from English phrasing`() {
        assertEquals(RouteRenameRequest("office", "home"), RouteRenameMatcher.extract("rename route office to home"))
        assertEquals(RouteRenameRequest("office", "home"), RouteRenameMatcher.extract("rename the route office to home"))
        assertEquals(RouteRenameRequest("office", "home"), RouteRenameMatcher.extract("change route office to home"))
        assertEquals(RouteRenameRequest("office", "home"), RouteRenameMatcher.extract("change the name of route office to home"))
    }

    @Test
    fun `strips trailing punctuation from both names`() {
        assertEquals(RouteRenameRequest("kantor", "rumah"), RouteRenameMatcher.extract("ganti nama rute kantor menjadi rumah."))
    }

    @Test
    fun `returns null for unrelated commands`() {
        assertNull(RouteRenameMatcher.extract("mulai navigasi kantor"))
        assertNull(RouteRenameMatcher.extract("mulai merekam jalan"))
        assertNull(RouteRenameMatcher.extract(""))
    }
}
