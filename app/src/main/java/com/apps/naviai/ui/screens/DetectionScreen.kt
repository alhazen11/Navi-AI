package com.apps.naviai.ui.screens

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import com.apps.naviai.camera.CameraManager
import com.apps.naviai.core.permissions.rememberCameraPermissionState
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.ui.components.DetectionInfoCard
import com.apps.naviai.ui.components.DetectionOverlay
import com.apps.naviai.ui.components.PerformanceStatsOverlay
import com.apps.naviai.ui.components.RiskIndicator
import com.apps.naviai.ui.components.VoiceCommandPanel
import com.apps.naviai.ui.viewmodel.DetectionNavigationEvent
import com.apps.naviai.ui.viewmodel.DetectionViewModel
import com.apps.naviai.ui.viewmodel.HazardWarningStatus
import com.apps.naviai.ui.viewmodel.ObjectSearchStatus
import com.apps.naviai.ui.viewmodel.SceneDescriptionStatus
import com.apps.naviai.ui.viewmodel.TextReadingStatus

/**
 * The app's single screen for every feature -- object detection plus every
 * voice-triggered capability (Scene Understanding, Text Reading, Hazard
 * Awareness, Object Search, Route Recording/Navigation, Conversation
 * Memory). There used to be a separate HomeScreen/HomeViewModel hub;
 * removed so everything lives in one place, with [onOpenRecording]/
 * [onOpenSavedRoutes]/[onOpenMemory]/[onStartNavigation] reached either via
 * the top-bar buttons or the matching voice command (see
 * [DetectionViewModel.handleVoiceCommand], via [DetectionViewModel.navigationEvents]).
 */
