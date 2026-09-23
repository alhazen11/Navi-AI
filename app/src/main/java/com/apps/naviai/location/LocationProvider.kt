package com.apps.naviai.location

import kotlinx.coroutines.flow.Flow

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val timestamp: Long
)

/** Abstraction over FusedLocationProviderClient so recording/navigation logic doesn't depend on Play Services types directly. */
interface LocationProvider {
    /**
     * Emits location updates at roughly [intervalMs]. Must hold
     * ACCESS_FINE_LOCATION; caller is responsible for the permission
     * check. Location updates stop automatically when the collecting
     * coroutine is cancelled.
     */
    fun locationUpdates(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<LocationFix>

    /** One-shot last known location, or null if unavailable. */
    suspend fun getLastLocation(): LocationFix?

    companion object {
        const val DEFAULT_INTERVAL_MS = 1000L
    }
}
