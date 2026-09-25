package com.apps.naviai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.audio.TextToSpeechManager
import com.apps.naviai.audio.VoiceCommandManager
import com.apps.naviai.camera.FrameAnalyzer
import com.apps.naviai.camera.ImageUtils
import com.apps.naviai.core.common.InferenceDispatcher
import com.apps.naviai.core.common.IoDispatcher
import com.apps.naviai.database.RouteRepository
import com.apps.naviai.detection.detector.FrameInput
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.domain.model.AppSettings
import com.apps.naviai.llm.LlmClient
import com.apps.naviai.location.LocationPermission
import com.apps.naviai.memory.ConversationMemoryRepository
import com.apps.naviai.memory.MemoryCategoryClassifier
import com.apps.naviai.memory.MemoryImportanceClassifier
import com.apps.naviai.memory.MemorySearch
import com.apps.naviai.recording.RouteRecorder
import com.apps.naviai.routenav.RouteNavigationService
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
     * True while NAVI's own reply is actually playing through
     * [VoiceAgentClient]'s internal `AudioTrack` (see
     * [VoiceAgentEvent.AgentAudioStarted]/[VoiceAgentEvent.AgentAudioStopped])
     * -- surfaced so the UI can show a "Speaking…" state instead of
     * implying the agent is idly listening. Despite the name, the mic
     * itself is muted during this window only as a fallback on devices
     * without echo cancellation (see [ProModeViewModel.bargeInSupported])
     * -- on a device that supports real barge-in, the mic stays open the
     * whole time and the user can interrupt NAVI mid-reply.
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
    val debugLog: List<String> = emptyList()
)

/**
 * Pro Mode (Conversation Mode): a full-duplex, multi-turn conversation with
 * NAVI over [VoiceAgentClient], replacing the regular wake-word-gated
 * pipeline's keyword/regex command matchers with LLM tool-calling (see
 * [ProModeTools] for the schema, and this class's tool handlers below for
 * what each one actually does).
 *
 * Deliberately does NOT run the regular object-detection/tracking/risk
 * pipeline or its automatic hazard/announcement TTS -- that would talk over
 * the agent's own audio through the same speaker. Instead, this only keeps
 * the single most recent camera frame around (via [createFrameAnalyzer], no
 * ML inference) so the vision-backed tools (`describe_surroundings`,
 * `read_text`, `search_object`) have something to send the vision LLM.
 * Continuous safety announcements resume automatically once the user exits
 * back to the regular Detection screen -- see this class's exit handling
 * and [com.apps.naviai.ui.screens.DetectionScreen]'s own mic-restart logic.
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
 * **Bilingual (Indonesian + English) caveat**: [connectIfReady] always sends
 * `language_codes = ["id", "en"]` and the system prompt instructs the agent
 * to answer in whichever language the user just spoke, regardless of the
 * app's Settings language -- Pro Mode is meant to let the user switch
 * languages mid-conversation ("code-switching"). AssemblyAI's own published
 * language list for the *full* speech-to-speech Voice Agent pipeline (as of
 * this writing) explicitly names English/Spanish/French/German/Italian/
 * Portuguese with native code-switching; Indonesian is not on that list,
 * even though AssemblyAI's underlying speech-to-text models (and this
 * app's own [com.apps.naviai.audio.AssemblyAiBatchTranscriber], used
 * outside Pro Mode) do support Indonesian. In practice this means:
 * Indonesian recognition and the agent's own spoken Indonesian voice
 * output (played directly by [VoiceAgentClient], see its class doc) are a
 * best-effort attempt, not a guarantee. If Indonesian recognition/voice
 * quality turns out to be poor in practice, the fallback is the regular
 * (non-Pro-Mode) voice command pipeline, which has no such limitation.
 */
