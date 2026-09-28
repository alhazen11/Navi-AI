package com.apps.naviai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.audio.ProcessingCue
import com.apps.naviai.audio.TextToSpeechManager
import com.apps.naviai.audio.VoiceCommandManager
import com.apps.naviai.audio.VoiceCommandStatus
import android.util.Log
import com.apps.naviai.camera.FrameAnalyzer
import com.apps.naviai.camera.ImageUtils
import com.apps.naviai.core.common.InferenceDispatcher
import com.apps.naviai.core.common.IoDispatcher
import com.apps.naviai.database.RouteRepository
import com.apps.naviai.detection.detector.DetectorState
import com.apps.naviai.detection.detector.FrameInput
import com.apps.naviai.detection.detector.ObjectDetector
import com.apps.naviai.detection.preprocessing.ImagePreprocessor
import com.apps.naviai.detection.risk.RiskAssessmentEngine
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.detection.risk.RiskSensitivity
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.ObjectTracker
import com.apps.naviai.detection.tracking.TrackedObject
import com.apps.naviai.domain.model.AppSettings
import com.apps.naviai.llm.LlmClient
import com.apps.naviai.location.LocationPermission
import com.apps.naviai.memory.ConversationMemoryRepository
import com.apps.naviai.navigation.GoHomeCommandMatcher
import com.apps.naviai.navigation.GoHomeSignal
import com.apps.naviai.memory.MemoryCategoryClassifier
import com.apps.naviai.memory.MemoryImportanceClassifier
import com.apps.naviai.memory.MemorySearch
import com.apps.naviai.recording.RouteRecorder
import com.apps.naviai.routenav.RouteNavigationService
import com.apps.naviai.scene.DetectedObjectSummary
import com.apps.naviai.scene.HazardPromptBuilder
import com.apps.naviai.scene.HazardTrigger
import com.apps.naviai.scene.ObjectSearchPromptBuilder
import com.apps.naviai.scene.SceneContext
import com.apps.naviai.scene.ScenePromptBuilder
import com.apps.naviai.scene.TextReadingPromptBuilder
import com.apps.naviai.settings.SettingsRepository
import com.apps.naviai.voiceagent.ProModeCommandMatcher
import com.apps.naviai.voiceagent.ProModeTools
import com.apps.naviai.voiceagent.VoiceAgentClient
import com.apps.naviai.voiceagent.VoiceAgentEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class TranscriptSpeaker { USER, AGENT }
data class TranscriptEntry(val speaker: TranscriptSpeaker, val text: String)

/**
 * The feature groups [ProModeTools] exposes, for the "what's running" panel
 * on [com.apps.naviai.ui.screens.ProModeScreen] -- grouped by tool, not
 * one-row-per-tool (e.g. `save_memory`/`recall_memory`/`forget_memory`/
 * `clear_all_memories`/`list_all_memories` are all [MEMORY]), since the
 * panel is meant to answer "what is NAVI doing right now" at the same
 * granularity [com.apps.naviai.ui.screens.DetectionScreen]'s own status
 * cards do (one card per feature area, not per matcher).
 */
enum class ProModeFeature(val label: String) {
    SCENE_UNDERSTANDING("Scene Understanding"),
    TEXT_READING("Text Reading"),
    OBJECT_SEARCH("Object Search"),
    MEMORY("Memory"),
    ROUTE_RECORDING("Route Recording"),
    NAVIGATION("Navigation"),
    ROUTE_MANAGEMENT("Saved Routes"),
    /** Not a tool call, unlike every other row here -- see [ProModeViewModel.checkForHazard]. Fires automatically, same as [com.apps.naviai.ui.viewmodel.DetectionViewModel]'s own Hazard Awareness. */
    HAZARD_AWARENESS("Hazard Awareness")
}

/** One [ProModeFeature]'s most recent outcome -- absent from [ProModeUiState.featureStatuses] means it hasn't run yet this session. */
sealed interface FeatureStatus {
    data object Running : FeatureStatus
    data class Success(val message: String) : FeatureStatus
    data class Failed(val message: String) : FeatureStatus
}

sealed interface ProModeConnectionStatus {
    data object Connecting : ProModeConnectionStatus
    data object Ready : ProModeConnectionStatus
    /** The WebSocket dropped unexpectedly and [com.apps.naviai.voiceagent.VoiceAgentClient] is attempting a `session.resume` reconnect -- see its class doc. Mic capture keeps running through this. */
    data object Reconnecting : ProModeConnectionStatus
    data class Unavailable(val message: String) : ProModeConnectionStatus
    data class Error(val message: String) : ProModeConnectionStatus
    data object Ended : ProModeConnectionStatus
}

data class ProModeUiState(
    val status: ProModeConnectionStatus = ProModeConnectionStatus.Connecting,
    val transcript: List<TranscriptEntry> = emptyList(),
    val activeToolLabel: String? = null,
    /** True from the moment the user finishes speaking until a tool call or a reply arrives -- without this, the UI (and a screen-reader user) had no feedback at all that NAVI was working on a reply. */
    val isProcessing: Boolean = false,
    /**
     * True while NAVI is actually speaking a reply, driven by [TextToSpeechManager.isSpeaking]
     * (real engine progress, not a duration estimate) -- surfaced so the UI can show a "Speaking…"
     * state instead of implying the agent is idly listening. The mic is unconditionally muted for
     * this exact window too (see the `isSpeaking.collect` block in [ProModeViewModel.init]'s doc) --
     * no mid-sentence interruption, only right after NAVI finishes.
     */
    val isMicMuted: Boolean = false,
    /**
     * Raw protocol trace lines from [VoiceAgentClient] (see
     * [VoiceAgentEvent.DebugLog]'s doc), capped at [ProModeViewModel.MAX_DEBUG_LOG_LINES]
     * -- shown in a copyable panel on [com.apps.naviai.ui.screens.ProModeScreen]
     * since this protocol has no live-tested reference and asking for
     * `adb logcat` output hasn't been a workable way to get real diagnostic
     * evidence when something doesn't behave as documented.
     */
    val debugLog: List<String> = emptyList(),
    /** Every [ProModeFeature]'s most recent outcome this session -- see [FeatureStatus] and [ProModeViewModel.track]. Drives the features panel on [com.apps.naviai.ui.screens.ProModeScreen]. */
    val featureStatuses: Map<ProModeFeature, FeatureStatus> = emptyMap()
)

