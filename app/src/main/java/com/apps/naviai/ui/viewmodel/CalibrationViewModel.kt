package com.apps.naviai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.camera.FrameAnalyzer
import com.apps.naviai.core.common.InferenceDispatcher
import com.apps.naviai.data.calibration.CalibrationData
import com.apps.naviai.data.calibration.CalibrationRepository
import com.apps.naviai.data.calibration.CalibrationResult
import com.apps.naviai.detection.detector.DetectorState
import com.apps.naviai.detection.detector.FrameInput
import com.apps.naviai.detection.detector.ObjectDetector
import com.apps.naviai.detection.distance.ObjectDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CalibrationUiState(
    val referenceLabel: String = "person",
    val referenceDistanceInput: String = "1.0",
    val latestBoxHeightPixels: Float? = null,
    val isCapturing: Boolean = false,
    val result: CalibrationResult? = null,
    val existingCalibration: CalibrationData? = null,
    val detectorState: DetectorState = DetectorState.Uninitialized
) {
    val availableReferenceLabels: List<String> get() = ObjectDimensions.supportedLabels.sorted()
    val canSave: Boolean get() = latestBoxHeightPixels != null && referenceDistanceInput.toFloatOrNull() != null
}

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val objectDetector: ObjectDetector,
    private val calibrationRepository: CalibrationRepository,
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            calibrationRepository.calibration.collect { calibration ->
                _uiState.update { it.copy(existingCalibration = calibration) }
            }
        }
        viewModelScope.launch {
            if (objectDetector.state !is DetectorState.Ready) {
                objectDetector.initialize(useVulkan = false)
            }
            _uiState.update { it.copy(detectorState = objectDetector.state) }
        }
    }

    fun createFrameAnalyzer(mirror: Boolean): FrameAnalyzer =
        FrameAnalyzer(viewModelScope, inferenceDispatcher, mirror) { frame -> onFrame(frame) }

    fun setReferenceLabel(label: String) {
        _uiState.update { it.copy(referenceLabel = label, latestBoxHeightPixels = null, result = null) }
    }

    fun setReferenceDistanceInput(text: String) {
        _uiState.update { it.copy(referenceDistanceInput = text) }
    }

    fun setCapturing(capturing: Boolean) {
        _uiState.update { it.copy(isCapturing = capturing, latestBoxHeightPixels = null, result = null) }
    }

    private suspend fun onFrame(frame: FrameInput) {
        if (!_uiState.value.isCapturing) return
        val result = objectDetector.detect(frame, confidenceThreshold = 0.4f, iouThreshold = 0.45f)
        val target = _uiState.value.referenceLabel
        val match = result.detections.filter { it.label == target }.maxByOrNull { it.confidence } ?: return
        _uiState.update { it.copy(latestBoxHeightPixels = match.boundingBox.height()) }
    }

    fun saveCalibration() {
        val state = _uiState.value
        val distance = state.referenceDistanceInput.toFloatOrNull()
        val boxHeight = state.latestBoxHeightPixels
        if (distance == null || boxHeight == null) {
            _uiState.update {
                it.copy(result = CalibrationResult.Failure("Enter a valid distance and capture the reference object first."))
            }
            return
        }
        viewModelScope.launch {
            val result = calibrationRepository.calibrate(state.referenceLabel, distance, boxHeight)
            _uiState.update { it.copy(result = result, isCapturing = false) }
        }
    }

    fun resetCalibration() {
        viewModelScope.launch {
            calibrationRepository.reset()
            _uiState.update { it.copy(latestBoxHeightPixels = null, result = null) }
        }
    }
}
