package com.apps.naviai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCategoryClassifierTest {

    @Test
    fun `classifies location content as ROUTE_INFORMATION`() {
        assertEquals(MemoryCategory.ROUTE_INFORMATION, MemoryCategoryClassifier.classify("rumah saya berada di Depok"))
        assertEquals(MemoryCategory.ROUTE_INFORMATION, MemoryCategoryClassifier.classify("alamat kantor saya di Jalan Sudirman"))
    }

    @Test
    fun `classifies navigation-related content as NAVIGATION_PREFERENCE`() {
        assertEquals(MemoryCategory.NAVIGATION_PREFERENCE, MemoryCategoryClassifier.classify("saya suka navigasi yang lambat"))
    }

    @Test
    fun `classifies liking-disliking content as PERSONAL_PREFERENCE`() {
        assertEquals(MemoryCategory.PERSONAL_PREFERENCE, MemoryCategoryClassifier.classify("saya suka kopi hitam"))
        assertEquals(MemoryCategory.PERSONAL_PREFERENCE, MemoryCategoryClassifier.classify("I am allergic to peanuts"))
    }

    @Test
    fun `falls back to GENERAL_MEMORY for unrecognized content`() {
        assertEquals(MemoryCategory.GENERAL_MEMORY, MemoryCategoryClassifier.classify("hari ini cuacanya cerah"))
    }

    @Test
    fun `route keywords take priority over preference keywords`() {
        // Contains both "suka" (preference) and "kantor" (route) -- route wins.
        assertEquals(MemoryCategory.ROUTE_INFORMATION, MemoryCategoryClassifier.classify("saya suka jalan ke kantor"))
    }

    @Test
    fun `importance keywords are detected`() {
        assertTrue(MemoryImportanceClassifier.isImportant("ini penting: obat saya ada di laci"))
        assertTrue(MemoryImportanceClassifier.isImportant("this is important"))
        assertFalse(MemoryImportanceClassifier.isImportant("saya suka kopi"))
    }
}
