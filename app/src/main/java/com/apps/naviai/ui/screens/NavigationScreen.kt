package com.apps.naviai.ui.screens

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.camera.CameraManager
import com.apps.naviai.core.permissions.rememberCameraPermissionState
import com.apps.naviai.core.permissions.rememberLocationPermissionState
import com.apps.naviai.routenav.NavigationUiPhase
import com.apps.naviai.routenav.NavigationViewModel

/**
 * Camera here is preview-only (per Feature 2's spec: "current implementation
 * may focus on camera activation and preview" while keeping architecture
 * ready for obstacle detection). This app already has a full YOLO/NCNN
 * detection pipeline (see detection/, ui/viewmodel/DetectionViewModel.kt) --
 * wiring live obstacle warnings into navigation means reusing that
 * ObjectDetector + FrameAnalyzer pipeline the same way DetectionScreen
 * does, not building a second one; deliberately not done here to avoid
 * binding an ImageAnalysis use case that does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(routeName: String, onBack: () -> Unit, viewModel: NavigationViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val locationPermission = rememberLocationPermissionState()
    val cameraPermission = rememberCameraPermissionState()

    val cameraManager = remember { CameraManager(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(Unit) {
        if (!locationPermission.isGranted) locationPermission.request()
        if (!cameraPermission.isGranted) cameraPermission.request()
    }

    LaunchedEffect(locationPermission.isGranted, routeName) {
        if (locationPermission.isGranted && phase !is NavigationUiPhase.Active) {
            viewModel.startNavigation(routeName)
        }
    }

    // NavigationController.phase goes back to Idle once navigation is
    // genuinely stopped (Stop button, "stop navigasi", or a route-not-found/
    // permission failure) -- NOT just before it's ever started, which is
    // also Idle. Without tracking hasBeenActive, this screen had no way to
    // tell those apart: it kept showing "Starting navigation..." and the
    // Stop button forever after actually stopping, which looks exactly
    // like navigation refusing to stop even though the GPS/TTS loop
    // underneath had genuinely ended. Popping back here is what actually
    // reflects "stopped" to the user instead of leaving them stuck looking
    // at a stale screen.
    var hasBeenActive by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase is NavigationUiPhase.Active) hasBeenActive = true
        if (hasBeenActive && phase is NavigationUiPhase.Idle) onBack()
    }

    LaunchedEffect(previewView, cameraPermission.isGranted) {
        val view = previewView ?: return@LaunchedEffect
        if (!cameraPermission.isGranted) return@LaunchedEffect
        val provider = cameraManager.awaitProvider()
        if (!cameraManager.hasLensFacing(provider, CameraSelector.LENS_FACING_BACK)) return@LaunchedEffect
        val preview = cameraManager.buildPreviewUseCase().also { it.surfaceProvider = view.surfaceProvider }
        cameraManager.bindPreviewOnly(lifecycleOwner, provider, preview, CameraSelector.LENS_FACING_BACK)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermission.isGranted) {
            AndroidView(factory = { ctx -> PreviewView(ctx).apply { previewView = this } }, modifier = Modifier.fillMaxSize())
        }

        TopAppBar(
            title = { Text("Navigating: $routeName", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
            },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NavigationStatusCard(phase, hasBeenActive, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.size(16.dp))
            Button(
                onClick = { viewModel.stopNavigation() },
                modifier = Modifier.semantics { contentDescription = "Stop navigation" }
            ) { Text("Stop navigation") }
        }

        if (!locationPermission.isGranted) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NAVI needs location access to navigate.", color = Color.White)
                    Spacer(modifier = Modifier.size(12.dp))
                    Button(onClick = { locationPermission.request() }) { Text("Grant location permission") }
                }
            }
        }
    }
}

@Composable
private fun NavigationStatusCard(phase: NavigationUiPhase, hasBeenActive: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(16.dp)
    ) {
        when (phase) {
            is NavigationUiPhase.Active -> {
                Text(phase.currentInstruction, style = MaterialTheme.typography.titleMedium)
                phase.distanceToNextPointMeters?.let {
                    Text("Distance to next point: ${it.toInt()}m", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Progress: ${phase.progressIndex + 1}/${phase.totalPoints}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "GPS: ${phase.gpsAccuracyMeters?.let { "±${it.toInt()}m" } ?: "acquiring…"}" +
                        (phase.headingDegrees?.let { "  ·  Heading: ${it.toInt()}°" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (phase.isDeviated) {
                    Text("Off route", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            is NavigationUiPhase.Finished -> Text("Arrived at \"${phase.routeName}\".", style = MaterialTheme.typography.titleMedium)
            is NavigationUiPhase.Failed -> Text(phase.message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            NavigationUiPhase.Idle -> Text(
                if (hasBeenActive) "Navigation stopped." else "Starting navigation…",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