@Composable
fun DetectionScreen(
    onOpenSettings: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenSavedRoutes: () -> Unit,
    onOpenMemory: () -> Unit,
    onStartNavigation: (String) -> Unit,
    viewModel: DetectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val permission = rememberCameraPermissionState()

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                DetectionNavigationEvent.GoToRecording -> onOpenRecording()
                is DetectionNavigationEvent.GoToNavigation -> onStartNavigation(event.routeName)
            }
        }
    }

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
                    onClick = onOpenRecording,
                    modifier = Modifier.semantics { contentDescription = "Record a route" }
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color.White)
                }
                IconButton(
                    onClick = onOpenSavedRoutes,
                    modifier = Modifier.semantics { contentDescription = "Saved routes" }
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = Color.White)
                }
                IconButton(
                    onClick = onOpenMemory,
                    modifier = Modifier.semantics { contentDescription = "Memory" }
                ) {
                    Icon(Icons.Filled.Psychology, contentDescription = null, tint = Color.White)
                }
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

                IconButton(
                    onClick = { viewModel.describeSurroundingsManually() },
                    enabled = uiState.sceneDescriptionStatus != SceneDescriptionStatus.Processing,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Describe surroundings" }
                ) {
                    Icon(Icons.Filled.RemoveRedEye, contentDescription = null, tint = Color.White)
                }

                IconButton(
                    onClick = { viewModel.readTextManually() },
                    enabled = uiState.textReadingStatus != TextReadingStatus.Processing,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Read text aloud" }
                ) {
                    Icon(Icons.Filled.TextFields, contentDescription = null, tint = Color.White)
                }
            }

            SceneDescriptionCard(uiState.sceneDescriptionStatus, modifier = Modifier.fillMaxWidth())
            TextReadingCard(uiState.textReadingStatus, modifier = Modifier.fillMaxWidth())
            HazardWarningCard(uiState.hazardWarningStatus, modifier = Modifier.fillMaxWidth())
            ObjectSearchCard(uiState.objectSearchStatus, modifier = Modifier.fillMaxWidth())

            uiState.trackedObjects.maxByOrNull { it.riskLevel.priority }?.let { mostRelevant ->
                Spacer(modifier = Modifier.size(16.dp))
                DetectionInfoCard(tracked = mostRelevant, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Auto-hides a result card [AUTO_HIDE_AFTER_MS] after it reaches a
 * "terminal" state (a result/error is showing, not still loading) --
 * without this, a Scene/Text/Object result just sits on screen forever
 * until the next command replaces it, cluttering the view. Keyed on
 * [status] itself: a genuinely new status (even a repeat of the same
 * status object) restarts both the "visible" reset and the hide timer,
 * so a fresh result is never hidden by a timer left over from a previous
 * one. Doesn't touch the underlying ViewModel state -- this only affects
 * whether the card is drawn, not [status] itself.
 */
@Composable
private fun rememberAutoHideVisible(status: Any, isTerminal: Boolean): Boolean {
    var visible by remember(status) { mutableStateOf(true) }
    LaunchedEffect(status) {
        if (isTerminal) {
            delay(AUTO_HIDE_AFTER_MS)
            visible = false
        }
    }
    return visible
}

private const val AUTO_HIDE_AFTER_MS = 15_000L

@Composable
private fun SceneDescriptionCard(status: SceneDescriptionStatus, modifier: Modifier = Modifier) {
    val isTerminal = status is SceneDescriptionStatus.Success || status is SceneDescriptionStatus.Failure
    if (status == SceneDescriptionStatus.Idle || !rememberAutoHideVisible(status, isTerminal)) return

    Spacer(modifier = Modifier.size(16.dp))
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(16.dp)
    ) {
        when (status) {
            is SceneDescriptionStatus.Processing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(12.dp))
                Text("Analyzing surroundings…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SceneDescriptionStatus.Success -> Text(
                status.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            is SceneDescriptionStatus.Failure -> Text(
                status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            SceneDescriptionStatus.Idle -> Unit
        }
    }
}

@Composable
private fun TextReadingCard(status: TextReadingStatus, modifier: Modifier = Modifier) {
    val isTerminal = status is TextReadingStatus.Success || status is TextReadingStatus.Failure
    if (status == TextReadingStatus.Idle || !rememberAutoHideVisible(status, isTerminal)) return

    Spacer(modifier = Modifier.size(16.dp))
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(16.dp)
    ) {
        when (status) {
            is TextReadingStatus.Processing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(12.dp))
                Text("Reading text…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is TextReadingStatus.Success -> Text(
                status.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            is TextReadingStatus.Failure -> Text(
                status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextReadingStatus.Idle -> Unit
        }
    }
}

@Composable
private fun HazardWarningCard(status: HazardWarningStatus, modifier: Modifier = Modifier) {
    // Not shown on Failure: this feature triggers automatically (not from a
    // user action), so a failed background check isn't surfaced as a visible
    // error -- that would alarm/distract the user over something they never
    // asked for. It's silently retried on the next qualifying frame instead
    // (already logged for debugging in DetectionViewModel.checkForHazard).
    if (status == HazardWarningStatus.Idle || status is HazardWarningStatus.Failure) return
    val isTerminal = status is HazardWarningStatus.Success
    if (!rememberAutoHideVisible(status, isTerminal)) return

    Spacer(modifier = Modifier.size(16.dp))
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(16.dp)
    ) {
        when (status) {
            is HazardWarningStatus.Processing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(12.dp))
                Text("Checking a possible obstacle…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is HazardWarningStatus.Success -> Text(
                status.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            else -> Unit
        }
    }
}

@Composable
private fun ObjectSearchCard(status: ObjectSearchStatus, modifier: Modifier = Modifier) {
    val isTerminal = status is ObjectSearchStatus.Success || status is ObjectSearchStatus.Failure
    if (status == ObjectSearchStatus.Idle || !rememberAutoHideVisible(status, isTerminal)) return

    Spacer(modifier = Modifier.size(16.dp))
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(16.dp)
    ) {
        when (status) {
            is ObjectSearchStatus.Processing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(12.dp))
                Text("Searching…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ObjectSearchStatus.Success -> Text(
                status.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            is ObjectSearchStatus.Failure -> Text(
                status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            ObjectSearchStatus.Idle -> Unit
        }
    }
}
