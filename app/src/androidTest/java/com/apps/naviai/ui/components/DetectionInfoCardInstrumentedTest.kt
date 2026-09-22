package com.apps.naviai.ui.components

import android.graphics.RectF
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.TrackedObject
import com.apps.naviai.ui.theme.NaviAITheme
import org.junit.Rule
import org.junit.Test

class DetectionInfoCardInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsLabelDistanceAndTrackingId() {
        val tracked = TrackedObject(
            trackingId = 7,
            detection = Detection(0, "person", 0.87f, RectF(0f, 0f, 100f, 200f), 0L),
            estimatedDistanceMeters = 2.4f,
            distanceConfidence = 0.7f,
            movementDirection = MovementDirection.APPROACHING,
            riskLevel = RiskLevel.HIGH,
            framesTracked = 5,
            lastAnnouncedAtMs = null
        )

        composeRule.setContent {
            NaviAITheme {
                DetectionInfoCard(tracked = tracked)
            }
        }

        composeRule.onNodeWithText("Person").assertIsDisplayed()
        composeRule.onNodeWithText("#7").assertIsDisplayed()
    }
}
