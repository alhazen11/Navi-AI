package com.apps.naviai.routenav

import com.apps.naviai.location.GeoMath
import kotlin.math.abs

/** A recorded route waypoint, stripped down to just what navigation math needs. */
data class NavPoint(val latitude: Double, val longitude: Double)

/**
 * All the distance/accuracy knobs the acceptance criteria calls out as
 * "must be configurable" -- exposed as constructor params with sensible
 * defaults rather than hardcoded constants, so a future Settings screen can
 * surface them without touching [NavigationEngine].
 */
data class NavigationThresholds(
    /** How close to a route point counts as "reached it, advance to the next one". */
    val routePointProximityMeters: Double = 8.0,
    /** How close to the FINAL route point counts as "arrived". */
    val destinationArrivalRadiusMeters: Double = 10.0,
    /** Distance from the nearest recorded point beyond which the user is considered off-route. */
    val routeDeviationMeters: Double = 20.0,
    /** GPS fixes worse than this are treated as unusable for turn guidance. */
    val minAcceptableGpsAccuracyMeters: Float = 25f
)

enum class TurnInstruction { STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT }

sealed interface NavigationEvent {
    data class Guidance(val turn: TurnInstruction, val distanceToNextPointMeters: Double) : NavigationEvent
    data object Deviated : NavigationEvent
    data object BackOnRoute : NavigationEvent
    data object ApproachingDestination : NavigationEvent
    data object Arrived : NavigationEvent
    data object PoorGpsAccuracy : NavigationEvent
}

/**
 * Pure route-following state machine: given the ordered list of recorded
 * route points and a stream of (location, accuracy, heading) updates,
 * decides what guidance event applies next. No Android framework types
 * (see [GeoMath]) -- entirely unit-testable without a device/emulator or
 * Robolectric, which matters here because GPS/compass behavior can't be
 * exercised live in this environment.
 *
 * Route deviation is approximated as "distance to the nearest recorded
 * point exceeds [NavigationThresholds.routeDeviationMeters]" rather than a
 * true perpendicular distance to the route polyline -- simpler, and close
 * enough given recorded points are themselves just a walked path sampled
 * at GPS-update intervals, not a precise surveyed line.
 */
class NavigationEngine(
    private val routePoints: List<NavPoint>,
    private val thresholds: NavigationThresholds = NavigationThresholds(),
    /**
     * Index into [routePoints] to target first -- defaults to the very
     * first recorded point, but callers starting navigation from wherever
     * the user actually is (see [nearestPointIndex]) pass that instead, so
     * navigation doesn't insist on walking back to the route's recorded
     * starting point first.
     */
    startIndex: Int = 0
) {
    init {
        require(routePoints.isNotEmpty()) { "routePoints must not be empty" }
    }

    var currentTargetIndex = startIndex.coerceIn(0, routePoints.lastIndex)
        private set
    var isDeviated = false
        private set
    var isArrived = false
        private set

    /**
     * @param headingDegrees current compass heading (0-360, 0=north), or
     *   null if unavailable/too unreliable to use (see CompassManager).
     */
    fun update(currentLat: Double, currentLon: Double, gpsAccuracyMeters: Float, headingDegrees: Double?): NavigationEvent {
        if (isArrived) return NavigationEvent.Arrived
        if (gpsAccuracyMeters > thresholds.minAcceptableGpsAccuracyMeters) return NavigationEvent.PoorGpsAccuracy

        var target = routePoints[currentTargetIndex]
        var distanceToTarget = GeoMath.distanceMeters(currentLat, currentLon, target.latitude, target.longitude)

        // Advance through any waypoints already within proximity in one
        // update -- handles a fast walker or a sparse update rate skipping
        // past several closely-spaced recorded points at once, instead of
        // only ever advancing a single point per call.
        while (distanceToTarget <= thresholds.routePointProximityMeters && currentTargetIndex < routePoints.lastIndex) {
            currentTargetIndex++
            target = routePoints[currentTargetIndex]
            distanceToTarget = GeoMath.distanceMeters(currentLat, currentLon, target.latitude, target.longitude)
        }

        val isLastPoint = currentTargetIndex == routePoints.lastIndex
        if (isLastPoint && distanceToTarget <= thresholds.destinationArrivalRadiusMeters) {
            isArrived = true
            return NavigationEvent.Arrived
        }

        val nearestDistance = routePoints.minOf { GeoMath.distanceMeters(currentLat, currentLon, it.latitude, it.longitude) }
        val nowDeviated = nearestDistance > thresholds.routeDeviationMeters
        if (nowDeviated != isDeviated) {
            isDeviated = nowDeviated
            return if (nowDeviated) NavigationEvent.Deviated else NavigationEvent.BackOnRoute
        }

        if (isLastPoint && distanceToTarget <= thresholds.destinationArrivalRadiusMeters * APPROACHING_MULTIPLIER) {
            return NavigationEvent.ApproachingDestination
        }

        if (headingDegrees == null) {
            // No reliable heading -- still useful to report distance, just without a turn direction.
            return NavigationEvent.Guidance(TurnInstruction.STRAIGHT, distanceToTarget)
        }

        val targetBearing = GeoMath.bearingDegrees(currentLat, currentLon, target.latitude, target.longitude)
        val diff = GeoMath.angularDifference(headingDegrees, targetBearing)
        return NavigationEvent.Guidance(classifyTurn(diff), distanceToTarget)
    }

    private fun classifyTurn(diffDegrees: Double): TurnInstruction {
        val magnitude = abs(diffDegrees)
        return when {
            magnitude < 15 -> TurnInstruction.STRAIGHT
            magnitude < 45 -> if (diffDegrees > 0) TurnInstruction.SLIGHT_RIGHT else TurnInstruction.SLIGHT_LEFT
            magnitude < 120 -> if (diffDegrees > 0) TurnInstruction.RIGHT else TurnInstruction.LEFT
            else -> if (diffDegrees > 0) TurnInstruction.SHARP_RIGHT else TurnInstruction.SHARP_LEFT
        }
    }

    companion object {
        private const val APPROACHING_MULTIPLIER = 3.0

        /**
         * Finds the [routePoints] index closest to (currentLat, currentLon)
         * -- used as [startIndex] so navigation begins from wherever the
         * user is actually standing along the recorded route, instead of
         * always assuming they're at the route's first recorded point
         * (which may be far away if they start navigating mid-route, or the
         * recorded start point drifted slightly from the real one).
         */
        fun nearestPointIndex(routePoints: List<NavPoint>, currentLat: Double, currentLon: Double): Int {
            require(routePoints.isNotEmpty()) { "routePoints must not be empty" }
            return routePoints.indices.minBy { index ->
                GeoMath.distanceMeters(currentLat, currentLon, routePoints[index].latitude, routePoints[index].longitude)
            }
        }
    }
}