/**
 * Pro Mode (Conversation Mode): a full-duplex, multi-turn conversation with
 * NAVI over [VoiceAgentClient], replacing the regular wake-word-gated
 * pipeline's keyword/regex command matchers with LLM tool-calling (see
 * [ProModeTools] for the schema, and this class's tool handlers below for
 * what each one actually does).
 *
 * Deliberately does NOT run [com.apps.naviai.audio.AnnouncementManager]'s
 * continuous on-device announcements -- unlike the LLM-backed Hazard
 * Awareness supplement below, that pass would speak on every risky object at
 * its own ~4s cadence, constantly talking over the agent's own audio through
 * the same speaker. It DOES run the regular object-detection/tracking/risk
 * pipeline every frame (via [createFrameAnalyzer] -> [onFrame]), same as
 * [com.apps.naviai.ui.viewmodel.DetectionViewModel], for two reasons: the
 * vision-backed tools (`describe_surroundings`, `read_text`, `search_object`)
 * need a current frame regardless, and [checkForHazard] needs real
 * [TrackedObject.riskLevel]s to decide whether a large object is actually
 * blocking the walking path (see [HazardTrigger]) -- unlike distance
 * estimation, skipped here (always `null` in the observations passed to
 * [ObjectTracker.update]) since [RiskAssessmentEngine.assess] still reaches
 * MEDIUM+ risk from region/movement/confidence alone, and Pro Mode has no
 * calibration-aware [com.apps.naviai.domain.model.CameraParameters] to feed a
 * real distance estimate anyway.
 *
 * Unlike that continuous pass, [checkForHazard] only fires occasionally (a
 * real network call, gated by [HazardTrigger]'s conservative "large AND
 * centered AND at least MEDIUM risk" check plus a per-track cooldown), and
 * its result is queued into [ttsManager] with `flushQueue = false` -- it
 * waits its turn behind whatever the agent is currently saying rather than
 * cutting it off, so it never actually talks over the agent's audio, just
 * shares the same speak queue with it.
 * Continuous on-device safety announcements resume automatically once the
 * user exits back to the regular Detection screen -- see this class's exit
 * handling and [com.apps.naviai.ui.screens.DetectionScreen]'s own
 * mic-restart logic.
 *
 * **Feature coverage**: every app feature that's either already
 * voice-reachable in the regular pipeline (Scene Understanding, Text
 * Reading, Object Search, route recording/navigation/rename, conversation
 * memory) or trivially reachable through an existing repository method
 * (listing/deleting saved routes, listing all memories -- UI-only actions
 * in the regular app) is exposed as a tool here. Two things are
 * deliberately NOT exposed, both for concrete technical reasons rather than
 * being forgotten:
 * - **Distance calibration** ([com.apps.naviai.data.calibration.CalibrationRepository.calibrate])
 *   needs a real, live detected bounding-box height at the moment of
 *   calibration -- it fundamentally requires the object-detection pipeline
 *   this class intentionally doesn't run. Use the regular Settings >
 *   Calibrate distance screen instead.
 * - **Settings** (confidence threshold, speech rate, risk sensitivity,
 *   etc.) aren't voice-controllable anywhere else in the app either --
 *   Pro Mode doesn't introduce a new capability class the rest of the app
 *   doesn't already have.
 *
 * **Bilingual (Indonesian + English) caveat**: [connectIfReady] passes
 * `languageCodes = ["id", "en"]` as an intent, and the system prompt
 * instructs the agent to answer in whichever language the user just spoke,
 * regardless of the app's Settings language -- Pro Mode is meant to let the
 * user switch languages mid-conversation ("code-switching").
 *
 * Indonesian is NOT on AssemblyAI's published language list for the Voice
 * Agent pipeline (only English/Spanish/French/German/Italian/Portuguese are),
 * even though AssemblyAI's underlying speech-to-text models -- and this app's
 * own [com.apps.naviai.audio.AssemblyAiBatchTranscriber], used outside Pro
 * Mode -- do support it. Sending `"id"` on the wire anyway turned out to be
 * the root cause of Pro Mode's "agent greets, then never responds to
 * anything" bug: an unsupported code silently leaves the session with no
 * transcriber (see [VoiceAgentClient]'s class doc and [com.apps.naviai.voiceagent.VoiceAgentLanguages]).
 * [VoiceAgentClient] now filters unsupported codes out and lets the server
 * auto-detect instead, so this list stays an honest statement of intent
 * without breaking the session -- and `"id"` starts being sent for real the
 * day AssemblyAI adds it to [com.apps.naviai.voiceagent.VoiceAgentLanguages].
 *
 * What that means in practice: Indonesian speech in Pro Mode relies on the
 * server's automatic detection and is best-effort, not guaranteed. If
 * Indonesian recognition or the agent's own spoken Indonesian turns out poor,
 * the fallback is the regular (non-Pro-Mode) voice command pipeline, which
 * uses [com.apps.naviai.audio.AssemblyAiBatchTranscriber] and has no such
 * limitation.
 */
