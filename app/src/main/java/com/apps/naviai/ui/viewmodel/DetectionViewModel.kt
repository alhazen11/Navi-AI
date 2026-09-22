package com.apps.naviai.ui.viewmodel

import androidx.camera.core.CameraSelector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.audio.AnnouncementManager
import com.apps.naviai.audio.TextToSpeechManager
import com.apps.naviai.audio.TtsAvailability
import com.apps.naviai.camera.FrameAnalyzer
import com.apps.naviai.core.common.InferenceDispatcher
import com.apps.naviai.core.performance.PerformanceMonitor
import com.apps.naviai.core.performance.PerformanceStats
import com.apps.naviai.data.calibration.CalibrationData
import com.apps.naviai.data.calibration.CalibrationRepository
import com.apps.naviai.detection.detector.DetectorState
import com.apps.naviai.detection.detector.FrameInput
import com.apps.naviai.detection.detector.ObjectDetector
import com.apps.naviai.detection.distance.DistanceEstimator
import com.apps.naviai.detection.preprocessing.ImagePreprocessor
import com.apps.naviai.detection.risk.RiskAssessmentEngine
import com.apps.naviai.detection.risk.RiskSensitivity
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.ObjectTracker
import com.apps.naviai.detection.tracking.TrackedObject
import com.apps.naviai.domain.model.AppSettings
import com.apps.naviai.domain.model.CameraParameters
import com.apps.naviai.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetectionUiState(
    val detectorState: DetectorState = DetectorState.Uninitialized,
    val trackedObjects: List<TrackedObject> = emptyList(),
    val performanceStats: PerformanceStats = PerformanceStats(),
    val isVulkanSupported: Boolean = false,
    val ttsAvailability: TtsAvailability = TtsAvailability.UNKNOWN,
    val settings: AppSettings = AppSettings(),
    val isRunning: Boolean = false,
    /** Upright (post-rotation) display-space dimensions of the most recently analyzed frame,
     *  used by the overlay to scale bounding boxes onto the preview Canvas. */
    val frameUprightWidth: Int = 0,
    val frameUprightHeight: Int = 0
) {
    val lastErrorMessage: String?
        get() = (detectorState as? DetectorState.Error)?.message
}

