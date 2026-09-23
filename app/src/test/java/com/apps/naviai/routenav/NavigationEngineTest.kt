package com.apps.naviai.routenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    // A short straight route heading due north: three points ~11m apart.
    private val route = listOf(
        NavPoint(0.0, 0.0),
        NavPoint(0.0001, 0.0),
        NavPoint(0.0002, 0.0)
    )

    @Test(expected = IllegalArgumentException::class)
    fun `an empty route is rejected`() {
        NavigationEngine(emptyList())
    }

    @Test
    fun `poor GPS accuracy is reported before anything else`() {
        val engine = NavigationEngine(route)
        val event = engine.update(0.0, 0.0, gpsAccuracyMeters = 50f, headingDegrees = 0.0)
        assertEquals(NavigationEvent.PoorGpsAccuracy, event)
    }

    @Test
    fun `walking straight toward the next point with no heading gives STRAIGHT guidance`() {
        val engine = NavigationEngine(route)
        // Far from the first target but well within the route corridor.
        val event = engine.update(-0.0001, 0.0, gpsAccuracyMeters = 5f, headingDegrees = null)
        assertTrue(event is NavigationEvent.Guidance)
        assertEquals(TurnInstruction.STRAIGHT, (event as NavigationEvent.Guidance).turn)
    }

    @Test
    fun `heading east while the target is north calls for a left turn`() {
        val engine = NavigationEngine(route)
        // At the first point already; target is the second point, due north (bearing 0).
        // Heading east (90) means the target is 90 degrees to the left.
        val event = engine.update(0.0, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 90.0)
        assertTrue(event is NavigationEvent.Guidance)
        assertEquals(TurnInstruction.LEFT, (event as NavigationEvent.Guidance).turn)
    }

    @Test
    fun `heading west while the target is north calls for a right turn`() {
        val engine = NavigationEngine(route)
        val event = engine.update(0.0, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 270.0)
        assertTrue(event is NavigationEvent.Guidance)
        assertEquals(TurnInstruction.RIGHT, (event as NavigationEvent.Guidance).turn)
    }

    @Test
    fun `heading exactly opposite the target calls for a sharp turn`() {
        val engine = NavigationEngine(route)
        val event = engine.update(0.0, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 180.0)
        assertTrue(event is NavigationEvent.Guidance)
        val turn = (event as NavigationEvent.Guidance).turn
        assertTrue(turn == TurnInstruction.SHARP_LEFT || turn == TurnInstruction.SHARP_RIGHT)
    }

    @Test
    fun `advances to the next target once within proximity of the current one`() {
        // Points are ~11m apart; a 5m proximity threshold means reaching
        // point 0 advances the target to point 1, but not straight past it
        // to point 2 (11m away) in the same update.
        val engine = NavigationEngine(route, NavigationThresholds(routePointProximityMeters = 5.0))
        assertEquals(0, engine.currentTargetIndex)
        engine.update(0.0, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 0.0)
        assertEquals(1, engine.currentTargetIndex)
    }

    @Test
    fun `arrives when within the destination radius of the final point`() {
        val engine = NavigationEngine(route, NavigationThresholds(routePointProximityMeters = 5.0, destinationArrivalRadiusMeters = 15.0))
        // Walk the route point by point, as a real GPS trace would.
        engine.update(0.0, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 0.0) // target 0 -> 1
        val event = engine.update(0.0001, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 0.0) // target 1 -> 2, within arrival radius
        assertEquals(NavigationEvent.Arrived, event)
        assertTrue(engine.isArrived)
    }

    @Test
    fun `stays Arrived on subsequent updates`() {
        val engine = NavigationEngine(route, NavigationThresholds(routePointProximityMeters = 5.0, destinationArrivalRadiusMeters = 15.0))
        engine.update(0.0, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 0.0)
        engine.update(0.0001, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 0.0) // arrives here
        val third = engine.update(0.0002, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 0.0)
        assertEquals(NavigationEvent.Arrived, third)
    }

    @Test
    fun `reports Deviated once far enough from every recorded point`() {
        val engine = NavigationEngine(route, NavigationThresholds(routeDeviationMeters = 10.0))
        // ~0.001 degrees longitude at the equator is roughly 111m away -- far off route.
        val event = engine.update(0.0, 0.001, gpsAccuracyMeters = 5f, headingDegrees = 0.0)
        assertEquals(NavigationEvent.Deviated, event)
        assertTrue(engine.isDeviated)
    }

    @Test
    fun `reports BackOnRoute after having deviated and returning`() {
        val engine = NavigationEngine(route, NavigationThresholds(routeDeviationMeters = 10.0))
        engine.update(0.0, 0.001, gpsAccuracyMeters = 5f, headingDegrees = 0.0)
        assertTrue(engine.isDeviated)
        val event = engine.update(0.00005, 0.0, gpsAccuracyMeters = 5f, headingDegrees = 0.0)
        assertEquals(NavigationEvent.BackOnRoute, event)
        assertTrue(!engine.isDeviated)
    }

    @Test
    fun `starts targeting startIndex instead of always the first point`() {
        val engine = NavigationEngine(route, startIndex = 2)
        assertEquals(2, engine.currentTargetIndex)
    }

    @Test
    fun `an out-of-range startIndex is clamped to the route bounds`() {
        assertEquals(2, NavigationEngine(route, startIndex = 99).currentTargetIndex)
        assertEquals(0, NavigationEngine(route, startIndex = -5).currentTargetIndex)
    }

    @Test
    fun `nearestPointIndex finds the closest recorded point to the given location`() {
        assertEquals(0, NavigationEngine.nearestPointIndex(route, 0.0, 0.0))
        assertEquals(2, NavigationEngine.nearestPointIndex(route, 0.0002, 0.0))
        // Slightly closer to the middle point (0.0001) than either end.
        assertEquals(1, NavigationEngine.nearestPointIndex(route, 0.00012, 0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nearestPointIndex rejects an empty route`() {
        NavigationEngine.nearestPointIndex(emptyList(), 0.0, 0.0)
    }
}
