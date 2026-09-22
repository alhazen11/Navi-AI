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
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.MEDIUM
            )
        )
        assertTrue(
            HazardTrigger.isBlockingHazard(
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.CRITICAL
            )
        )
    }

    @Test
    fun `below MEDIUM risk never triggers even if large and centered`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.LOW
            )
        )
        assertFalse(
            HazardTrigger.isBlockingHazard(
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.SAFE
            )
        )
    }

    @Test
    fun `small object never triggers even when centered and high risk`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                boxWidth = 40f, boxHeight = 40f, boxCenterX = 500f,
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.CRITICAL
            )
        )
    }

    @Test
    fun `large object off to the side never triggers`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 50f, // far left edge
                frameWidth = frameWidth, frameHeight = frameHeight, riskLevel = RiskLevel.HIGH
            )
        )
    }

    @Test
    fun `invalid frame dimensions never trigger`() {
        assertFalse(
            HazardTrigger.isBlockingHazard(
                boxWidth = 500f, boxHeight = 600f, boxCenterX = 500f,
                frameWidth = 0, frameHeight = 2000, riskLevel = RiskLevel.CRITICAL
            )
        )
    }
}