@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val objectDetector: ObjectDetector,
    private val distanceEstimator: DistanceEstimator,
    private val objectTracker: ObjectTracker,
    private val riskAssessmentEngine: RiskAssessmentEngine,
    private val announcementManager: AnnouncementManager,
    private val ttsManager: TextToSpeechManager,
    private val settingsRepository: SettingsRepository,
    private val calibrationRepository: CalibrationRepository,
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val performanceMonitor = PerformanceMonitor()

    private val _uiState = MutableStateFlow(DetectionUiState())
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    @Volatile private var currentSettings: AppSettings = AppSettings()
    @Volatile private var currentCalibration: CalibrationData? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                _uiState.update { it.copy(settings = settings) }
                riskAssessmentEngine.sensitivity = RiskSensitivity(cautionMultiplier = settings.riskSensitivity)
                announcementManager.normalCooldownMs = settings.announcementIntervalMs
                ttsManager.setLanguage(settings.speechLanguage)
                ttsManager.setSpeechRate(settings.speechRate)
            }
        }
        viewModelScope.launch {
            calibrationRepository.calibration.collect { currentCalibration = it }
        }
        viewModelScope.launch {
            ttsManager.availability.collect { availability ->
                _uiState.update { it.copy(ttsAvailability = availability) }
            }
        }
        // objectDetector is an app-wide singleton, so it may already be Ready
        // (e.g. the splash screen initialized it) or Uninitialized (this
        // screen was reached directly, or a prior screen's ViewModel was
        // torn down). Either way, this instance must not assume Splash ran
        // first -- sync current state and self-initialize if needed.
        _uiState.update { it.copy(detectorState = objectDetector.state) }
        if (objectDetector.state !is DetectorState.Ready) {
            viewModelScope.launch { initializeDetector() }
        }
    }

    suspend fun initializeDetector() {
        _uiState.update { it.copy(detectorState = DetectorState.Initializing) }
        objectDetector.initialize(currentSettings.useVulkan)
        _uiState.update {
            it.copy(
                detectorState = objectDetector.state,
                isVulkanSupported = objectDetector.isVulkanSupported()
            )
        }
    }

    fun createFrameAnalyzer(mirror: Boolean): FrameAnalyzer =
        FrameAnalyzer(viewModelScope, inferenceDispatcher, mirror) { frame -> onFrame(frame) }

    fun setRunning(running: Boolean) {
        _uiState.update { it.copy(isRunning = running) }
        if (!running) {
            objectTracker.reset()
            performanceMonitor.reset()
            _uiState.update { it.copy(trackedObjects = emptyList()) }
        }
    }

    fun toggleCamera() {
        val next = if (currentSettings.cameraLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        viewModelScope.launch { settingsRepository.setCameraLensFacing(next) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableVoiceAssistance(enabled) }
        if (!enabled) ttsManager.stop()
    }

    private suspend fun onFrame(frame: FrameInput) {
        if (!_uiState.value.isRunning) return
        val pipelineStartNanos = System.nanoTime()

        val result = objectDetector.detect(frame, currentSettings.confidenceThreshold, currentSettings.iouThreshold)

        val (uprightWidth, uprightHeight) = ImagePreprocessor.uprightSize(frame.width, frame.height, frame.rotationDegrees)
        val cameraParameters = buildCameraParameters(uprightWidth, uprightHeight)

        val observations = result.detections.map { detection ->
            val distance = if (currentSettings.enableDistanceEstimation) {
                distanceEstimator.estimateDistance(detection, cameraParameters)
            } else {
                null
            }
            detection to distance
        }

        val nowMs = System.currentTimeMillis()
        val tracked = if (currentSettings.enableTracking) {
            objectTracker.update(observations, nowMs)
        } else {
            observations.mapIndexed { index, (detection, distance) ->
                TrackedObject(
                    trackingId = index,
                    detection = detection,
                    estimatedDistanceMeters = distance?.distanceMeters,
                    distanceConfidence = distance?.confidence ?: 0f,
                    movementDirection = MovementDirection.UNKNOWN,
                    riskLevel = com.apps.naviai.detection.risk.RiskLevel.SAFE,
                    framesTracked = 1,
                    lastAnnouncedAtMs = null
                )
            }
        }

        val withRisk = tracked.map { it.copy(riskLevel = riskAssessmentEngine.assess(it, uprightWidth)) }

        if (currentSettings.enableVoiceAssistance && currentSettings.enableTracking) {
            announcementManager.evaluate(withRisk, currentSettings.speechLanguage, nowMs) { trackingId, atMs ->
                objectTracker.markAnnounced(trackingId, atMs)
            }
        }

        val pipelineMs = (System.nanoTime() - pipelineStartNanos) / 1_000_000.0
        val stats = performanceMonitor.recordFrame(nowMs, result.inferenceTimeMs, pipelineMs, withRisk.size)

        _uiState.update {
            it.copy(
                trackedObjects = withRisk,
                performanceStats = stats,
                frameUprightWidth = uprightWidth,
                frameUprightHeight = uprightHeight
            )
        }
    }

    private fun buildCameraParameters(frameWidth: Int, frameHeight: Int): CameraParameters {
        val default = CameraParameters.defaultFor(frameWidth, frameHeight)
        val calibration = currentCalibration ?: return default
        return default.copy(focalLengthPixels = calibration.focalLengthPixels, isCalibrated = true)
    }

    // Deliberately does NOT release objectDetector or shut down ttsManager here.
    // Both are Hilt @Singleton instances shared across every screen (Splash,
    // Detection, Calibration each get their own DetectionViewModel/
    // CalibrationViewModel scoped to their own NavBackStackEntry). Releasing
    // an app-wide singleton from one screen's onCleared() would tear it down
    // for every other screen still using it -- e.g. Splash's ViewModel used
    // to release the native detector the instant it was popped off the back
    // stack, permanently breaking detection on the Detection screen that
    // navigated in right after it. Native/TTS resources are left to the OS
    // to reclaim on process death, which is the standard pattern for
    // process-wide native singletons on Android.
}
