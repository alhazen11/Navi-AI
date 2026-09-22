package com.apps.naviai.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.data.calibration.CalibrationData
import com.apps.naviai.data.calibration.CalibrationRepository
import com.apps.naviai.detection.detector.ObjectDetector
import com.apps.naviai.domain.model.AppSettings
import com.apps.naviai.llm.LlmClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val calibration: CalibrationData? = null,
    val isVulkanSupported: Boolean = false
)

sealed interface LlmConnectionTestState {
    data object Idle : LlmConnectionTestState
    data object Testing : LlmConnectionTestState
    data class Success(val message: String) : LlmConnectionTestState
    data class Failure(val message: String) : LlmConnectionTestState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val calibrationRepository: CalibrationRepository,
    private val objectDetector: ObjectDetector,
    private val llmClient: LlmClient
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        calibrationRepository.calibration
    ) { settings, calibration ->
        SettingsUiState(settings, calibration, objectDetector.isVulkanSupported())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _llmTestState = MutableStateFlow<LlmConnectionTestState>(LlmConnectionTestState.Idle)
    val llmTestState: StateFlow<LlmConnectionTestState> = _llmTestState.asStateFlow()

    fun setConfidenceThreshold(value: Float) = viewModelScope.launch { settingsRepository.setConfidenceThreshold(value) }
    fun setIouThreshold(value: Float) = viewModelScope.launch { settingsRepository.setIouThreshold(value) }
    fun setUseVulkan(value: Boolean) = viewModelScope.launch { settingsRepository.setUseVulkan(value) }
    fun setEnableDistanceEstimation(value: Boolean) = viewModelScope.launch { settingsRepository.setEnableDistanceEstimation(value) }
    fun setEnableTracking(value: Boolean) = viewModelScope.launch { settingsRepository.setEnableTracking(value) }
    fun setEnableVoiceAssistance(value: Boolean) = viewModelScope.launch { settingsRepository.setEnableVoiceAssistance(value) }
    fun setSpeechLanguage(value: AnnouncementLanguage) = viewModelScope.launch { settingsRepository.setSpeechLanguage(value) }
    fun setSpeechRate(value: Float) = viewModelScope.launch { settingsRepository.setSpeechRate(value) }
    fun setAnnouncementIntervalMs(value: Long) = viewModelScope.launch { settingsRepository.setAnnouncementIntervalMs(value) }
    fun setRiskSensitivity(value: Float) = viewModelScope.launch { settingsRepository.setRiskSensitivity(value) }
    fun setCameraLensFacing(value: Int) = viewModelScope.launch { settingsRepository.setCameraLensFacing(value) }
    fun setAssemblyAiApiKey(value: String) = viewModelScope.launch { settingsRepository.setAssemblyAiApiKey(value) }
    fun setLlmBaseUrl(value: String) = viewModelScope.launch { settingsRepository.setLlmBaseUrl(value) }
    fun setLlmApiKey(value: String) = viewModelScope.launch { settingsRepository.setLlmApiKey(value) }
    fun setLlmModel(value: String) = viewModelScope.launch { settingsRepository.setLlmModel(value) }

    /** Pings the configured LLM endpoint's /models listing to verify it's reachable -- doesn't persist anything. */
    fun testLlmConnection(baseUrl: String, apiKey: String?, model: String?) {
        _llmTestState.value = LlmConnectionTestState.Testing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { llmClient.testConnection(baseUrl, apiKey, model) }
            _llmTestState.value = when (result) {
                is LlmClient.TestResult.Success -> LlmConnectionTestState.Success(result.message)
                is LlmClient.TestResult.Failure -> LlmConnectionTestState.Failure(result.message)
            }
        }
    }

    fun resetSettings() = viewModelScope.launch { settingsRepository.resetToDefaults() }
    fun resetCalibration() = viewModelScope.launch { calibrationRepository.reset() }
}
