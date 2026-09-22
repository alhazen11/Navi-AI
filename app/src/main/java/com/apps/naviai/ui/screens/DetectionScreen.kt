package com.apps.naviai.ui.screens

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.camera.CameraManager
import com.apps.naviai.core.permissions.rememberCameraPermissionState
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.ui.components.DetectionInfoCard
import com.apps.naviai.ui.components.DetectionOverlay
import com.apps.naviai.ui.components.PerformanceStatsOverlay
import com.apps.naviai.ui.components.RiskIndicator
import com.apps.naviai.ui.components.VoiceCommandPanel
import com.apps.naviai.ui.viewmodel.DetectionViewModel

@Composable
fun DetectionScreen(
    onOpenSettings: () -> Unit,
    onOpenCalibration: () -> Unit,
    viewModel: DetectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val permission = rememberCameraPermissionState()

    val cameraManager = remember { CameraManager(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    val lensFacing = uiState.settings.cameraLensFacing
    val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT

    DisposableEffect(Unit) {
        viewModel.setRunning(true)
        onDispose {
            viewModel.setRunning(false)
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(previewView, lensFacing, permission.isGranted) {
        val view = previewView ?: return@LaunchedEffect
        if (!permission.isGranted) return@LaunchedEffect

        val provider = cameraManager.awaitProvider()
        if (!cameraManager.hasLensFacing(provider, lensFacing)) return@LaunchedEffect

        val preview = cameraManager.buildPreviewUseCase().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = cameraManager.buildAnalysisUseCase()
        analysis.setAnalyzer(analysisExecutor, viewModel.createFrameAnalyzer(mirror))
        cameraManager.bind(lifecycleOwner, provider, preview, analysis, lensFacing)
    }

    val hasCritical = uiState.trackedObjects.any { it.riskLevel == RiskLevel.CRITICAL }
    LaunchedEffect(hasCritical) {
        if (hasCritical) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permission.isGranted) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { previewView = this } },
                modifier = Modifier.fillMaxSize()
            )
            DetectionOverlay(
                trackedObjects = uiState.trackedObjects,
                imageWidth = uiState.frameUprightWidth,
                imageHeight = uiState.frameUprightHeight,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Camera permission is required.", color = Color.White)
            }
        }

        // Top bar: risk indicator + settings/calibration access.
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val highestRisk = uiState.trackedObjects.maxByOrNull { it.riskLevel.priority }?.riskLevel ?: RiskLevel.SAFE
            RiskIndicator(riskLevel = highestRisk)

            Row {
                IconButton(
                    onClick = onOpenCalibration,
                    modifier = Modifier.semantics { contentDescription = "Calibrate distance estimation" }
                ) {
                    Icon(Icons.Filled.Straighten, contentDescription = null, tint = Color.White)
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.semantics { contentDescription = "Open settings" }
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White)
                }
            }
        }

        PerformanceStatsOverlay(
            stats = uiState.performanceStats,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 72.dp, start = 16.dp)
        )

        // Bottom stack: voice command panel, controls, then the detail card --
        // grouped in one Column so they lay out top-to-bottom without manual
        // padding math between independently-aligned elements.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VoiceCommandPanel(modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.size(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconToggleButton(
                    checked = uiState.settings.enableVoiceAssistance,
                    onCheckedChange = { viewModel.setVoiceEnabled(it) },
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = if (uiState.settings.enableVoiceAssistance) "Voice announcements on" else "Voice announcements off"
                    }
                ) {
                    Icon(if (uiState.settings.enableVoiceAssistance) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, contentDescription = null)
                }

                FilledIconToggleButton(
                    checked = uiState.isRunning,
                    onCheckedChange = { viewModel.setRunning(it) },
                    modifier = Modifier.size(72.dp).semantics {
                        contentDescription = if (uiState.isRunning) "Stop detection" else "Start detection"
                    }
                ) {
                    Icon(if (uiState.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                }

                IconButton(
                    onClick = { viewModel.toggleCamera() },
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Switch camera" }
                ) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = null, tint = Color.White)
                }
            }

            uiState.trackedObjects.maxByOrNull { it.riskLevel.priority }?.let { mostRelevant ->
                Spacer(modifier = Modifier.size(16.dp))
                DetectionInfoCard(tracked = mostRelevant, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
