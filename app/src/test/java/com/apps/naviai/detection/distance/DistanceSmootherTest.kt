package com.apps.naviai.detection.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceSmootherTest {

    @Test
    fun `first reading is returned unsmoothed`() {
        val smoother = DistanceSmoother(alpha = 0.3f)
        assertEquals(5.0f, smoother.smooth(trackingId = 1, rawDistanceMeters = 5.0f)!!, 1e-4f)
    }

    @Test
    fun `smooths a sudden jump instead of jumping straight to it`() {
        val smoother = DistanceSmoother(alpha = 0.3f)
        smoother.smooth(1, 5.0f)
        val second = smoother.smooth(1, 2.0f)!!

        // alpha=0.3: next = 5.0 + 0.3*(2.0-5.0) = 4.1, i.e. partway toward the new reading, not all the way.
        assertEquals(4.1f, second, 1e-3f)
        assertTrue(second > 2.0f && second < 5.0f)
    }

    @Test
    fun `converges toward a sustained new value over repeated frames`() {
        val smoother = DistanceSmoother(alpha = 0.5f)
        var last = smoother.smooth(1, 10f)!!
        repeat(20) { last = smoother.smooth(1, 2f)!! }

        assertEquals(2f, last, 0.01f)
    }

    @Test
    fun `null reading returns the last smoothed value unchanged`() {
        val smoother = DistanceSmoother(alpha = 0.3f)
        smoother.smooth(1, 3.0f)
        val result = smoother.smooth(1, null)

        assertEquals(3.0f, result!!, 1e-4f)
    }

    @Test
    fun `different tracks are independent`() {
        val smoother = DistanceSmoother(alpha = 0.3f)
        smoother.smooth(1, 10f)
        smoother.smooth(2, 1f)

        assertEquals(10f, smoother.smooth(1, null)!!, 1e-4f)
        assertEquals(1f, smoother.smooth(2, null)!!, 1e-4f)
    }

    @Test
    fun `reset clears history for that track only`() {
        val smoother = DistanceSmoother(alpha = 0.3f)
        smoother.smooth(1, 10f)
        smoother.smooth(2, 5f)

        smoother.reset(1)

        assertNull(smoother.smooth(1, null))
        assertEquals(5f, smoother.smooth(2, null)!!, 1e-4f)
    }
}