@HiltViewModel
class ProModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceAgentClient: VoiceAgentClient,
    private val voiceCommandManager: VoiceCommandManager,
    /** Used ONLY for the enter/exit navigation announcements below -- never for the agent's own conversational replies, which VoiceAgentClient plays directly from AssemblyAI (see its class doc). */
    private val ttsManager: TextToSpeechManager,
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient,
    private val routeRecorder: RouteRecorder,
    private val routeRepository: RouteRepository,
    private val memoryRepository: ConversationMemoryRepository,
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

    init {
        // Only one engine may hold the mic at a time -- see VoiceAgentClient's
        // class doc. The regular wake-word listener restarts on its own once
        // the user is back on Detection (its VoiceCommandPanel re-runs its
        // start-listening effect on every fresh composition).
        voiceCommandManager.stopListening()

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                settingsLoaded = true
                connectIfReady()
            }
        }
    }

    fun createFrameAnalyzer(mirror: Boolean): FrameAnalyzer =
        FrameAnalyzer(viewModelScope, inferenceDispatcher, mirror) { frame -> latestFrame = frame }

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

    /**
     * True when this device supports [VoiceAgentClient.isEchoCancellationAvailable]
     * -- decided once, right before [VoiceAgentClient.connect], and used for
     * the whole session. When true, [handleAgentEvent] never mutes the mic
     * on [VoiceAgentEvent.AgentAudioStarted] (real barge-in: the user can
     * interrupt NAVI mid-reply -- [VoiceAgentClient] itself flushes the
     * still-playing reply audio as soon as the server reports
     * `input.speech.started`, see its class doc); when false, the mic is
     * muted for the whole [VoiceAgentEvent.AgentAudioStarted]/
     * [VoiceAgentEvent.AgentAudioStopped] window instead, since without echo
     * cancellation an open mic during playback would likely feed NAVI's own
     * voice back to the server as if it were the user talking.
     */
    private var bargeInSupported = false

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

        bargeInSupported = voiceAgentClient.isEchoCancellationAvailable()
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
            VoiceAgentEvent.Ready -> _uiState.update { it.copy(status = ProModeConnectionStatus.Ready) }
            VoiceAgentEvent.UserSpeechStarted -> {
                // Real barge-in: VoiceAgentClient itself already flushed any
                // agent audio still playing/queued (see its class doc and
                // AgentAudioStopped below, which fires as a result) before
                // this event ever reaches here -- this only needs to reset
                // the "thinking"/tool-label UI state for the new turn.
                _uiState.update { it.copy(isProcessing = false, activeToolLabel = null) }
            }
            VoiceAgentEvent.UserSpeechStopped -> _uiState.update { it.copy(isProcessing = true) }
            is VoiceAgentEvent.UserTranscript -> {
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
                // Text only, for the on-screen transcript -- the agent's actual voice
                // is played directly by VoiceAgentClient (see AgentAudioStarted/Stopped
                // below), not spoken through ttsManager.
                appendTranscript(TranscriptSpeaker.AGENT, event.text)
                _uiState.update { it.copy(activeToolLabel = null, isProcessing = false) }
            }
            // The real "NAVI is speaking" signal, driven by actual AudioTrack playback in
            // VoiceAgentClient rather than a text-length estimate -- mic muting here is only
            // the fallback for devices without echo cancellation (see bargeInSupported's doc);
            // on a barge-in-capable device this still updates the UI's "Speaking…" state even
            // though the mic itself is never muted.
            VoiceAgentEvent.AgentAudioStarted -> {
                if (!bargeInSupported) voiceAgentClient.setMicMuted(true)
                _uiState.update { it.copy(isMicMuted = true) }
            }
            VoiceAgentEvent.AgentAudioStopped -> {
                if (!bargeInSupported) voiceAgentClient.setMicMuted(false)
                _uiState.update { it.copy(isMicMuted = false) }
            }
            is VoiceAgentEvent.ToolInvoked -> _uiState.update { it.copy(activeToolLabel = event.name, isProcessing = true) }
            // Authoritative turn-over signal (see VoiceAgentEvent.ReplyDone's doc) -- always returns to a
            // listening-ready state even if AgentTranscript never fired or its text failed to parse for this turn.
            VoiceAgentEvent.ReplyDone -> _uiState.update { it.copy(activeToolLabel = null, isProcessing = false) }
            is VoiceAgentEvent.Error -> {
                _uiState.update { it.copy(status = ProModeConnectionStatus.Error(event.message), isProcessing = false) }
            }
            VoiceAgentEvent.Ended -> _uiState.update { it.copy(status = ProModeConnectionStatus.Ended, activeToolLabel = null, isProcessing = false) }
            is VoiceAgentEvent.DebugLog -> _uiState.update {
                it.copy(debugLog = (it.debugLog + event.line).takeLast(MAX_DEBUG_LOG_LINES))
            }
        }
    }

    private fun appendTranscript(speaker: TranscriptSpeaker, text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(transcript = it.transcript + TranscriptEntry(speaker, text)) }
    }

    /** Voice-triggered exit ("NAVI, matikan mode pro") -- see [ProModeCommandMatcher.isExit]. The screen's own back button reaches the same disconnect via [onScreenClosed]. */
    private fun exitProMode() {
        ttsManager.speak(exitingMessage(currentSettings.speechLanguage), flushQueue = true, utteranceId = "pro_mode_exit")
        voiceAgentClient.disconnect()
        _exitEvents.tryEmit(Unit)
    }

    /** Called from the screen's `onDispose` (back button, or navigating away any other way). */
    fun onScreenClosed() {
        voiceAgentClient.disconnect()
    }

    // ---- Tool dispatch: replaces the regular VoiceCommandParser + per-feature matchers with LLM tool-calling ----

    private fun registerTools(language: AnnouncementLanguage) {
        voiceAgentClient.registerTool("describe_surroundings") { describeSurroundings(language) }
        voiceAgentClient.registerTool("read_text") { readText(language) }
        voiceAgentClient.registerTool("search_object") { args -> searchObject(args.optString("query"), language) }
        voiceAgentClient.registerTool("save_memory") { args -> saveMemory(args.optString("content"), language) }
        voiceAgentClient.registerTool("recall_memory") { args -> recallMemory(args.optString("query"), language) }
        voiceAgentClient.registerTool("forget_memory") { args -> forgetMemory(args.optString("query"), language) }
        voiceAgentClient.registerTool("clear_all_memories") { args -> clearAllMemories(args.optBoolean("confirmed", false), language) }
        voiceAgentClient.registerTool("start_route_recording") { startRouteRecording(language) }
        voiceAgentClient.registerTool("stop_route_recording") { args -> stopRouteRecording(args.optString("name"), language) }
        voiceAgentClient.registerTool("start_navigation") { args -> startNavigation(args.optString("route_name"), language) }
        voiceAgentClient.registerTool("stop_navigation") { stopNavigation(language) }
        voiceAgentClient.registerTool("list_saved_routes") { listSavedRoutes(language) }
        voiceAgentClient.registerTool("rename_route") { args -> renameRoute(args.optString("old_name"), args.optString("new_name"), language) }
        voiceAgentClient.registerTool("delete_route") { args -> deleteRoute(args.optString("name"), args.optBoolean("confirmed", false), language) }
        voiceAgentClient.registerTool("list_all_memories") { listAllMemories(language) }
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

    private suspend fun describeSurroundings(language: AnnouncementLanguage): String {
        val (baseUrl, apiKey, model) = requireLlmConfig(language)
        val (_, jpeg) = currentJpegOrThrow()
        val prompt = ScenePromptBuilder.build(SceneContext(jpeg, emptyList(), RiskLevel.SAFE, enteringLabel(language)), language)
        return chatOrThrow(baseUrl, apiKey, model, prompt, jpeg)
    }

    private suspend fun readText(language: AnnouncementLanguage): String {
        val (baseUrl, apiKey, model) = requireLlmConfig(language)
        val (_, jpeg) = currentJpegOrThrow(quality = 92)
        val prompt = TextReadingPromptBuilder.build(readTextLabel(language), language)
        return chatOrThrow(baseUrl, apiKey, model, prompt, jpeg)
    }

    private suspend fun searchObject(query: String, language: AnnouncementLanguage): String {
        if (query.isBlank()) throw IllegalStateException(missingArgumentMessage(language))
        val (baseUrl, apiKey, model) = requireLlmConfig(language)
        val (_, jpeg) = currentJpegOrThrow()
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
        RouteNavigationService.start(context, routeName)
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

        Kamu adalah NAVI dalam Mode Pro (mode percakapan bebas) untuk pengguna tunanetra/low-vision. Pengguna
        boleh berbicara dalam Bahasa Indonesia atau Inggris, dan boleh berganti bahasa kapan saja. SELALU
        kenali bahasa yang baru saja dipakai pengguna dan jawab dalam bahasa yang SAMA -- jangan mencampur dua
        bahasa dalam satu jawaban. Sebelum menghapus rute atau menghapus semua ingatan, selalu minta konfirmasi
        eksplisit dulu.
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
        /** Caps [ProModeUiState.debugLog] so a long session's trace doesn't grow unbounded in memory. */
        const val MAX_DEBUG_LOG_LINES = 300
    }
}
