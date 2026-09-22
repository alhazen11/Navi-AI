package com.apps.naviai.detection.distance

import android.graphics.RectF
import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.domain.model.CameraParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DistanceEstimatorTest {

    private val estimator = MonocularHeightDistanceEstimator()

    private fun personDetection(boxHeightPx: Float, frameHeight: Int = 640): Detection = Detection(
        classId = 0,
        label = "person",
        confidence = 0.9f,
        boundingBox = RectF(100f, (frameHeight - boxHeightPx) / 2f, 150f, (frameHeight - boxHeightPx) / 2f + boxHeightPx),
        timestamp = 0L
    )

    @Test
    fun `known focal length and box height produce the expected distance`() {
        // distance = (realHeight * focalLength) / boxHeightPx
        // person real height = 1.70m; pick focalLength=1000, boxHeight=340 -> distance = 1.70*1000/340 = 5.0m
        val params = CameraParameters(focalLengthPixels = 1000f, isCalibrated = true, frameWidth = 640, frameHeight = 640)
        val detection = personDetection(boxHeightPx = 340f)

        val estimate = estimator.estimateDistance(detection, params)

        assertEquals(5.0f, estimate!!.distanceMeters, 0.05f)
        assertEquals(DistanceMethod.CALIBRATED_KNOWN_HEIGHT, estimate.method)
    }

    @Test
    fun `uncalibrated camera parameters yield lower confidence than calibrated`() {
        val calibrated = CameraParameters(1000f, isCalibrated = true, 640, 640)
        val uncalibrated = CameraParameters(1000f, isCalibrated = false, 640, 640)
        val detection = personDetection(boxHeightPx = 340f)

        val calibratedEstimate = estimator.estimateDistance(detection, calibrated)!!
        val uncalibratedEstimate = estimator.estimateDistance(detection, uncalibrated)!!

        assertTrue(uncalibratedEstimate.confidence < calibratedEstimate.confidence)
        assertEquals(DistanceMethod.UNCALIBRATED_KNOWN_HEIGHT, uncalibratedEstimate.method)
    }

    @Test
    fun `unknown object class returns null`() {
        val detection = Detection(
            classId = 79,
            label = "not-a-real-coco-class",
            confidence = 0.9f,
            boundingBox = RectF(0f, 0f, 50f, 50f),
            timestamp = 0L
        )
        val params = CameraParameters.defaultFor(640, 640)

        assertNull(estimator.estimateDistance(detection, params))
    }

    @Test
    fun `degenerate near-zero-height box returns null instead of an absurd distance`() {
        val detection = Detection(
            classId = 0,
            label = "person",
            confidence = 0.9f,
            boundingBox = RectF(0f, 0f, 50f, 0.5f),
            timestamp = 0L
        )
        val params = CameraParameters.defaultFor(640, 640)

        assertNull(estimator.estimateDistance(detection, params))
    }

    @Test
    fun `box touching the frame edge is penalized with lower confidence`() {
        val params = CameraParameters(1000f, isCalibrated = true, 640, 640)
        val centered = Detection(0, "person", 0.9f, RectF(100f, 150f, 150f, 490f), 0L)
        val clipped = Detection(0, "person", 0.9f, RectF(100f, 0f, 150f, 340f), 0L)

        val centeredEstimate = estimator.estimateDistance(centered, params)!!
        val clippedEstimate = estimator.estimateDistance(clipped, params)!!

        assertTrue(clippedEstimate.confidence < centeredEstimate.confidence)
    }

    @Test
    fun `low-reliability class such as bicycle never reports high confidence`() {
        val params = CameraParameters(1000f, isCalibrated = true, 640, 640)
        val detection = Detection(1, "bicycle", 0.9f, RectF(100f, 150f, 250f, 490f), 0L)

        val estimate = estimator.estimateDistance(detection, params)!!

        assertTrue(estimate.confidence < 0.6f)
    }
}
