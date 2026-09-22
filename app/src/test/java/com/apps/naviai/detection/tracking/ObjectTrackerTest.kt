package com.apps.naviai.detection.tracking

import android.graphics.RectF
import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.detection.distance.DistanceEstimate
import com.apps.naviai.detection.distance.DistanceMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObjectTrackerTest {

    private fun detectionAt(left: Float, top: Float, size: Float = 50f, classId: Int = 0): Detection = Detection(
        classId = classId,
        label = "person",
        confidence = 0.9f,
        boundingBox = RectF(left, top, left + size, top + size),
        timestamp = 0L
    )

    private fun distanceOf(meters: Float) = DistanceEstimate(meters, 0.8f, DistanceMethod.UNCALIBRATED_KNOWN_HEIGHT)

    @Test
    fun `a new detection is assigned a stable tracking id across frames`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)

        val first = tracker.update(listOf(detectionAt(100f, 100f) to null), nowMs = 0)
        assertEquals(1, first.size)
        val id = first[0].trackingId

        // Same object, moved slightly -- should match via IoU, not spawn a new id.
        val second = tracker.update(listOf(detectionAt(105f, 103f) to null), nowMs = 33)

        assertEquals(1, second.size)
        assertEquals(id, second[0].trackingId)
        assertEquals(2, second[0].framesTracked)
    }

    @Test
    fun `different classes never match to the same track`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)

        val first = tracker.update(listOf(detectionAt(100f, 100f, classId = 0) to null), nowMs = 0)
        val originalId = first[0].trackingId
        val result = tracker.update(listOf(detectionAt(102f, 101f, classId = 1) to null), nowMs = 33)

        // The class-1 detection cannot match the class-0 track (still within
        // its timeout window), so both persist as separate tracks.
        assertEquals(2, result.size)
        val originalTrack = result.first { it.trackingId == originalId }
        val newTrack = result.first { it.trackingId != originalId }
        assertEquals(0, originalTrack.detection.classId)
        assertEquals(1, newTrack.detection.classId)
    }

    @Test
    fun `a track not seen for longer than the timeout is dropped`() {
        val tracker = ObjectTracker(trackTimeoutMs = 500)

        tracker.update(listOf(detectionAt(100f, 100f) to null), nowMs = 0)
        val afterTimeout = tracker.update(emptyList(), nowMs = 1000)

        assertTrue(afterTimeout.isEmpty())
    }

    @Test
    fun `a track surviving within the timeout window is kept even with no observation`() {
        val tracker = ObjectTracker(trackTimeoutMs = 2000)

        tracker.update(listOf(detectionAt(100f, 100f) to null), nowMs = 0)
        val stillAlive = tracker.update(emptyList(), nowMs = 500)

        assertEquals(1, stillAlive.size)
    }

    @Test
    fun `decreasing distance over several frames is classified as approaching`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)
        var result: List<TrackedObject> = emptyList()

        val distances = listOf(5.0f, 4.5f, 4.0f, 3.4f)
        distances.forEachIndexed { i, d ->
            result = tracker.update(listOf(detectionAt(100f, 100f) to distanceOf(d)), nowMs = i * 33L)
        }

        assertEquals(MovementDirection.APPROACHING, result[0].movementDirection)
    }

    @Test
    fun `increasing distance over several frames is classified as receding`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)
        var result: List<TrackedObject> = emptyList()

        val distances = listOf(2.0f, 2.5f, 3.0f, 3.6f)
        distances.forEachIndexed { i, d ->
            result = tracker.update(listOf(detectionAt(100f, 100f) to distanceOf(d)), nowMs = i * 33L)
        }

        assertEquals(MovementDirection.RECEDING, result[0].movementDirection)
    }

    @Test
    fun `sustained lateral drift with stable distance is classified as lateral movement`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)
        var result: List<TrackedObject> = emptyList()

        val positions = listOf(50f, 90f, 130f, 170f)
        positions.forEachIndexed { i, x ->
            result = tracker.update(listOf(detectionAt(x, 100f) to distanceOf(3.0f)), nowMs = i * 33L)
        }

        assertEquals(MovementDirection.MOVING_LEFT_TO_RIGHT, result[0].movementDirection)
    }

    @Test
    fun `a brand new track reports unknown movement, not a false approaching signal`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)
        val result = tracker.update(listOf(detectionAt(100f, 100f) to distanceOf(2.0f)), nowMs = 0)

        assertEquals(MovementDirection.UNKNOWN, result[0].movementDirection)
    }

    @Test
    fun `markAnnounced persists the announcement timestamp on the track`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)
        val first = tracker.update(listOf(detectionAt(100f, 100f) to null), nowMs = 0)
        val id = first[0].trackingId
        assertEquals(null, first[0].lastAnnouncedAtMs)

        tracker.markAnnounced(id, atMs = 500)
        val second = tracker.update(listOf(detectionAt(101f, 100f) to null), nowMs = 33)

        assertNotNull(second.first { it.trackingId == id }.lastAnnouncedAtMs)
        assertEquals(500L, second.first { it.trackingId == id }.lastAnnouncedAtMs)
    }

    @Test
    fun `reset clears all tracks and restarts id assignment`() {
        val tracker = ObjectTracker(trackTimeoutMs = 5000)
        val before = tracker.update(listOf(detectionAt(100f, 100f) to null), nowMs = 0)

        tracker.reset()
        val after = tracker.update(listOf(detectionAt(100f, 100f) to null), nowMs = 0)

        assertEquals(before[0].trackingId, after[0].trackingId)
    }
}
