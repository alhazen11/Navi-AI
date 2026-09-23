package com.apps.naviai.recording

import android.content.Context
import com.apps.naviai.database.RoutePointEntity
import com.apps.naviai.location.LocationFix
import com.apps.naviai.location.LocationPermission
import com.apps.naviai.location.LocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data class Recording(val elapsedMs: Long, val pointCount: Int, val currentAccuracyMeters: Float?) : RecordingStatus
}

/**
 * Owns the actual GPS-point collection for the Route Recording feature.
 * Singleton (not ViewModel-scoped) so recording keeps running -- and its
 * accumulated points aren't lost -- if the user briefly navigates to
 * another screen mid-recording; [RecordingStatus] observers (e.g.
 * RecordingScreen) just watch [status].
 */
@Singleton
class RouteRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: LocationProvider
) {

    private val _status = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    // Dispatchers.Main.immediate, not a bare SupervisorJob (which defaults
    // to Dispatchers.Default -- a plain thread-pool thread with no
    // Looper.prepare() ever called): FusedLocationProviderClient's
    // requestLocationUpdates() needs a Looper-backed thread to deliver
    // callbacks on, and crashes immediately otherwise. See the comment in
    // FusedLocationProvider.locationUpdates() for the exact crash this fixes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val points = mutableListOf<RoutePointEntity>()
    private var startedAtMs = 0L

    val isRecording: Boolean get() = _status.value is RecordingStatus.Recording

    /**
     * Idempotent while already recording. Also a no-op (stays Idle) if
     * ACCESS_FINE_LOCATION isn't granted -- checked here, not left to
     * callers: a caller-side check was tried and still crashed, because a
     * second call site didn't have one (see [LocationPermission]'s doc).
     * The UI's own permission prompt (RecordingScreen) is what actually
     * gets the user to grant it; this just refuses to touch the
     * permission-gated location API without it instead of crashing.
     */
    fun start() {
        if (isRecording || !LocationPermission.isGranted(context)) return
        points.clear()
        startedAtMs = System.currentTimeMillis()
        _status.value = RecordingStatus.Recording(elapsedMs = 0, pointCount = 0, currentAccuracyMeters = null)

        job = scope.launch {
            locationProvider.locationUpdates(intervalMs = LOCATION_UPDATE_INTERVAL_MS).collect { fix -> onFix(fix) }
        }
    }

    private fun onFix(fix: LocationFix) {
        points += RoutePointEntity(
            routeId = 0, // placeholder -- RouteRepository.saveRoute() assigns the real id at save time
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitude = fix.altitude,
            accuracy = fix.accuracyMeters,
            speed = fix.speedMetersPerSecond,
            bearing = fix.bearingDegrees,
            timestamp = fix.timestamp,
            sequenceNumber = points.size
        )
        _status.value = RecordingStatus.Recording(
            elapsedMs = System.currentTimeMillis() - startedAtMs,
            pointCount = points.size,
            currentAccuracyMeters = fix.accuracyMeters
        )
    }

    /** Stops collecting and returns everything recorded, in chronological order. Returns an empty list if nothing was ever recorded, or if not currently recording. */
    fun stop(): List<RoutePointEntity> {
        job?.cancel()
        job = null
        val collected = points.toList()
        points.clear()
        _status.value = RecordingStatus.Idle
        return collected
    }

    /** Discards an in-progress recording without returning any points -- e.g. the user cancels outright. */
    fun cancel() {
        job?.cancel()
        job = null
        points.clear()
        _status.value = RecordingStatus.Idle
    }

    private companion object {
        const val LOCATION_UPDATE_INTERVAL_MS = 1000L
    }
}
