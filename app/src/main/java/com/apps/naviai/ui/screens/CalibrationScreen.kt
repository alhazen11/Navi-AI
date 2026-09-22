package com.apps.naviai.ui.screens

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.camera.CameraManager
import com.apps.naviai.core.permissions.rememberCameraPermissionState
import com.apps.naviai.data.calibration.CalibrationResult
import com.apps.naviai.ui.viewmodel.CalibrationViewModel
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onBack: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = rememberCameraPermissionState()

    val cameraManager = remember { CameraManager(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(previewView, permission.isGranted) {
        val view = previewView ?: return@LaunchedEffect
        if (!permission.isGranted) {
            permission.request()
            return@LaunchedEffect
        }
        val provider = cameraManager.awaitProvider()
        val preview = cameraManager.buildPreviewUseCase().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = cameraManager.buildAnalysisUseCase()
        analysis.setAnalyzer(analysisExecutor, viewModel.createFrameAnalyzer(mirror = false))
        cameraManager.bind(lifecycleOwner, provider, preview, analysis, CameraSelector.LENS_FACING_BACK)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrate distance") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                if (permission.isGranted) {
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx).apply { previewView = this } },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("Camera permission required.", modifier = Modifier.padding(24.dp))
                }
            }

            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Point the camera at a known reference object, measure its actual distance from " +
                        "the phone, and enter that distance below. This computes an approximate " +
                        "effective focal length used for all future distance estimates -- it does not " +
                        "produce a laboratory-grade measurement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Reference object", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                LazyRow {
                    items(state.availableReferenceLabels) { label ->
                        FilterChip(
                            selected = label == state.referenceLabel,
                            onClick = { viewModel.setReferenceLabel(label) },
                            label = { Text(label.replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = state.referenceDistanceInput,
                    onValueChange = viewModel::setReferenceDistanceInput,
                    label = { Text("Actual distance (meters)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.setCapturing(!state.isCapturing) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.isCapturing) "Capturing… tap to stop" else "Capture reference object")
                    }
                }

                state.latestBoxHeightPixels?.let {
                    Text(
                        "Detected height: ${it.toInt()} px",
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = viewModel::saveCalibration,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                ) {
                    Text("Save calibration")
                }

                OutlinedButton(
                    onClick = viewModel::resetCalibration,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Text("Reset calibration")
                }

                when (val result = state.result) {
                    is CalibrationResult.Success -> Text(
                        "Calibrated: focal length ≈ ${result.calibration.focalLengthPixels.toInt()} px",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    is CalibrationResult.Failure -> Text(
                        result.reason,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    null -> {
                        state.existingCalibration?.let { existing ->
                            Text(
                                "Currently calibrated using a ${existing.referenceLabel} at " +
                                    "${existing.referenceDistanceMeters} m (focal length ≈ ${existing.focalLengthPixels.toInt()} px).",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
