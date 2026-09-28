package com.apps.naviai.scene

import com.apps.naviai.detection.risk.RiskLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardTriggerTest {

    private val frameWidth = 1000
    private val frameHeight = 2000

    @Test
    fun `large centered object at MEDIUM risk or above triggers`() {
        assertTrue(
            HazardTrigger.isBlockingHazard(
                label = "car",
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.MEDIUM
            )
        )
        assertTrue(
            HazardTrigger.isBlockingHazard(
                label = "car",
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.CRITICAL
            )
        )
    }

    @Test
    fun `below MEDIUM risk never triggers even if large and centered`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                label = "car",
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.LOW
            )
        )
        assertFalse(
            HazardTrigger.isBlockingHazard(
                label = "car",
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.SAFE
            )
        )
    }

    @Test
    fun `small object never triggers even when centered and high risk`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                label = "car",
                boxWidth = 40f, boxHeight = 40f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.CRITICAL
            )
        )
    }

    @Test
    fun `large object off to the side never triggers`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                label = "car",
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 50f, // far left edge
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.HIGH
            )
        )
    }

    @Test
    fun `invalid frame dimensions never trigger`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                label = "car",
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = 0, frameHeight = 2000, riskLevel = RiskLevel.CRITICAL
            )
        )
    }

    @Test
    fun `label outside the hazard allow-list never triggers even if large, centered and high risk`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                label = "dining table",
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.CRITICAL
            )
        )
    }

    @Test
    fun `labels drawn from each requested group all trigger`() {
        for (label in listOf(
            "person", "bicycle", "motorcycle", "bus", "truck", "bench", "chair", "couch", "dog", "horse",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "potted plant",
            "backpack", "suitcase", "umbrella", "handbag", "skateboard", "sports ball", "kite", "surfboard",
            "bird", "cat", "sheep", "cow", "elephant", "bear", "zebra", "giraffe"
        )) {
            assertTrue(
                "expected '$label' to be hazard-eligible",
                HazardTrigger.isBlockingHazard(
                    label = label,
                    boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                    frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.MEDIUM
                )
            )
        }
    }
}