@HiltViewModel
class ProModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceAgentClient: VoiceAgentClient,
    private val voiceCommandManager: VoiceCommandManager,
    /** Every spoken word in Pro Mode, including the agent's own replies -- [VoiceAgentClient] discards AssemblyAI's synthesized voice (see its "Agent voice" doc). */
    private val ttsManager: TextToSpeechManager,
    private val settingsRepository: SettingsRepository,
    /** App-wide singleton, same instance [com.apps.naviai.ui.viewmodel.DetectionViewModel] uses -- see [onFrame]'s doc. */
    private val objectDetector: ObjectDetector,
    /** App-wide singleton -- reset on entry/exit here (see [init]/[onScreenClosed]) so stale tracks from whichever screen ran last don't leak into this session's [checkForHazard] cooldowns, or into the other screen's after this one exits. */
    private val objectTracker: ObjectTracker,
    /** App-wide singleton; `sensitivity` is re-applied from Settings on every emission, same as [com.apps.naviai.ui.viewmodel.DetectionViewModel]. */
    private val riskAssessmentEngine: RiskAssessmentEngine,
    private val llmClient: LlmClient,
    private val routeRecorder: RouteRecorder,
    private val routeRepository: RouteRepository,
    private val memoryRepository: ConversationMemoryRepository,
    private val goHomeSignal: GoHomeSignal,
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProModeUiState())
    val uiState: StateFlow<ProModeUiState> = _uiState.asStateFlow()

    private val _exitEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitEvents: SharedFlow<Unit> = _exitEvents

    @Volatile private var currentSettings: AppSettings = AppSettings()
    @Volatile private var latestFrame: FrameInput? = null
    @Volatile private var settingsLoaded = false
    @Volatile private var micPermissionGranted = false

    /** trackingId -> last time a hazard warning was fired for it -- see [DetectionViewModel]'s own copy of this idea, [DetectionViewModel.hazardWarnedTrackIds]. Cleared on entry/exit, same as [objectTracker] itself. */
    private val hazardWarnedTrackIds = mutableMapOf<Int, Long>()

    /** Only one hazard LLM call in flight at a time -- see [DetectionViewModel.hazardCheckInFlight]. */
    @Volatile private var hazardCheckInFlight = false

    /** Guards [ensureDetectorInitialized] to at most one [ObjectDetector.initialize] attempt per session. */
    private var detectorInitAttempted = false

    /**
     * Whether the user had ALREADY manually paused voice commands (the mic button on
     * [com.apps.naviai.ui.components.VoiceCommandPanel]) before this session ever started -- read
     * from [VoiceCommandManager.state] in [init], BEFORE this class's own [VoiceCommandManager.stopListening]
     * call below overwrites that same status. [VoiceCommandStatus.PAUSED] is set by ANY call to
     * `stopListening()`, not only a manual one -- reading it AFTER this class's own call (as
     * [resumeRegularVoiceListening] used to) always sees PAUSED regardless of what it was before,
     * since this class itself is what just set it, which meant [resumeRegularVoiceListening] was
     * unconditionally skipping the restart on every single exit, every time, silently -- exactly the
     * "voice command listens but never responds" bug this field exists to fix.
     */
    private val wasManuallyPausedBeforeEntry: Boolean

    init {
        // Captured BEFORE stopListening() below overwrites it -- see wasManuallyPausedBeforeEntry's
        // doc for why this exact ordering matters.
        wasManuallyPausedBeforeEntry = voiceCommandManager.state.value.status == VoiceCommandStatus.PAUSED

        // Only one engine may hold the mic at a time -- see VoiceAgentClient's class doc.
        // resumeRegularVoiceListening() explicitly hands it back on every exit path.
        voiceCommandManager.stopListening()

        // objectTracker is an app-wide singleton also used by DetectionViewModel -- reset it (and this
        // session's own cooldown map) so Detection's leftover tracks/history from before Pro Mode was
        // entered can't produce a misleading movement direction or a cooldown that never should have
        // applied to this session's own [checkForHazard] calls.
        objectTracker.reset()
        hazardWarnedTrackIds.clear()

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                settingsLoaded = true
                // Pro Mode speaks every reply through this engine (see the ttsManager param's doc), so it
                // has to carry the same language/rate the rest of the app uses -- otherwise replies come
                // out in whatever locale some other screen configured last.
                ttsManager.setLanguage(settings.speechLanguage)
                ttsManager.setSpeechRate(settings.speechRate)
                // Same knob DetectionViewModel's own settings collector applies to this same singleton --
                // see riskAssessmentEngine's param doc.
                riskAssessmentEngine.sensitivity = RiskSensitivity(cautionMultiplier = settings.riskSensitivity)
                ensureDetectorInitialized()
                connectIfReady()
            }
        }

        // The mic is open continuously, so NAVI's own voice is a real input source unless something
        // stops it -- muted, unconditionally, for exactly as long as ttsManager reports it's actually
        // speaking. This used to be conditional on VoiceAgentClient.isEchoCancellationAvailable() (an
        // AcousticEchoCanceler-based "real barge-in" that left the mic open on a device reporting AEC
        // support), back when the agent's own reply played through an internal AudioTrack in the same
        // low-level pipeline the AEC was attached alongside. That assumption broke the moment
        // TextToSpeechManager took over playback (see VoiceAgentClient's "Agent voice" doc -- needed for
        // Indonesian, which AssemblyAI's own voice doesn't cover): Android's system TTS engine is a
        // separate audio session AcousticEchoCanceler has no guaranteed reference to, and on a real
        // device this measurably was NOT being cancelled -- the agent audibly reacted to its own replies,
        // a self-response feedback loop, not just extra noise. Unconditional muting trades away
        // mid-sentence interruption (the user can still cut NAVI off right as a reply ends, via
        // UserSpeechStarted's ttsManager.stop() below) for actually being correct.
        viewModelScope.launch {
            ttsManager.isSpeaking.collect { speaking ->
                voiceAgentClient.setMicMuted(speaking)
                _uiState.update { it.copy(isMicMuted = speaking) }
            }
        }
    }

    /** [objectDetector] is an app-wide singleton that may already be Ready (Splash, or [DetectionViewModel] initialized it) -- only attempts [ObjectDetector.initialize] if it genuinely isn't. */
    private fun ensureDetectorInitialized() {
        if (detectorInitAttempted || objectDetector.state is DetectorState.Ready) return
        detectorInitAttempted = true
        viewModelScope.launch { objectDetector.initialize(currentSettings.useVulkan) }
    }

    fun createFrameAnalyzer(mirror: Boolean): FrameAnalyzer =
        FrameAnalyzer(viewModelScope, inferenceDispatcher, mirror) { frame -> onFrame(frame) }

    /**
     * Keeps [latestFrame] for the vision-backed tools (as before), then runs
     * the same detect -> track -> risk-assess pipeline [DetectionViewModel.onFrame]
     * does, but only far enough to feed [checkForHazard] -- no
     * [com.apps.naviai.audio.AnnouncementManager] pass, no UI overlay state,
     * no distance estimation (see this class's doc for why that's skipped).
     * Bails out early via [hazardPipelineAvailable] whenever the result
     * couldn't possibly matter (detector not ready, voice assistance off, no
     * LLM configured) so a Pro Mode session with none of those set up isn't
     * paying for a full NCNN inference pass every frame for nothing.
     */
    private suspend fun onFrame(frame: FrameInput) {
        latestFrame = frame
        if (!hazardPipelineAvailable()) return

        val result = objectDetector.detect(frame, currentSettings.confidenceThreshold, currentSettings.iouThreshold)
        val (uprightWidth, uprightHeight) = ImagePreprocessor.uprightSize(frame.width, frame.height, frame.rotationDegrees)
        val nowMs = System.currentTimeMillis()

        val tracked = if (currentSettings.enableTracking) {
            objectTracker.update(result.detections.map { it to null }, nowMs)
        } else {
            // Same SAFE-risk fallback DetectionViewModel.onFrame uses when tracking is off --
            // HazardTrigger requires at least MEDIUM risk, so this simply never triggers rather
            // than crashing on a missing track history.
            result.detections.mapIndexed { index, detection ->
                TrackedObject(
                    trackingId = index,
                    detection = detection,
                    estimatedDistanceMeters = null,
                    distanceConfidence = 0f,
                    movementDirection = MovementDirection.UNKNOWN,
                    riskLevel = RiskLevel.SAFE,
                    framesTracked = 1,
                    lastAnnouncedAtMs = null
                )
            }
        }

        val withRisk = tracked.map { it.copy(riskLevel = riskAssessmentEngine.assess(it, uprightWidth)) }
        checkForHazard(withRisk, uprightWidth, uprightHeight, frame, nowMs)
    }

    /** Whether running [onFrame]'s detection pipeline could possibly lead anywhere -- see that function's doc. */
    private fun hazardPipelineAvailable(): Boolean {
        if (!currentSettings.enableVoiceAssistance) return false
        if (objectDetector.state !is DetectorState.Ready) return false
        return !currentSettings.llmBaseUrl.isNullOrBlank() && !currentSettings.llmModel.isNullOrBlank()
    }

    /**
     * Hazard Awareness for Pro Mode: same trigger and prompt as
     * [DetectionViewModel.checkForHazard] (see [HazardTrigger]/[HazardPromptBuilder]),
     * but the result is spoken through [ttsManager] with `flushQueue = false`
     * instead -- it queues behind whatever NAVI is already saying rather
     * than interrupting the conversation, so it never actually competes with
     * the agent's own audio (see this class's doc). Tracked under
     * [ProModeFeature.HAZARD_AWARENESS] on the features panel, not via
     * [track] -- unlike every other row there, this isn't a tool call, so
     * there's no [VoiceAgentEvent.ToolInvoked] to drive
     * [ProModeUiState.activeToolLabel] for it, and a failure here should
     * stay silent (the user never asked for this particular check) rather
     * than [track]'s rethrow-on-failure.
     */
    private fun checkForHazard(trackedObjects: List<TrackedObject>, frameWidth: Int, frameHeight: Int, frame: FrameInput, nowMs: Long) {
        if (hazardCheckInFlight) return
        val baseUrl = currentSettings.llmBaseUrl
        val model = currentSettings.llmModel
        if (baseUrl.isNullOrBlank() || model.isNullOrBlank()) return

        val candidate = trackedObjects.firstOrNull { tracked ->
            val lastWarnedAt = hazardWarnedTrackIds[tracked.trackingId] ?: 0L
            if (nowMs - lastWarnedAt < HAZARD_COOLDOWN_MS) return@firstOrNull false
            val box = tracked.detection.boundingBox
            HazardTrigger.isBlockingHazard(
                tracked.detection.label, box.width(), box.height(), box.centerX(), frameWidth, frameHeight, tracked.riskLevel
            )
        } ?: return

        hazardWarnedTrackIds[candidate.trackingId] = nowMs
        hazardCheckInFlight = true
        setFeatureStatus(ProModeFeature.HAZARD_AWARENESS, FeatureStatus.Running)

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
                        setFeatureStatus(ProModeFeature.HAZARD_AWARENESS, FeatureStatus.Success(result.text))
                        ttsManager.speak(result.text, flushQueue = false, utteranceId = "pro_mode_hazard_${result.text.hashCode()}")
                    }
                    is LlmClient.ChatResult.Failure -> {
                        Log.w(TAG, "Pro Mode hazard check failed: ${result.message}")
                        setFeatureStatus(ProModeFeature.HAZARD_AWARENESS, FeatureStatus.Failed(result.message))
                    }
                }
            } finally {
                hazardCheckInFlight = false
            }
        }
    }

    /**
     * Called from [com.apps.naviai.ui.screens.ProModeScreen] whenever its
     * mic-permission state changes (including the initial value). Without
     * this, [connectIfReady] could open the Voice Agent session and reach
     * [VoiceAgentClient.startAudioCapture] before RECORD_AUDIO was actually
     * granted -- a race against the screen's own permission dialog that,
     * left unguarded, throws inside [VoiceAgentClient]'s WebSocket message
     * handling and can silently stop that connection from processing any
     * further server message at all (see [VoiceAgentClient.handleServerMessage]'s
     * class doc), which looks exactly like "the agent stopped replying".
     */
    fun onMicPermissionResult(granted: Boolean) {
        micPermissionGranted = granted
        connectIfReady()
    }

    private var connectAttempted = false

    /** Connects exactly once, and only once both Settings have loaded (see [init]) and RECORD_AUDIO is confirmed granted (see [onMicPermissionResult]) -- called from both. */
    private fun connectIfReady() {
        if (connectAttempted || !settingsLoaded) return
        val language = currentSettings.speechLanguage
        if (!micPermissionGranted) {
            _uiState.update { it.copy(status = ProModeConnectionStatus.Unavailable(missingMicPermissionMessage(language))) }
            return
        }
        connectAttempted = true

        if (currentSettings.offlineModeEnabled) {
            _uiState.update { it.copy(status = ProModeConnectionStatus.Unavailable(offlineMessage(language))) }
            return
        }
        val apiKey = currentSettings.assemblyAiApiKey
        if (apiKey.isNullOrBlank()) {
            _uiState.update { it.copy(status = ProModeConnectionStatus.Unavailable(missingApiKeyMessage(language))) }
            return
        }

        // Immediate local feedback that the screen itself has opened -- the WebSocket connect +
        // the Voice Agent's own greeting (see the `greeting` param below) can take a couple of
        // seconds, which would otherwise be silence with no confirmation Pro Mode is even starting.
        ttsManager.speak(enteringMessage(language), flushQueue = true, utteranceId = "pro_mode_enter")
        registerTools(language)
        voiceAgentClient.connect(
            apiKey = apiKey,
            systemPrompt = bilingualSystemPrompt(),
            greeting = greetingMessage(language),
            tools = ProModeTools.build(language),
            // Both languages regardless of the Settings language: Pro Mode
            // is meant to recognize Indonesian and English interchangeably
            // within the same session (the Voice Agent API's
            // input.language_codes array exists specifically for this
            // "code-switching" case) -- see the class doc's language caveat.
            languageCodes = listOf("id", "en"),
            onEvent = { event -> handleAgentEvent(event) }
        )
    }

    private fun handleAgentEvent(event: VoiceAgentEvent) {
        when (event) {
            VoiceAgentEvent.Connecting -> _uiState.update { it.copy(status = ProModeConnectionStatus.Connecting) }
            VoiceAgentEvent.Reconnecting -> _uiState.update { it.copy(status = ProModeConnectionStatus.Reconnecting) }
            VoiceAgentEvent.Ready -> {
                // Proof the connection is genuinely healthy -- resets the auto-restart budget so a
                // transient bad patch (a few session.ended in a row) doesn't permanently exhaust it
                // for the rest of however long the user stays in Pro Mode. See autoRestartAttempts' doc.
                autoRestartAttempts = 0
                _uiState.update { it.copy(status = ProModeConnectionStatus.Ready) }
            }
            VoiceAgentEvent.UserSpeechStarted -> {
                // Barge-in: the user started talking, so cut NAVI off mid-sentence instead of letting it
                // finish over them. Safe to call unconditionally -- it's a no-op when nothing is speaking.
                ttsManager.stop()
                _uiState.update { it.copy(isProcessing = false, activeToolLabel = null) }
            }
            VoiceAgentEvent.UserSpeechStopped -> _uiState.update { it.copy(isProcessing = true) }
            is VoiceAgentEvent.UserTranscript -> {
                // Checked first: a global reset takes priority over Pro Mode's own plain exit.
                if (GoHomeCommandMatcher.isMatch(event.text)) {
                    goHome()
                    return
                }
                if (ProModeCommandMatcher.isExit(event.text)) {
                    exitProMode()
                    return
                }
                // Only finalizes what the user said -- the agent hasn't
                // replied yet, so this must NOT clear isProcessing/
                // activeToolLabel (that's still pending, see AgentTranscript
                // below) or the "thinking" indicator would vanish right
                // before the actual wait for a reply begins.
                appendTranscript(TranscriptSpeaker.USER, event.text)
            }
            is VoiceAgentEvent.AgentTranscript -> {
                appendTranscript(TranscriptSpeaker.AGENT, event.text)
                // The only place NAVI's replies become audible: AssemblyAI's own voice is discarded by
                // VoiceAgentClient (see its "Agent voice" doc). Queued rather than flushed, so it can't
                // cut off the "Masuk ke Mode Pro." announcement still finishing from connectIfReady();
                // a genuine interruption is handled by UserSpeechStarted's ttsManager.stop() instead.
                if (event.text.isNotBlank()) {
                    ttsManager.speak(event.text, flushQueue = false, utteranceId = "pro_mode_reply_${event.text.hashCode()}")
                }
                _uiState.update { it.copy(activeToolLabel = null, isProcessing = false) }
            }
            is VoiceAgentEvent.ToolInvoked -> _uiState.update { it.copy(activeToolLabel = event.name, isProcessing = true) }
            // Authoritative turn-over signal (see VoiceAgentEvent.ReplyDone's doc) -- always returns to a
            // listening-ready state even if AgentTranscript never fired or its text failed to parse for this turn.
            VoiceAgentEvent.ReplyDone -> {
                _uiState.update { it.copy(activeToolLabel = null, isProcessing = false) }
                // See pendingToolExit's doc: only now, after the exit_pro_mode tool call's own reply has
                // been queued to speak (AgentTranscript, above, always precedes ReplyDone), is it safe to
                // tear the session down.
                if (pendingToolExit) {
                    pendingToolExit = false
                    exitAnnounced = true
                    sessionEndingIntentionally = true
                    voiceAgentClient.disconnect()
                    // Same mic hand-back as exitProMode()/goHome() -- see MIC_HANDOFF_DELAY_MS's and
                    // resumeRegularVoiceListening()'s docs. This tool-triggered exit is a THIRD,
                    // separate path back to Detection (the agent decides to call exit_pro_mode itself
                    // whenever the user's own phrasing doesn't match GoHomeCommandMatcher/
                    // ProModeCommandMatcher's fixed patterns -- e.g. "back to home" in natural
                    // English), so it needs this exact same hand-off, not just the two client-side
                    // phrase-matched exits.
                    viewModelScope.launch {
                        delay(MIC_HANDOFF_DELAY_MS)
                        resumeRegularVoiceListening()
                        _exitEvents.tryEmit(Unit)
                    }
                }
            }
            is VoiceAgentEvent.Error -> {
                _uiState.update { it.copy(status = ProModeConnectionStatus.Error(event.message), isProcessing = false) }
            }
            VoiceAgentEvent.Ended -> {
                _uiState.update { it.copy(status = ProModeConnectionStatus.Ended, activeToolLabel = null, isProcessing = false) }
                // Only auto-restart a session that ended on its own (server session.ended/timeout, or
                // VoiceAgentClient giving up on session.resume after a real disconnect) -- never one this
                // class itself just tore down on purpose. See sessionEndingIntentionally's doc.
                if (!sessionEndingIntentionally) attemptAutoRestart()
            }
            is VoiceAgentEvent.DebugLog -> _uiState.update {
                it.copy(debugLog = (it.debugLog + event.line).takeLast(MAX_DEBUG_LOG_LINES))
            }
        }
    }

    private fun appendTranscript(speaker: TranscriptSpeaker, text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(transcript = it.transcript + TranscriptEntry(speaker, text)) }
    }

    /** Set by [exitProMode]/[goHome] so the [onScreenClosed] that follows its own navigation doesn't cut its goodbye off mid-word. */
    private var exitAnnounced = false

    /**
     * Set right before this class itself ends the session on purpose -- [exitProMode], [goHome],
     * the `exit_pro_mode` tool's flush in [handleAgentEvent]'s `ReplyDone` branch, and
     * [onScreenClosed] (leaving the screen any other way, e.g. the back button). Read by
     * [VoiceAgentEvent.Ended]'s handler to decide whether to [attemptAutoRestart] -- a session that
     * ended because ONE of the above called [voiceAgentClient]`.disconnect()` should stay ended; one
     * that ended on its own (server `session.ended`, or [VoiceAgentClient] giving up on
     * `session.resume` after a real disconnect) should come back automatically instead of leaving
     * the user stuck on a dead "Session ended" screen until they back out and re-enter Pro Mode.
     */
    private var sessionEndingIntentionally = false

    /**
     * How many consecutive times [attemptAutoRestart] has fired since the last time the session
     * actually reached [VoiceAgentEvent.Ready] (reset there -- see that branch). Capped at
     * [MAX_AUTO_RESTART_ATTEMPTS] so a session that's unhealthy for a real reason (bad API key,
     * server outage) doesn't retry forever, hammering the endpoint and draining the battery/mic
     * indefinitely with no way for the user to tell it apart from a working Pro Mode.
     */
    private var autoRestartAttempts = 0

    /**
     * Reconnects a session that ended on its own -- see [sessionEndingIntentionally]'s doc. Resets
     * [connectAttempted] so [connectIfReady] runs its checks again from scratch (mic permission,
     * Offline Mode, API key may all have been fine a moment ago and still are, but re-checking costs
     * nothing and catches the case where one of them changed while disconnected). A short fixed
     * delay first, matching [VoiceAgentClient]'s own `RECONNECT_DELAY_MS` in spirit, so a server that
     * ends sessions instantly and repeatedly doesn't turn this into a tight reconnect loop.
     */
    private fun attemptAutoRestart() {
        if (autoRestartAttempts >= MAX_AUTO_RESTART_ATTEMPTS) return
        autoRestartAttempts++
        _uiState.update { it.copy(status = ProModeConnectionStatus.Reconnecting) }
        viewModelScope.launch {
            delay(AUTO_RESTART_DELAY_MS)
            connectAttempted = false
            connectIfReady()
        }
    }

    /**
     * Set by the `exit_pro_mode` tool handler (see [ProModeTools]), read and cleared on the next
     * [VoiceAgentEvent.ReplyDone] rather than disconnecting immediately when the handler returns.
     * Two reasons it waits, same as the tool-triggered screen hand-offs this app used to have (see
     * git history for `ProModeNavigationEvent` if that reasoning is needed again):
     * - The handler's return value still has to reach the server as a `tool.result` over the live
     *   WebSocket; disconnecting first risks that send racing (or losing to) the teardown.
     * - The agent's own spoken goodbye (its `transcript.agent` reply, informed by the tool
     *   description asking it to say one) should be queued in [ttsManager] before the session ends,
     *   not skipped -- that's the whole point of exposing this as a tool instead of only relying on
     *   [ProModeCommandMatcher.isExit]'s fixed phrase list, which exits with a canned line and no
     *   real reply from the agent at all.
     */
    private var pendingToolExit = false

    /**
     * Explicitly hands the mic back to the regular always-on listener when leaving Pro Mode --
     * called from every exit path ([exitProMode]/[goHome]/[onScreenClosed]) instead of relying on
     * Detection's own [com.apps.naviai.ui.components.VoiceCommandPanel] to notice on its own and
     * restart it. That used to be the only thing that restarted it: this class's own [init] calls
     * [voiceCommandManager]`.stopListening()` directly on the singleton (bypassing
     * [com.apps.naviai.ui.viewmodel.VoiceCommandViewModel]'s own `listeningRequested` bookkeeping
     * entirely), so getting it going again depended on Detection's `LaunchedEffect` actually firing
     * on the way back -- which is exactly the kind of Compose-recomposition timing this class has no
     * real control over from here, and evidently isn't reliable enough on its own. Calling
     * [VoiceCommandManager.startContinuousListening] directly mirrors how
     * [com.apps.naviai.recording.RecordingViewModel]/[com.apps.naviai.routenav.NavigationController]
     * already keep this same singleton alive themselves rather than depending on some other screen's
     * UI. Skips restarting if the user had manually paused listening before ever entering Pro Mode --
     * see [wasManuallyPausedBeforeEntry]'s doc for why this checks THAT captured field and not the
     * live [VoiceCommandManager.state] (checking the live status here used to always see
     * [VoiceCommandStatus.PAUSED] and skip the restart unconditionally, since this class's own [init]
     * is what set it moments earlier -- the actual cause of voice commands never coming back at all).
     */
    private fun resumeRegularVoiceListening() {
        if (wasManuallyPausedBeforeEntry) return
        voiceCommandManager.startContinuousListening(
            currentSettings.speechLanguage,
            currentSettings.assemblyAiApiKey,
            currentSettings.offlineModeEnabled
        )
    }

    /** Voice-triggered exit ("NAVI, matikan mode pro") -- see [ProModeCommandMatcher.isExit]. The screen's own back button reaches the same disconnect via [onScreenClosed]. */
    private fun exitProMode() {
        exitAnnounced = true
        sessionEndingIntentionally = true
        // flushQueue: drops whatever reply was still queued, since the user asked to leave.
        ttsManager.speak(exitingMessage(currentSettings.speechLanguage), flushQueue = true, utteranceId = "pro_mode_exit")
        voiceAgentClient.disconnect()
        // See MIC_HANDOFF_DELAY_MS's doc -- lets the just-released AudioRecord actually free the mic
        // before VoiceCommandManager tries to open a new one for it.
        viewModelScope.launch {
            delay(MIC_HANDOFF_DELAY_MS)
            resumeRegularVoiceListening()
            _exitEvents.tryEmit(Unit)
        }
    }

    /**
     * "NAVI, kembali" / "kembali ke home" -- see [GoHomeCommandMatcher] and [GoHomeSignal]'s docs.
     * Unlike [exitProMode] (which just pops back one level -- always correct for Pro Mode, since
     * it's only ever reached directly from Detection), this goes through [goHomeSignal] so the
     * result is the same "clear the whole stack" reset every other trigger gives, and also stops
     * any recording/navigation that might have been left running from before Pro Mode was entered
     * (Pro Mode's own [init] only pauses [voiceCommandManager]'s listening, it doesn't touch either
     * of those singletons).
     */
    private fun goHome() {
        exitAnnounced = true
        sessionEndingIntentionally = true
        if (routeRecorder.isRecording) routeRecorder.stop()
        RouteNavigationService.stop(context, spokenConfirmation = false)
        ttsManager.speak(goingHomeMessage(currentSettings.speechLanguage), flushQueue = true, utteranceId = "pro_mode_go_home")
        voiceAgentClient.disconnect()
        // See MIC_HANDOFF_DELAY_MS's doc -- lets the just-released AudioRecord actually free the mic
        // before Detection's VoiceCommandManager tries to open a new one for it, once goHomeSignal
        // navigates back there. Without this, VoiceCommandManager's fresh AudioRecord could be
        // constructed a handful of milliseconds after VoiceAgentClient released its own one for the
        // SAME input source -- Android doesn't guarantee the audio HAL has actually reclaimed the mic
        // that fast, and a new AudioRecord opened into that window can report a healthy
        // STATE_INITIALIZED while still not actually capturing real audio, which looks exactly like
        // "voice commands stopped working" with no error anywhere.
        viewModelScope.launch {
            delay(MIC_HANDOFF_DELAY_MS)
            resumeRegularVoiceListening()
            goHomeSignal.trigger()
        }
    }

    private fun goingHomeMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Kembali ke beranda."
        AnnouncementLanguage.ENGLISH -> "Returning to Home."
    }

    /**
     * Called from the screen's `onDispose` (back button, or navigating away any other way).
     * [resumeRegularVoiceListening] is called here too, harmlessly redundant with [exitProMode]/
     * [goHome]'s own call on whichever of those actually ran -- this is the ONLY hand-back for a
     * plain back-button exit (neither of those runs then), so it has to be covered here regardless.
     */
    fun onScreenClosed() {
        sessionEndingIntentionally = true
        // Leaving with a reply still being spoken should go quiet -- except right after
        // exitProMode()/goHome(), whose spoken line should survive the navigation it just triggered.
        if (!exitAnnounced) ttsManager.stop()
        voiceAgentClient.disconnect()
        // Symmetric with init{}'s reset -- leaves objectTracker clean for whichever screen (this one
        // again, or Detection) starts tracking next, rather than handing it stale tracks from this session.
        objectTracker.reset()
        hazardWarnedTrackIds.clear()
        viewModelScope.launch {
            delay(MIC_HANDOFF_DELAY_MS)
            resumeRegularVoiceListening()
        }
    }

    // ---- Tool dispatch: replaces the regular VoiceCommandParser + per-feature matchers with LLM tool-calling ----

    /**
     * Wraps a tool handler with [ProModeFeature] status tracking for the features panel on
     * [com.apps.naviai.ui.screens.ProModeScreen] -- [FeatureStatus.Running] before [block] runs,
     * then [FeatureStatus.Success]/[FeatureStatus.Failed] from its outcome. Rethrows on failure
     * unchanged so [VoiceAgentClient.handleToolCall] still sees it and reports `is_error:true` to
     * the agent -- this only observes the outcome, it never changes it.
     */
    private suspend fun track(feature: ProModeFeature, block: suspend () -> String): String {
        setFeatureStatus(feature, FeatureStatus.Running)
        try {
            val result = block()
            setFeatureStatus(feature, FeatureStatus.Success(result))
            return result
        } catch (t: Throwable) {
            setFeatureStatus(feature, FeatureStatus.Failed(t.message ?: "Failed"))
            throw t
        }
    }

    private fun setFeatureStatus(feature: ProModeFeature, status: FeatureStatus) {
        _uiState.update { it.copy(featureStatuses = it.featureStatuses + (feature to status)) }
    }

    private fun registerTools(language: AnnouncementLanguage) {
        voiceAgentClient.registerTool("describe_surroundings") { track(ProModeFeature.SCENE_UNDERSTANDING) { describeSurroundings(language) } }
        voiceAgentClient.registerTool("read_text") { track(ProModeFeature.TEXT_READING) { readText(language) } }
        voiceAgentClient.registerTool("search_object") { args -> track(ProModeFeature.OBJECT_SEARCH) { searchObject(args.optString("query"), language) } }
        voiceAgentClient.registerTool("save_memory") { args -> track(ProModeFeature.MEMORY) { saveMemory(args.optString("content"), language) } }
        voiceAgentClient.registerTool("recall_memory") { args -> track(ProModeFeature.MEMORY) { recallMemory(args.optString("query"), language) } }
        voiceAgentClient.registerTool("forget_memory") { args -> track(ProModeFeature.MEMORY) { forgetMemory(args.optString("query"), language) } }
        voiceAgentClient.registerTool("clear_all_memories") { args -> track(ProModeFeature.MEMORY) { clearAllMemories(args.optBoolean("confirmed", false), language) } }
        voiceAgentClient.registerTool("start_route_recording") { track(ProModeFeature.ROUTE_RECORDING) { startRouteRecording(language) } }
        voiceAgentClient.registerTool("stop_route_recording") { args -> track(ProModeFeature.ROUTE_RECORDING) { stopRouteRecording(args.optString("name"), language) } }
        voiceAgentClient.registerTool("start_navigation") { args -> track(ProModeFeature.NAVIGATION) { startNavigation(args.optString("route_name"), language) } }
        voiceAgentClient.registerTool("stop_navigation") { track(ProModeFeature.NAVIGATION) { stopNavigation(language) } }
        voiceAgentClient.registerTool("list_saved_routes") { track(ProModeFeature.ROUTE_MANAGEMENT) { listSavedRoutes(language) } }
        voiceAgentClient.registerTool("rename_route") { args -> track(ProModeFeature.ROUTE_MANAGEMENT) { renameRoute(args.optString("old_name"), args.optString("new_name"), language) } }
        voiceAgentClient.registerTool("delete_route") { args -> track(ProModeFeature.ROUTE_MANAGEMENT) { deleteRoute(args.optString("name"), args.optBoolean("confirmed", false), language) } }
        voiceAgentClient.registerTool("list_all_memories") { track(ProModeFeature.MEMORY) { listAllMemories(language) } }
        // Not wrapped in track(): exiting isn't one of the FeatureStatus rows on the features panel,
        // and there's no point recording an outcome for a call whose whole effect is tearing this
        // ViewModel's own session down a moment later.
        voiceAgentClient.registerTool("exit_pro_mode") { exitProModeTool(language) }
    }

    /**
     * Handler for the `exit_pro_mode` tool (see [ProModeTools]) -- sets [pendingToolExit] rather
     * than disconnecting here directly; see that field's doc for why. Returns a short line the
     * agent's own LLM works into its actual spoken goodbye (per the tool's description, which asks
     * it to say one), same as any other tool's result text.
     */
    private fun exitProModeTool(language: AnnouncementLanguage): String {
        pendingToolExit = true
        return exitingMessage(language)
    }

    private suspend fun currentJpegOrThrow(quality: Int = 80): Pair<FrameInput, ByteArray> {
        val frame = latestFrame ?: throw IllegalStateException(noFrameMessage(currentSettings.speechLanguage))
        val jpeg = withContext(ioDispatcher) {
            ImageUtils.rgbaToUprightJpeg(frame.rgba, frame.width, frame.height, frame.rotationDegrees, frame.mirror, quality = quality)
        }
        return frame to jpeg
    }

    private fun requireLlmConfig(language: AnnouncementLanguage): Triple<String, String?, String> {
        val baseUrl = currentSettings.llmBaseUrl
        val model = currentSettings.llmModel
        if (baseUrl.isNullOrBlank() || model.isNullOrBlank()) throw IllegalStateException(missingLlmConfigMessage(language))
        return Triple(baseUrl, currentSettings.llmApiKey, model)
    }

    /**
     * The only three tools that speak [ProcessingCue] -- see that class's doc: a real vision-LLM
     * round trip is the one case in Pro Mode that's genuinely slow enough to need an audible
     * "working on it" cue. Speaking it from every tool via [track] instead (tried first) queued a
     * separate "Memproses…" onto [ttsManager] for EVERY tool call, including quick local ones
     * (Memory, Saved Routes) and any turn that chains more than one tool call -- which piled up into
     * several redundant "Memproses…" utterances speaking back to back before any real reply, making
     * Pro Mode sound stuck "processing" continuously even for an ordinary one-line request.
     */
    private fun speakProcessingCue(language: AnnouncementLanguage) {
        ttsManager.speak(ProcessingCue.message(language), flushQueue = false, utteranceId = "pro_mode_processing")
    }

    private suspend fun describeSurroundings(language: AnnouncementLanguage): String {
        val (baseUrl, apiKey, model) = requireLlmConfig(language)
        val (_, jpeg) = currentJpegOrThrow()
        speakProcessingCue(language)
        val prompt = ScenePromptBuilder.build(SceneContext(jpeg, emptyList(), RiskLevel.SAFE, enteringLabel(language)), language)
        return chatOrThrow(baseUrl, apiKey, model, prompt, jpeg)
    }

    private suspend fun readText(language: AnnouncementLanguage): String {
        val (baseUrl, apiKey, model) = requireLlmConfig(language)
        val (_, jpeg) = currentJpegOrThrow(quality = 92)
        speakProcessingCue(language)
        val prompt = TextReadingPromptBuilder.build(readTextLabel(language), language)
        return chatOrThrow(baseUrl, apiKey, model, prompt, jpeg)
    }

    private suspend fun searchObject(query: String, language: AnnouncementLanguage): String {
        if (query.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        val (baseUrl, apiKey, model) = requireLlmConfig(language)
        val (_, jpeg) = currentJpegOrThrow()
        speakProcessingCue(language)
        val prompt = ObjectSearchPromptBuilder.build(query, emptyList(), language)
        return chatOrThrow(baseUrl, apiKey, model, prompt, jpeg)
    }

    private suspend fun chatOrThrow(baseUrl: String, apiKey: String?, model: String, prompt: String, jpeg: ByteArray): String {
        val result = withContext(ioDispatcher) { llmClient.chatWithImage(baseUrl, apiKey, model, prompt, jpeg) }
        return when (result) {
            is LlmClient.ChatResult.Success -> result.text
            is LlmClient.ChatResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    private suspend fun saveMemory(content: String, language: AnnouncementLanguage): String {
        if (content.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        val category = MemoryCategoryClassifier.classify(content)
        val important = MemoryImportanceClassifier.isImportant(content)
        memoryRepository.save(content, category, important)
        return memorySavedMessage(language)
    }

    private suspend fun recallMemory(query: String, language: AnnouncementLanguage): String {
        if (query.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        val best = MemorySearch.search(query, memoryRepository.getAllOnce()).firstOrNull()
        return best?.content ?: memoryNotFoundMessage(language)
    }

    private suspend fun forgetMemory(query: String, language: AnnouncementLanguage): String {
        if (query.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        val best = MemorySearch.search(query, memoryRepository.getAllOnce()).firstOrNull() ?: return memoryNotFoundMessage(language)
        memoryRepository.delete(best.id)
        return memoryDeletedMessage(language)
    }

    /**
     * `confirmed` is a code-level safety net, not just prose in the system
     * prompt (see [ProModeTools]'s class doc's "Natural confirmation"
     * section) -- the agent's own LLM interprets whatever natural-language
     * answer the user actually gave ("iya boleh", "jangan deh", "lanjutkan
     * saja", not just a literal "ya"/"yes") and must resolve that into this
     * explicit boolean before anything gets deleted. A missing/false value
     * refuses the deletion outright, regardless of what the tool's
     * description asked the agent to do.
     */
    private suspend fun clearAllMemories(confirmed: Boolean, language: AnnouncementLanguage): String {
        if (!confirmed) return confirmationRequiredMessage(language)
        memoryRepository.deleteAll()
        return allMemoriesClearedMessage(language)
    }

    private fun startRouteRecording(language: AnnouncementLanguage): String {
        if (!LocationPermission.isGranted(context)) throw IllegalStateException(locationPermissionMessage(language))
        routeRecorder.start()
        return recordingStartedMessage(language)
    }

    private suspend fun stopRouteRecording(name: String, language: AnnouncementLanguage): String {
        val points = routeRecorder.stop()
        if (points.isEmpty()) throw IllegalStateException(nothingRecordedMessage(language))
        val routeName = name.ifBlank { defaultRouteName(language) }
        routeRepository.saveRoute(routeName, System.currentTimeMillis(), points)
        return routeSavedMessage(routeName, language)
    }

    private suspend fun startNavigation(routeName: String, language: AnnouncementLanguage): String {
        if (routeName.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        val route = routeRepository.getRouteWithPointsByName(routeName)
        if (route == null || route.points.isEmpty()) throw IllegalStateException(routeNotFoundMessage(routeName, language))
        // listenForVoiceCommands = false -- see RouteNavigationService.start's doc: this class
        // already stopped VoiceCommandManager to give voiceAgentClient exclusive mic ownership
        // (see this class's doc), and already exposes an equivalent stop_navigation tool. Without
        // this, NavigationController.start() reopened that singleton's own mic underneath Pro
        // Mode's, and NAVI's own spoken turn instructions got picked up by the Voice Agent's mic
        // and answered as if the user had said them.
        RouteNavigationService.start(context, routeName, listenForVoiceCommands = false)
        return navigationStartedMessage(routeName, language)
    }

    private fun stopNavigation(language: AnnouncementLanguage): String {
        RouteNavigationService.stop(context)
        return navigationStoppedMessage(language)
    }

    private suspend fun listSavedRoutes(language: AnnouncementLanguage): String {
        val routes = routeRepository.observeRoutes().first()
        if (routes.isEmpty()) return noRoutesMessage(language)
        return routes.joinToString(", ") { it.name }
    }

    private suspend fun renameRoute(oldName: String, newName: String, language: AnnouncementLanguage): String {
        if (oldName.isBlank() || newName.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        val renamed = routeRepository.renameRouteByName(oldName, newName)
        if (!renamed) throw IllegalStateException(routeNotFoundMessage(oldName, language))
        return routeRenamedMessage(oldName, newName, language)
    }

    /** Same `confirmed` safety net as [clearAllMemories] -- see its doc. */
    private suspend fun deleteRoute(name: String, confirmed: Boolean, language: AnnouncementLanguage): String {
        if (name.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        if (!confirmed) return confirmationRequiredMessage(language)
        val route = routeRepository.getRouteWithPointsByName(name) ?: throw IllegalStateException(routeNotFoundMessage(name, language))
        routeRepository.deleteRoute(route.route.id)
        return routeDeletedMessage(name, language)
    }

    private suspend fun listAllMemories(language: AnnouncementLanguage): String {
        val memories = memoryRepository.getAllOnce()
        if (memories.isEmpty()) return noMemoriesMessage(language)
        return memories.joinToString(" ") { it.content.trimEnd('.') + "." }
    }

    // ---- System prompt / messages ----

    /**
     * A single bilingual prompt, not keyed by the Settings language -- Pro
     * Mode's whole point is letting the user switch between Indonesian and
     * English mid-conversation (see the class doc's language caveat), so
     * the instruction to mirror the user's language has to be part of the
     * prompt itself rather than picked once up front.
     */
    private fun bilingualSystemPrompt(): String = """
        You are NAVI in Pro Mode (free-form conversation mode) for a blind/low-vision user.
        The user may speak either Indonesian or English, and may switch between them from one turn to the
        next. ALWAYS detect which language the user's most recent message was in and reply in that SAME
        language -- if they spoke Indonesian, answer in Indonesian; if English, answer in English. Never mix
        languages within a single reply.
        Answer briefly and naturally, one or two sentences per turn.
        Use the available tools to actually do things (look at the surroundings, read text, search for objects,
        save/recall/forget/list information, record/navigate/rename/delete/list routes) -- don't pretend to do
        them without calling the matching tool. Before calling clear_all_memories or delete_route, ALWAYS ask
        the user to explicitly confirm first, in their own language. If the user wants to stop recording a
        route, start navigation, rename, or delete a route but hasn't given a route name yet, ask for one
        first, in their own language. If a tool reports an item wasn't found (e.g. no route with that name),
        say so plainly rather than pretending it worked.
        When the user indicates they're done with this conversation or want to leave Pro Mode -- however they
        phrase it, not just an exact "exit" -- say a brief goodbye in their own language, THEN call
        exit_pro_mode. Don't call it mid-sentence before you've actually said goodbye.

        Kamu adalah NAVI dalam Mode Pro (mode percakapan bebas) untuk pengguna tunanetra/low-vision. Pengguna
        boleh berbicara dalam Bahasa Indonesia atau Inggris, dan boleh berganti bahasa kapan saja. SELALU
        kenali bahasa yang baru saja dipakai pengguna dan jawab dalam bahasa yang SAMA -- jangan mencampur dua
        bahasa dalam satu jawaban. Sebelum menghapus rute atau menghapus semua ingatan, selalu minta konfirmasi
        eksplisit dulu. Saat pengguna menunjukkan sudah selesai atau ingin keluar dari Mode Pro -- dengan kata
        apa pun -- ucapkan salam perpisahan singkat dalam bahasa mereka DULU, baru panggil exit_pro_mode.
    """.trimIndent()

    private fun greetingMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Mode Pro aktif. Silakan bicara bebas, saya mendengarkan."
        AnnouncementLanguage.ENGLISH -> "Pro Mode is active. Go ahead and talk freely, I'm listening."
    }

    private fun enteringMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Masuk ke Mode Pro."
        AnnouncementLanguage.ENGLISH -> "Entering Pro Mode."
    }

    private fun exitingMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Keluar dari Mode Pro."
        AnnouncementLanguage.ENGLISH -> "Exiting Pro Mode."
    }

    private fun enteringLabel(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Jelaskan lingkungan saya"
        AnnouncementLanguage.ENGLISH -> "Describe my surroundings"
    }

    private fun readTextLabel(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Bacakan tulisan ini"
        AnnouncementLanguage.ENGLISH -> "Read this text"
    }

    private fun offlineMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Mode Pro butuh internet dan tidak tersedia saat Mode Offline aktif."
        AnnouncementLanguage.ENGLISH -> "Pro Mode needs the internet and isn't available while Offline Mode is on."
    }

    private fun missingApiKeyMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Tambahkan kunci API AssemblyAI di Pengaturan untuk memakai Mode Pro."
        AnnouncementLanguage.ENGLISH -> "Add your AssemblyAI API key in Settings to use Pro Mode."
    }

    private fun missingMicPermissionMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Izinkan akses mikrofon untuk memakai Mode Pro."
        AnnouncementLanguage.ENGLISH -> "Grant microphone access to use Pro Mode."
    }

    private fun missingLlmConfigMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Atur endpoint LLM di Pengaturan dulu untuk memakai fitur ini."
        AnnouncementLanguage.ENGLISH -> "Set up an LLM endpoint in Settings first to use this feature."
    }

    private fun noFrameMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Belum ada gambar kamera untuk dianalisis."
        AnnouncementLanguage.ENGLISH -> "No camera frame available to analyze yet."
    }

    private fun missingArgumentMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Informasi yang diberikan tidak lengkap."
        AnnouncementLanguage.ENGLISH -> "The information given was incomplete."
    }

    private fun memorySavedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Baik, saya akan mengingatnya."
        AnnouncementLanguage.ENGLISH -> "Okay, I'll remember that."
    }

    private fun memoryNotFoundMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Saya tidak menemukan ingatan itu."
        AnnouncementLanguage.ENGLISH -> "I couldn't find that memory."
    }

    private fun memoryDeletedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Informasi itu sudah dihapus."
        AnnouncementLanguage.ENGLISH -> "That information has been deleted."
    }

    private fun allMemoriesClearedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Semua ingatan telah dihapus."
        AnnouncementLanguage.ENGLISH -> "All memories have been deleted."
    }

    /** Returned instead of performing a destructive action when `confirmed` wasn't true -- see ProModeTools' "Natural confirmation" doc. */
    private fun confirmationRequiredMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Aksi ini belum dilakukan karena belum ada konfirmasi yang jelas dari pengguna."
        AnnouncementLanguage.ENGLISH -> "This action was not performed because the user hasn't clearly confirmed it yet."
    }

    private fun locationPermissionMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Izin lokasi belum diberikan, tidak bisa merekam rute."
        AnnouncementLanguage.ENGLISH -> "Location permission hasn't been granted, can't record a route."
    }

    private fun recordingStartedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Mulai merekam rute."
        AnnouncementLanguage.ENGLISH -> "Started recording the route."
    }

    private fun nothingRecordedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Belum ada rute yang sedang direkam."
        AnnouncementLanguage.ENGLISH -> "No route is currently being recorded."
    }

    private fun defaultRouteName(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute"
        AnnouncementLanguage.ENGLISH -> "Route"
    }

    private fun routeSavedMessage(name: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute disimpan dengan nama $name."
        AnnouncementLanguage.ENGLISH -> "Route saved as $name."
    }

    private fun routeNotFoundMessage(name: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute bernama $name tidak ditemukan."
        AnnouncementLanguage.ENGLISH -> "I couldn't find a route named $name."
    }

    private fun navigationStartedMessage(name: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Memulai navigasi ke $name."
        AnnouncementLanguage.ENGLISH -> "Starting navigation to $name."
    }

    private fun navigationStoppedMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Navigasi dihentikan."
        AnnouncementLanguage.ENGLISH -> "Navigation stopped."
    }

    private fun noRoutesMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Belum ada rute yang disimpan."
        AnnouncementLanguage.ENGLISH -> "No routes have been saved yet."
    }

    private fun routeRenamedMessage(oldName: String, newName: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute $oldName sudah diganti nama menjadi $newName."
        AnnouncementLanguage.ENGLISH -> "Route $oldName has been renamed to $newName."
    }

    private fun routeDeletedMessage(name: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute $name telah dihapus."
        AnnouncementLanguage.ENGLISH -> "Route $name has been deleted."
    }

    private fun noMemoriesMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Saya belum menyimpan ingatan apa pun."
        AnnouncementLanguage.ENGLISH -> "I don't have any memories saved yet."
    }

    override fun onCleared() {
        voiceAgentClient.disconnect()
        super.onCleared()
    }

    private companion object {
        const val TAG = "ProModeViewModel"

        /** Caps [ProModeUiState.debugLog] so a long session's trace doesn't grow unbounded in memory. */
        const val MAX_DEBUG_LOG_LINES = 300

        /** See [autoRestartAttempts]'s doc -- how many consecutive self-ended sessions this reconnects before giving up. */
        const val MAX_AUTO_RESTART_ATTEMPTS = 3

        /** See [attemptAutoRestart]'s doc -- fixed pause before each auto-restart attempt. */
        const val AUTO_RESTART_DELAY_MS = 1500L

        /** See [goHome]/[exitProMode]'s docs -- buffer between releasing this class's own mic (AudioRecord, via [voiceAgentClient]) and navigating back to whichever screen reclaims it next. */
        const val MIC_HANDOFF_DELAY_MS = 300L

        /** Same per-track cooldown as [DetectionViewModel]'s own `HAZARD_COOLDOWN_MS` -- see [checkForHazard]'s doc. */
        const val HAZARD_COOLDOWN_MS = 15_000L
    }
}
