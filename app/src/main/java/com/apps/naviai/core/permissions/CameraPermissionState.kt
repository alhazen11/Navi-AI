package com.apps.naviai.core.permissions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Minimal single-permission holder: current grant state plus a request launcher. */
class SinglePermissionState internal constructor(
    private val permission: String,
    initiallyGranted: Boolean,
    private val launcher: androidx.activity.result.ActivityResultLauncher<String>
) {
    var isGranted by mutableStateOf(initiallyGranted)
        internal set

    fun request() = launcher.launch(permission)
}

@Composable
fun rememberPermissionState(permission: String): SinglePermissionState {
    val context = LocalContext.current
    val initiallyGranted = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED

    val state = remember(permission) { mutableStateOf<SinglePermissionState?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.value?.isGranted = granted
    }
    return remember(permission) {
        SinglePermissionState(permission, initiallyGranted, launcher).also { state.value = it }
    }
}

@Composable
fun rememberCameraPermissionState(): SinglePermissionState = rememberPermissionState(Manifest.permission.CAMERA)

@Composable
fun rememberMicrophonePermissionState(): SinglePermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

@Composable
fun rememberLocationPermissionState(): SinglePermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
