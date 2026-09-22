package com.apps.naviai.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalPositionClassifierTest {

    private val frameWidth = 1000

    @Test
    fun `classifies objects near the center as CENTER`() {
        assertEquals(HorizontalPosition.CENTER, HorizontalPositionClassifier.classify(500f, frameWidth))
        assertEquals(HorizontalPosition.CENTER, HorizontalPositionClassifier.classify(420f, frameWidth))
        assertEquals(HorizontalPosition.CENTER, HorizontalPositionClassifier.classify(580f, frameWidth))
    }

    @Test
    fun `classifies objects on the left`() {
        assertEquals(HorizontalPosition.LEFT, HorizontalPositionClassifier.classify(100f, frameWidth))
        assertEquals(HorizontalPosition.LEFT, HorizontalPositionClassifier.classify(0f, frameWidth))
    }

    @Test
    fun `classifies objects on the right`() {
        assertEquals(HorizontalPosition.RIGHT, HorizontalPositionClassifier.classify(900f, frameWidth))
        assertEquals(HorizontalPosition.RIGHT, HorizontalPositionClassifier.classify(1000f, frameWidth))
    }

    @Test
    fun `invalid frame width defaults to CENTER`() {
        assertEquals(HorizontalPosition.CENTER, HorizontalPositionClassifier.classify(500f, 0))
    }
}
