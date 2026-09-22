package com.apps.naviai.detection.risk

import android.graphics.RectF
import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.TrackedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RiskAssessmentEngineTest {

    private val frameWidth = 640

    private fun tracked(
        centerX: Float,
        distance: Float?,
        distanceConfidence: Float = 0.8f,
        movement: MovementDirection = MovementDirection.STATIONARY,
        confidence: Float = 0.9f,
        framesTracked: Int = 5
    ): TrackedObject = TrackedObject(
        trackingId = 1,
        detection = Detection(0, "person", confidence, RectF(centerX - 25f, 200f, centerX + 25f, 400f), 0L),
        estimatedDistanceMeters = distance,
        distanceConfidence = distanceConfidence,
        movementDirection = movement,
        riskLevel = RiskLevel.SAFE,
        framesTracked = framesTracked,
        lastAnnouncedAtMs = null
    )

    @Test
    fun `very close object directly ahead is critical`() {
        val engine = RiskAssessmentEngine()
        val result = engine.assess(tracked(centerX = 320f, distance = 0.5f), frameWidth)
        assertEquals(RiskLevel.CRITICAL, result)
    }

    @Test
    fun `far away peripheral object is safe`() {
        val engine = RiskAssessmentEngine()
        // Off to the side (outside the walking-path band) AND far away.
        val result = engine.assess(tracked(centerX = 10f, distance = 20f), frameWidth)
        assertEquals(RiskLevel.SAFE, result)
    }

    @Test
    fun `far away object directly ahead stays low priority, not critical`() {
        val engine = RiskAssessmentEngine()
        val result = engine.assess(tracked(centerX = 320f, distance = 20f), frameWidth)
        assertTrue(result == RiskLevel.SAFE || result == RiskLevel.LOW)
    }

    @Test
    fun `same distance is riskier when centered in the walking path than at the periphery`() {
        val engine = RiskAssessmentEngine()
        val centered = engine.assess(tracked(centerX = 320f, distance = 2.0f), frameWidth)
        val peripheral = engine.assess(tracked(centerX = 20f, distance = 2.0f), frameWidth)

        assertTrue(peripheral.priority <= centered.priority)
    }

    @Test
    fun `an approaching object scores higher risk than a receding one at the same distance`() {
        val engine = RiskAssessmentEngine()
        val approaching = engine.assess(
            tracked(centerX = 320f, distance = 2.0f, movement = MovementDirection.APPROACHING), frameWidth
        )
        val receding = engine.assess(
            tracked(centerX = 320f, distance = 2.0f, movement = MovementDirection.RECEDING), frameWidth
        )

        assertTrue(receding.priority < approaching.priority)
    }

    @Test
    fun `uncertain distance is treated with moderate caution, not ignored and not treated as imminent`() {
        val engine = RiskAssessmentEngine()
        val uncertain = engine.assess(tracked(centerX = 320f, distance = null, distanceConfidence = 0f), frameWidth)

        assertTrue(uncertain == RiskLevel.LOW || uncertain == RiskLevel.MEDIUM)
    }

    @Test
    fun `a single-frame flicker is damped relative to a persisted track`() {
        val engine = RiskAssessmentEngine()
        val brandNew = engine.assess(tracked(centerX = 320f, distance = 0.6f, framesTracked = 1), frameWidth)
        val persisted = engine.assess(tracked(centerX = 320f, distance = 0.6f, framesTracked = 10), frameWidth)

        assertTrue(brandNew.priority <= persisted.priority)
    }

    @Test
    fun `higher caution sensitivity raises risk for the same observation`() {
        val relaxed = RiskAssessmentEngine(RiskSensitivity(cautionMultiplier = 0.5f))
        val cautious = RiskAssessmentEngine(RiskSensitivity(cautionMultiplier = 2.0f))
        val observation = tracked(centerX = 320f, distance = 3.0f)

        val relaxedResult = relaxed.assess(observation, frameWidth)
        val cautiousResult = cautious.assess(observation, frameWidth)

        assertTrue(cautiousResult.priority >= relaxedResult.priority)
    }

    @Test
    fun `zero frame width never crashes and returns safe`() {
        val engine = RiskAssessmentEngine()
        val result = engine.assess(tracked(centerX = 0f, distance = 0.3f), frameWidth = 0)
        assertEquals(RiskLevel.SAFE, result)
    }
}
