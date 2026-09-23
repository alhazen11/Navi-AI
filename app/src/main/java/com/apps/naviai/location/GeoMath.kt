package com.apps.naviai.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure great-circle distance/bearing math (Haversine). Deliberately doesn't
 * use android.location.Location.distanceBetween()/bearingTo() -- those are
 * Android framework methods that aren't safely callable in a plain JVM unit
 * test, so this reimplements the same math directly to keep it
 * Robolectric-free, same rationale as [com.apps.naviai.scene.HazardTrigger].
 */
object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance between two lat/lon points, in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /** Initial compass bearing (0-360, 0 = true north, clockwise) from point 1 to point 2. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360) % 360
    }

    /**
     * Signed angular difference to turn from [fromDegrees] to [toDegrees],
     * normalized to (-180, 180]. Positive = turn right/clockwise, negative
     * = turn left/counter-clockwise.
     */
    fun angularDifference(fromDegrees: Double, toDegrees: Double): Double {
        var diff = (toDegrees - fromDegrees) % 360
        if (diff > 180) diff -= 360
        if (diff <= -180) diff += 360
        return diff
    }
}
