package com.apps.naviai.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class FusedLocationProvider @Inject constructor(@ApplicationContext context: Context) : LocationProvider {

    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMs: Long): Flow<LocationFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }

        // Looper.getMainLooper(), not null: null tells Play Services to use
        // the CALLING thread's own Looper, and callers of this Flow
        // (RouteRecorder, NavigationController) collect it from their own
        // CoroutineScope(SupervisorJob()) with no dispatcher specified,
        // which defaults to Dispatchers.Default -- a plain thread-pool
        // thread with no Looper.prepare() ever called. That crashes here
        // with "Can't create handler inside thread that has not called
        // Looper.prepare()" the moment requestLocationUpdates is invoked --
        // this is exactly the crash seen right after "mulai merekam jalan".
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    // Manual Task->coroutine bridging (not the kotlinx-coroutines-play-services
    // .await() extension) to avoid pulling in another dependency for one
    // call -- same pattern already used by CameraManager.awaitProvider()
    // for CameraX's ListenableFuture in this codebase.
    @SuppressLint("MissingPermission")
    override suspend fun getLastLocation(): LocationFix? = suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener { location -> if (cont.isActive) cont.resume(location?.toFix()) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        speedMetersPerSecond = if (hasSpeed()) speed else 0f,
        bearingDegrees = if (hasBearing()) bearing else 0f,
        timestamp = time
    )
}
