package com.apps.naviai.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Shared permission check for every class that touches
 * FusedLocationProviderClient directly ([RouteRecorder]/[NavigationController]/...).
 * Centralized here rather than left to each *caller* to check first --
 * that was tried (DetectionViewModel checked before calling
 * RouteRecorder.start()) and still crashed, because a second, unguarded
 * call site (RecordingViewModel.startRecording(), reacting to the same
 * stale recognized-command state DetectionViewModel already consumed)
 * called RouteRecorder.start() again without checking. The fix belongs in
 * the class that actually calls the permission-gated API, not scattered
 * across every caller.
 */
object LocationPermission {
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
