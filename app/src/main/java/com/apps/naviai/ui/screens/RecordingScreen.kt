package com.apps.naviai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.core.permissions.rememberLocationPermissionState
import com.apps.naviai.recording.RecordingStatus
import com.apps.naviai.recording.RecordingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(onBack: () -> Unit, viewModel: RecordingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val locationPermission = rememberLocationPermissionState()

    LaunchedEffect(Unit) {
        if (!locationPermission.isGranted) locationPermission.request()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Route") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!locationPermission.isGranted) {
                Text(
                    "NAVI needs location access to record a route.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(onClick = { locationPermission.request() }) { Text("Grant location permission") }
                return@Scaffold
            }

            when (val status = uiState.status) {
                is RecordingStatus.Recording -> {
                    Text("Recording…", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(16.dp))
                    Text("Elapsed: ${formatElapsed(status.elapsedMs)}", style = MaterialTheme.typography.bodyLarge)
                    Text("Points recorded: ${status.pointCount}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "GPS accuracy: ${status.currentAccuracyMeters?.let { "±${it.toInt()}m" } ?: "acquiring…"}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.size(24.dp))
                    Button(
                        onClick = { viewModel.stopRecordingManually() },
                        modifier = Modifier.semantics { contentDescription = "Stop recording" }
                    ) { Text("Stop recording") }
                }
                RecordingStatus.Idle -> {
                    Text(
                        "Say \"NAVI, mulai merekam jalan\" or tap below to start recording a route.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(
                        onClick = { viewModel.startRecordingManually() },
                        modifier = Modifier.semantics { contentDescription = "Start recording" }
                    ) { Text("Start recording") }
                }
            }

            if (uiState.awaitingRouteName) {
                Spacer(Modifier.size(24.dp))
                RouteNameDialogFallback(
                    onSubmit = { viewModel.submitRouteNameManually(it) },
                    onCancel = { viewModel.cancelPendingRouteName() }
                )
            }

            uiState.lastSavedRouteName?.let {
                Spacer(Modifier.size(16.dp))
                Text("Saved as \"$it\".", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RouteNameDialogFallback(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "NAVI is listening for the route's name -- or type it below.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Route name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (name.isNotBlank()) onSubmit(name) }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
