package com.apps.naviai.ui.screens

import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.settings.LlmConnectionTestState
import com.apps.naviai.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCalibration: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val llmTestState by viewModel.llmTestState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth().padding(horizontal = 20.dp)) {
            item { SectionTitle("Detection") }
            item {
                SliderSetting(
                    label = "Confidence threshold",
                    value = settings.confidenceThreshold,
                    valueRange = 0.1f..0.9f,
                    valueText = "${(settings.confidenceThreshold * 100).toInt()}%",
                    onValueChange = viewModel::setConfidenceThreshold
                )
            }
            item {
                SliderSetting(
                    label = "IoU threshold (NMS)",
                    value = settings.iouThreshold,
                    valueRange = 0.1f..0.9f,
                    valueText = "${(settings.iouThreshold * 100).toInt()}%",
                    onValueChange = viewModel::setIouThreshold
                )
            }
            item {
                Text(
                    "Model input resolution: ${settings.modelInputResolution}×${settings.modelInputResolution} " +
                        "(fixed by the bundled model export)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                SwitchSetting(
                    label = if (state.isVulkanSupported) "Use Vulkan (GPU) acceleration" else "Vulkan acceleration (not supported on this build)",
                    checked = settings.useVulkan && state.isVulkanSupported,
                    enabled = state.isVulkanSupported,
                    onCheckedChange = viewModel::setUseVulkan
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item { SectionTitle("Distance & tracking") }
            item {
                SwitchSetting("Distance estimation", settings.enableDistanceEstimation, onCheckedChange = viewModel::setEnableDistanceEstimation)
            }
            item {
                SwitchSetting("Object tracking", settings.enableTracking, onCheckedChange = viewModel::setEnableTracking)
            }
            item {
                SliderSetting(
                    label = "Risk sensitivity",
                    value = settings.riskSensitivity,
                    valueRange = 0.5f..2.0f,
                    valueText = when {
                        settings.riskSensitivity < 0.85f -> "Relaxed"
                        settings.riskSensitivity > 1.3f -> "Cautious"
                        else -> "Balanced"
                    },
                    onValueChange = viewModel::setRiskSensitivity
                )
            }
            item {
                Button(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(if (state.calibration != null) "Recalibrate distance" else "Calibrate distance")
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item { SectionTitle("Voice announcements") }
            item {
                SwitchSetting("Voice assistance", settings.enableVoiceAssistance, onCheckedChange = viewModel::setEnableVoiceAssistance)
            }
            item {
                LanguageSelector(settings.speechLanguage, viewModel::setSpeechLanguage)
            }
            item {
                SliderSetting(
                    label = "Speech rate",
                    value = settings.speechRate,
                    valueRange = 0.5f..2.0f,
                    valueText = "${"%.1f".format(settings.speechRate)}x",
                    onValueChange = viewModel::setSpeechRate
                )
            }
            item {
                SliderSetting(
                    label = "Announcement interval",
                    value = settings.announcementIntervalMs / 1000f,
                    valueRange = 1f..10f,
                    valueText = "${settings.announcementIntervalMs / 1000f}s",
                    onValueChange = { viewModel.setAnnouncementIntervalMs((it * 1000).toLong()) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item { SectionTitle("Voice commands") }
            item {
                Text(
                    "Voice commands use AssemblyAI's realtime speech-to-text over the internet -- " +
                        "the only part of NAVI AI that leaves the device. Paste your own API key from " +
                        "assemblyai.com below; it's stored only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                AssemblyAiApiKeyField(
                    currentValue = settings.assemblyAiApiKey.orEmpty(),
                    onValueCommitted = viewModel::setAssemblyAiApiKey
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item { SectionTitle("LLM (Ollama / OpenAI-compatible)") }
            item {
                Text(
                    "Point NAVI at any OpenAI-compatible chat API -- a local Ollama server " +
                        "(its built-in /v1 endpoint), LM Studio, OpenRouter, Groq, etc. Not used by " +
                        "any feature yet; this just saves the connection so it's ready when it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                LlmSettingsFields(
                    baseUrl = settings.llmBaseUrl.orEmpty(),
                    apiKey = settings.llmApiKey.orEmpty(),
                    model = settings.llmModel.orEmpty(),
                    onBaseUrlCommitted = viewModel::setLlmBaseUrl,
                    onApiKeyCommitted = viewModel::setLlmApiKey,
                    onModelCommitted = viewModel::setLlmModel,
                    testState = llmTestState,
                    onTestConnection = { viewModel.testLlmConnection(settings.llmBaseUrl.orEmpty(), settings.llmApiKey, settings.llmModel) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
            item { SectionTitle("Camera") }
            item {
                SwitchSetting(
                    label = "Use front camera",
                    checked = settings.cameraLensFacing == CameraSelector.LENS_FACING_FRONT,
                    onCheckedChange = {
                        viewModel.setCameraLensFacing(if (it) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                    }
                )
            }

            item {
                Button(
                    onClick = viewModel::resetSettings,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                ) { Text("Reset all settings to defaults") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label)
            Text(valueText, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            modifier = Modifier.semantics { contentDescription = "$label, $valueText" }
        )
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics { contentDescription = "$label, ${if (checked) "on" else "off"}" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun AssemblyAiApiKeyField(currentValue: String, onValueCommitted: (String) -> Unit) {
    // Local editing buffer: DataStore round-trips through a Flow, so wiring
    // the text field straight to `settings.assemblyAiApiKey` would fight
    // the user's cursor on every keystroke. Only synced back from
    // `currentValue` when it changes for a reason other than our own edits
    // (e.g. settings reset), via the `remember(currentValue)` key.
    var text by remember(currentValue) { mutableStateOf(currentValue) }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueCommitted(it)
        },
        label = { Text("AssemblyAI API key") },
        placeholder = { Text("Paste your key here") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "AssemblyAI API key, ${if (currentValue.isBlank()) "not set" else "set"}" }
    )
}

@Composable
private fun LlmSettingsFields(
    baseUrl: String,
    apiKey: String,
    model: String,
    onBaseUrlCommitted: (String) -> Unit,
    onApiKeyCommitted: (String) -> Unit,
    onModelCommitted: (String) -> Unit,
    testState: LlmConnectionTestState,
    onTestConnection: () -> Unit
) {
    // Same local-buffer-per-field pattern as AssemblyAiApiKeyField, for the
    // same reason: DataStore round-trips through a Flow, so binding straight
    // to the settings value would fight the user's cursor on every keystroke.
    var baseUrlText by remember(baseUrl) { mutableStateOf(baseUrl) }
    var apiKeyText by remember(apiKey) { mutableStateOf(apiKey) }
    var modelText by remember(model) { mutableStateOf(model) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = baseUrlText,
            onValueChange = { baseUrlText = it; onBaseUrlCommitted(it) },
            label = { Text("Base URL") },
            placeholder = { Text("http://<your-ollama-host>:11434/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "LLM base URL, ${if (baseUrl.isBlank()) "not set" else baseUrl}" }
        )
        OutlinedTextField(
            value = modelText,
            onValueChange = { modelText = it; onModelCommitted(it) },
            label = { Text("Model") },
            placeholder = { Text("e.g. llama3.2") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "LLM model, ${if (model.isBlank()) "not set" else model}" }
        )
        OutlinedTextField(
            value = apiKeyText,
            onValueChange = { apiKeyText = it; onApiKeyCommitted(it) },
            label = { Text("API key (optional)") },
            placeholder = { Text("Not needed for most local Ollama setups") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "LLM API key, ${if (apiKey.isBlank()) "not set" else "set"}" }
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            OutlinedButton(onClick = onTestConnection, enabled = testState != LlmConnectionTestState.Testing) {
                Text("Test connection")
            }
            if (testState == LlmConnectionTestState.Testing) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            }
        }

        val (resultText, resultColor) = when (testState) {
            is LlmConnectionTestState.Success -> testState.message to MaterialTheme.colorScheme.primary
            is LlmConnectionTestState.Failure -> testState.message to MaterialTheme.colorScheme.error
            else -> null to MaterialTheme.colorScheme.onSurfaceVariant
        }
        resultText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = resultColor, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun LanguageSelector(current: AnnouncementLanguage, onSelect: (AnnouncementLanguage) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Speech language")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            AnnouncementLanguage.entries.forEach { language ->
                val selected = language == current
                Button(
                    onClick = { onSelect(language) },
                    modifier = Modifier.semantics {
                        contentDescription = "${language.name}${if (selected) ", selected" else ""}"
                    }
                ) {
                    Text(if (language == AnnouncementLanguage.INDONESIAN) "Indonesian" else "English")
                }
            }
        }
    }
}
