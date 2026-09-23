package com.apps.naviai.location

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class GeoMathTest {

    @Test
    fun `distance between identical points is zero`() {
        assertEquals(0.0, GeoMath.distanceMeters(-6.2, 106.8, -6.2, 106.8), 0.001)
    }

    @Test
    fun `distance for one degree of latitude is about 111km`() {
        val distance = GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, distance, 500.0)
    }

    @Test
    fun `bearing due north is 0`() {
        val bearing = GeoMath.bearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, bearing, 0.5)
    }

    @Test
    fun `bearing due east is 90`() {
        val bearing = GeoMath.bearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, bearing, 0.5)
    }

    @Test
    fun `bearing due south is 180`() {
        val bearing = GeoMath.bearingDegrees(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, bearing, 0.5)
    }

    @Test
    fun `bearing due west is 270`() {
        val bearing = GeoMath.bearingDegrees(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0, bearing, 0.5)
    }

    @Test
    fun `angular difference is positive for a right turn`() {
        assertEquals(90.0, GeoMath.angularDifference(0.0, 90.0), 0.001)
    }

    @Test
    fun `angular difference is negative for a left turn`() {
        assertEquals(-90.0, GeoMath.angularDifference(90.0, 0.0), 0.001)
    }

    @Test
    fun `angular difference wraps around the 0-360 boundary correctly`() {
        // From heading 350 to target bearing 10 is a 20-degree right turn, not -340.
        assertEquals(20.0, GeoMath.angularDifference(350.0, 10.0), 0.001)
        // From heading 10 to target bearing 350 is a 20-degree left turn, not 340.
        assertEquals(-20.0, GeoMath.angularDifference(10.0, 350.0), 0.001)
    }

    @Test
    fun `angular difference for an exact reversal is 180`() {
        assertEquals(180.0, abs(GeoMath.angularDifference(0.0, 180.0)), 0.001)
    }
}
