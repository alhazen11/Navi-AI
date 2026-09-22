package com.apps.naviai.ui.viewmodel

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.audio.AnnouncementManager
import com.apps.naviai.audio.TextToSpeechManager
import com.apps.naviai.audio.TtsAvailability
import com.apps.naviai.audio.VoiceCommandManager
import com.apps.naviai.audio.VoiceCommandStatus
import com.apps.naviai.camera.FrameAnalyzer
import com.apps.naviai.camera.ImageUtils
import com.apps.naviai.core.common.InferenceDispatcher
import com.apps.naviai.core.common.IoDispatcher
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
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.detection.risk.RiskSensitivity
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.ObjectTracker
import com.apps.naviai.detection.tracking.TrackedObject
import com.apps.naviai.domain.model.AppSettings
import com.apps.naviai.domain.model.CameraParameters
import com.apps.naviai.llm.LlmClient
import com.apps.naviai.scene.DetectedObjectSummary
import com.apps.naviai.scene.HazardPromptBuilder
import com.apps.naviai.scene.HazardTrigger
import com.apps.naviai.scene.HorizontalPositionClassifier
import com.apps.naviai.scene.ObjectSearchMatcher
import com.apps.naviai.scene.ObjectSearchPromptBuilder
import com.apps.naviai.scene.SceneContext
import com.apps.naviai.scene.SceneDescriptionMatcher
import com.apps.naviai.scene.ScenePromptBuilder
import com.apps.naviai.scene.TextReadingMatcher
import com.apps.naviai.scene.TextReadingPromptBuilder
import com.apps.naviai.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Scene Understanding request lifecycle -- see [DetectionViewModel.describeSurroundings]. */
sealed interface SceneDescriptionStatus {
    data object Idle : SceneDescriptionStatus
    data object Processing : SceneDescriptionStatus
    data class Success(val text: String) : SceneDescriptionStatus
    data class Failure(val message: String) : SceneDescriptionStatus
}

/** Text Reading (OCR) request lifecycle -- see [DetectionViewModel.readTextAloud]. */
sealed interface TextReadingStatus {
    data object Idle : TextReadingStatus
    data object Processing : TextReadingStatus
    data class Success(val text: String) : TextReadingStatus
    data class Failure(val message: String) : TextReadingStatus
}

/** Hazard Awareness request lifecycle -- see [DetectionViewModel.checkForHazard]. Unlike the other two, this triggers automatically, not from a voice command or button. */
sealed interface HazardWarningStatus {
    data object Idle : HazardWarningStatus
    data object Processing : HazardWarningStatus
    data class Success(val text: String) : HazardWarningStatus
    data class Failure(val message: String) : HazardWarningStatus
}

/** Object/Landmark Search request lifecycle -- see [DetectionViewModel.searchForObject]. */
sealed interface ObjectSearchStatus {
    data object Idle : ObjectSearchStatus
    data object Processing : ObjectSearchStatus
    data class Success(val text: String) : ObjectSearchStatus
    data class Failure(val message: String) : ObjectSearchStatus
}

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
    val frameUprightHeight: Int = 0,
    val sceneDescriptionStatus: SceneDescriptionStatus = SceneDescriptionStatus.Idle,
    val textReadingStatus: TextReadingStatus = TextReadingStatus.Idle,
    val hazardWarningStatus: HazardWarningStatus = HazardWarningStatus.Idle,
    val objectSearchStatus: ObjectSearchStatus = ObjectSearchStatus.Idle
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
    private val voiceCommandManager: VoiceCommandManager,
    private val llmClient: LlmClient,
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val performanceMonitor = PerformanceMonitor()

    private val _uiState = MutableStateFlow(DetectionUiState())
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    @Volatile private var currentSettings: AppSettings = AppSettings()
    @Volatile private var currentCalibration: CalibrationData? = null

    /**
     * Most recently analyzed camera frame, kept only for Scene Understanding
     * (see [describeSurroundings]) -- a raw reference update on every frame
     * is cheap, unlike JPEG-encoding every frame would be, so this is only
     * turned into an image when a description is actually requested.
     */
    @Volatile private var latestFrame: FrameInput? = null

    /** De-dupes [VoiceCommandManager] state re-collection from acting on the same recognized command twice. */
    private var lastHandledVoiceEventId: Long = 0

    /** trackingId -> last time a hazard warning was fired for it, so a static chair doesn't get re-warned every frame. */
    private val hazardWarnedTrackIds = mutableMapOf<Int, Long>()

    /** Only one hazard LLM call in flight at a time -- a network round trip is far slower than the frame rate. */
    @Volatile private var hazardCheckInFlight = false

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
        viewModelScope.launch {
            voiceCommandManager.state.collect { voiceState ->
                if (voiceState.status != VoiceCommandStatus.RECOGNIZED || voiceState.eventId == lastHandledVoiceEventId) return@collect
                val command = voiceState.command ?: return@collect

                lastHandledVoiceEventId = voiceState.eventId
                val searchQuery = ObjectSearchMatcher.extractQuery(command)
                when {
                    SceneDescriptionMatcher.matches(command) -> describeSurroundings(command)
                    TextReadingMatcher.matches(command) -> readTextAloud(command)
                    searchQuery != null -> searchForObject(searchQuery)
                }
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
            latestFrame = null
            hazardWarnedTrackIds.clear()
            _uiState.update { it.copy(trackedObjects = emptyList(), hazardWarningStatus = HazardWarningStatus.Idle) }
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

    /** Manual trigger (e.g. a UI button) for the same flow the "jelaskan lingkungan" voice command reaches. */
    fun describeSurroundingsManually() = describeSurroundings(manualRequestLabel(currentSettings.speechLanguage))

    /**
     * Scene Understanding: captures the current camera frame, bundles it
     * with what the on-device pipeline already knows (detected objects,
     * distance, movement, risk), sends it to the user's configured vision
     * LLM (see Settings > LLM), and speaks back a short (1-2 sentence)
     * description. Never invents objects beyond what detection/the image
     * show -- that instruction lives in [ScenePromptBuilder], not here.
     */
    private fun describeSurroundings(triggerCommandText: String) {
        val language = currentSettings.speechLanguage
        val frame = latestFrame
        if (frame == null) {
            failScene(noFrameMessage(language))
            return
        }
        val baseUrl = currentSettings.llmBaseUrl
        val model = currentSettings.llmModel
        if (baseUrl.isNullOrBlank() || model.isNullOrBlank()) {
            failScene(missingLlmConfigMessage(language))
            return
        }

        val trackedObjects = _uiState.value.trackedObjects
        val highestRisk = trackedObjects.maxByOrNull { it.riskLevel.priority }?.riskLevel ?: RiskLevel.SAFE
        val objectSummaries = trackedObjects.map {
            DetectedObjectSummary(
                label = it.detection.label,
                distanceMeters = it.estimatedDistanceMeters,
                movement = it.movementDirection,
                confidence = it.detection.confidence,
                riskLevel = it.riskLevel
            )
        }

        _uiState.update { it.copy(sceneDescriptionStatus = SceneDescriptionStatus.Processing) }

        viewModelScope.launch {
            val jpeg = withContext(ioDispatcher) {
                ImageUtils.rgbaToUprightJpeg(frame.rgba, frame.width, frame.height, frame.rotationDegrees, frame.mirror)
            }
            val context = SceneContext(jpeg, objectSummaries, highestRisk, triggerCommandText)
            val prompt = ScenePromptBuilder.build(context, language)

            val result = withContext(ioDispatcher) {
                llmClient.chatWithImage(baseUrl, currentSettings.llmApiKey, model, prompt, jpeg)
            }
            when (result) {
                is LlmClient.ChatResult.Success -> {
                    _uiState.update { it.copy(sceneDescriptionStatus = SceneDescriptionStatus.Success(result.text)) }
                    ttsManager.speak(result.text, flushQueue = false, utteranceId = "scene_description")
                }
                is LlmClient.ChatResult.Failure -> failScene(sceneFailedMessage(language, result.message))
            }
        }
    }

    private fun failScene(message: String) {
        _uiState.update { it.copy(sceneDescriptionStatus = SceneDescriptionStatus.Failure(message)) }
        ttsManager.speak(message, flushQueue = false, utteranceId = "scene_description_error")
    }

    private fun noFrameMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Belum ada gambar kamera untuk dianalisis."
        AnnouncementLanguage.ENGLISH -> "No camera frame available to analyze yet."
    }

    private fun missingLlmConfigMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Atur endpoint LLM di Pengaturan dulu untuk memakai fitur ini."
        AnnouncementLanguage.ENGLISH -> "Set up an LLM endpoint in Settings first to use this feature."
    }

    private fun sceneFailedMessage(language: AnnouncementLanguage, detail: String): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Maaf, gagal menganalisis lingkungan. $detail"
        AnnouncementLanguage.ENGLISH -> "Sorry, I couldn't analyze your surroundings. $detail"
    }

    private fun manualRequestLabel(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Jelaskan lingkungan saya"
        AnnouncementLanguage.ENGLISH -> "Describe my surroundings"
    }

    /** Manual trigger (e.g. a UI button) for the same flow the "bacakan tulisan" voice command reaches. */
    fun readTextManually() = readTextAloud(manualReadRequestLabel(currentSettings.speechLanguage))

    /**
     * Text Reading (OCR): captures the current camera frame and asks the
     * user's configured vision LLM to read out only the prominent/relevant
     * text in it (ignoring background noise), never inventing text it
     * can't clearly see -- that instruction lives in [TextReadingPromptBuilder],
     * not here. Unlike Scene Understanding, no detection/risk context is
     * sent -- the acceptance criteria for this feature is image-only.
     */
    private fun readTextAloud(triggerCommandText: String) {
        val language = currentSettings.speechLanguage
        val frame = latestFrame
        if (frame == null) {
            failTextReading(noFrameMessage(language))
            return
        }
        val baseUrl = currentSettings.llmBaseUrl
        val model = currentSettings.llmModel
        if (baseUrl.isNullOrBlank() || model.isNullOrBlank()) {
            failTextReading(missingLlmConfigMessage(language))
            return
        }

        _uiState.update { it.copy(textReadingStatus = TextReadingStatus.Processing) }

        viewModelScope.launch {
            // Higher JPEG quality than the scene-description capture --
            // compression artifacts that don't hurt "is this a chair"
            // can easily turn a phone number into unreadable mush.
            val jpeg = withContext(ioDispatcher) {
                ImageUtils.rgbaToUprightJpeg(frame.rgba, frame.width, frame.height, frame.rotationDegrees, frame.mirror, quality = 92)
            }
            val prompt = TextReadingPromptBuilder.build(triggerCommandText, language)

            val result = withContext(ioDispatcher) {
                llmClient.chatWithImage(baseUrl, currentSettings.llmApiKey, model, prompt, jpeg)
            }
            when (result) {
                is LlmClient.ChatResult.Success -> {
                    _uiState.update { it.copy(textReadingStatus = TextReadingStatus.Success(result.text)) }
                    ttsManager.speak(result.text, flushQueue = false, utteranceId = "text_reading")
                }
                is LlmClient.ChatResult.Failure -> failTextReading(textReadingFailedMessage(language, result.message))
            }
        }
    }

    private fun failTextReading(message: String) {
        _uiState.update { it.copy(textReadingStatus = TextReadingStatus.Failure(message)) }
        ttsManager.speak(message, flushQueue = false, utteranceId = "text_reading_error")
    }

    private fun textReadingFailedMessage(language: AnnouncementLanguage, detail: String): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Maaf, gagal membaca tulisan. $detail"
        AnnouncementLanguage.ENGLISH -> "Sorry, I couldn't read the text. $detail"
    }

    private fun manualReadRequestLabel(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Bacakan tulisan ini"
        AnnouncementLanguage.ENGLISH -> "Read this text"
    }

    /**
     * Hazard Awareness: unlike Scene Understanding/Text Reading, this is
     * never triggered by the user -- [HazardTrigger] fires it automatically
     * whenever a large object looks like it's blocking the walking path.
     * Deliberately silent on failure (only logged + reflected in UI state,
     * no spoken error) since the user never asked for this particular
     * check; the fast on-device [AnnouncementManager] path remains the
     * primary, low-latency safety mechanism regardless of whether this
     * LLM-based supplement succeeds.
     */
    private fun checkForHazard(trackedObjects: List<TrackedObject>, frameWidth: Int, frameHeight: Int, frame: FrameInput, nowMs: Long) {
        if (!currentSettings.enableVoiceAssistance || hazardCheckInFlight) return
        val baseUrl = currentSettings.llmBaseUrl
        val model = currentSettings.llmModel
        if (baseUrl.isNullOrBlank() || model.isNullOrBlank()) return

        val candidate = trackedObjects.firstOrNull { tracked ->
            val lastWarnedAt = hazardWarnedTrackIds[tracked.trackingId] ?: 0L
            if (nowMs - lastWarnedAt < HAZARD_COOLDOWN_MS) return@firstOrNull false
            val box = tracked.detection.boundingBox
            HazardTrigger.isBlockingHazard(box.width(), box.height(), box.centerX(), frameWidth, frameHeight, tracked.riskLevel)
        } ?: return

        hazardWarnedTrackIds[candidate.trackingId] = nowMs
        hazardCheckInFlight = true
        _uiState.update { it.copy(hazardWarningStatus = HazardWarningStatus.Processing) }

        val language = currentSettings.speechLanguage
        val objectSummaries = trackedObjects.map {
            DetectedObjectSummary(
                label = it.detection.label,
                distanceMeters = it.estimatedDistanceMeters,
                movement = it.movementDirection,
                confidence = it.detection.confidence,
                riskLevel = it.riskLevel
            )
        }

        viewModelScope.launch {
            try {
                val jpeg = withContext(ioDispatcher) {
                    ImageUtils.rgbaToUprightJpeg(frame.rgba, frame.width, frame.height, frame.rotationDegrees, frame.mirror)
                }
                val prompt = HazardPromptBuilder.build(objectSummaries, language)
                val result = withContext(ioDispatcher) {
                    llmClient.chatWithImage(baseUrl, currentSettings.llmApiKey, model, prompt, jpeg)
                }
                when (result) {
                    is LlmClient.ChatResult.Success -> {
                        _uiState.update { it.copy(hazardWarningStatus = HazardWarningStatus.Success(result.text)) }
                        ttsManager.speak(result.text, flushQueue = false, utteranceId = "hazard_warning")
                    }
                    is LlmClient.ChatResult.Failure -> {
                        Log.w(TAG, "Hazard check failed: ${result.message}")
                        _uiState.update { it.copy(hazardWarningStatus = HazardWarningStatus.Failure(result.message)) }
                    }
                }
            } finally {
                hazardCheckInFlight = false
            }
        }
    }

    /**
     * Object/Landmark Search: captures the current camera frame, bundles it
     * with the on-device detection list (each object's left/center/right
     * position included, so the LLM's location claim is grounded in real
     * geometry, not a pixel guess -- see [HorizontalPositionClassifier]),
     * and asks the vision LLM whether [query] is present and where.
     * Blur/unclear-image handling and "don't invent it" instructions live
     * in [ObjectSearchPromptBuilder], not here. No manual UI trigger for
     * this one (unlike Scene Understanding/Text Reading) -- the query is
     * free-form text that only a voice command naturally provides.
     */
    private fun searchForObject(query: String) {
        val language = currentSettings.speechLanguage
        val frame = latestFrame
        if (frame == null) {
            failObjectSearch(noFrameMessage(language))
            return
        }
        val baseUrl = currentSettings.llmBaseUrl
        val model = currentSettings.llmModel
        if (baseUrl.isNullOrBlank() || model.isNullOrBlank()) {
            failObjectSearch(missingLlmConfigMessage(language))
            return
        }

        val trackedObjects = _uiState.value.trackedObjects
        val frameWidth = _uiState.value.frameUprightWidth
        val objectSummaries = trackedObjects.map {
            DetectedObjectSummary(
                label = it.detection.label,
                distanceMeters = it.estimatedDistanceMeters,
                movement = it.movementDirection,
                confidence = it.detection.confidence,
                riskLevel = it.riskLevel,
                horizontalPosition = HorizontalPositionClassifier.classify(it.detection.boundingBox.centerX(), frameWidth)
            )
        }

        _uiState.update { it.copy(objectSearchStatus = ObjectSearchStatus.Processing) }

        viewModelScope.launch {
            val jpeg = withContext(ioDispatcher) {
                ImageUtils.rgbaToUprightJpeg(frame.rgba, frame.width, frame.height, frame.rotationDegrees, frame.mirror)
            }
            val prompt = ObjectSearchPromptBuilder.build(query, objectSummaries, language)

            val result = withContext(ioDispatcher) {
                llmClient.chatWithImage(baseUrl, currentSettings.llmApiKey, model, prompt, jpeg)
            }
            when (result) {
                is LlmClient.ChatResult.Success -> {
                    _uiState.update { it.copy(objectSearchStatus = ObjectSearchStatus.Success(result.text)) }
                    ttsManager.speak(result.text, flushQueue = false, utteranceId = "object_search")
                }
                is LlmClient.ChatResult.Failure -> failObjectSearch(objectSearchFailedMessage(language, result.message))
            }
        }
    }

    private fun failObjectSearch(message: String) {
        _uiState.update { it.copy(objectSearchStatus = ObjectSearchStatus.Failure(message)) }
        ttsManager.speak(message, flushQueue = false, utteranceId = "object_search_error")
    }

    private fun objectSearchFailedMessage(language: AnnouncementLanguage, detail: String): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Maaf, gagal mencari objek. $detail"
        AnnouncementLanguage.ENGLISH -> "Sorry, I couldn't search for the object. $detail"
    }

    private suspend fun onFrame(frame: FrameInput) {
        if (!_uiState.value.isRunning) return
        latestFrame = frame
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

        checkForHazard(withRisk, uprightWidth, uprightHeight, frame, nowMs)

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

    private companion object {
        const val TAG = "DetectionViewModel"

        /**
         * Per-track cooldown before the same object can trigger another
         * hazard LLM call. Much longer than AnnouncementManager's ~4s normal
         * cooldown deliberately -- this fires a real network request, not a
         * local TTS call, and a stationary chair doesn't need re-describing
         * every few seconds.
         */
        const val HAZARD_COOLDOWN_MS = 15_000L
    }
}
