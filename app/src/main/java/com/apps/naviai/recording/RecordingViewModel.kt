package com.apps.naviai.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.audio.TextToSpeechManager
import com.apps.naviai.audio.VoiceCommandManager
import com.apps.naviai.audio.VoiceCommandStatus
import com.apps.naviai.database.RoutePointEntity
import com.apps.naviai.database.RouteRepository
import com.apps.naviai.navigation.GoHomeCommandMatcher
import com.apps.naviai.navigation.GoHomeSignal
import com.apps.naviai.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.Idle,
    /** True while NAVI is waiting for the user to say (or type) the route's name after "stop merekam jalan". */
    val awaitingRouteName: Boolean = false,
    val lastSavedRouteName: String? = null
)

/**
 * Drives the Route Recording feature end to end: voice commands
 * ("mulai/stop merekam jalan"), the "what should I name this route?"
 * dialogue step (via [VoiceCommandManager.awaitNextRawUtterance]), and
 * persistence through [RouteRepository]. Starts [VoiceCommandManager]
 * listening in [init] as a safety net in case this screen is somehow
 * reached before it's already active elsewhere -- but deliberately does
 * NOT stop it when this ViewModel is cleared: [VoiceCommandManager] is a
 * shared, app-wide singleton, and voice commands are meant to keep
 * working regardless of which screen the user is looking at (that's the
 * whole reason this app merged its separate Home screen into
 * DetectionScreen). Stopping it here used to pause voice commands
 * *everywhere* the moment the user simply navigated away from Recording,
 * not just for this screen.
 */
@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val routeRecorder: RouteRecorder,
    private val routeRepository: RouteRepository,
    private val voiceCommandManager: VoiceCommandManager,
    private val ttsManager: TextToSpeechManager,
    private val settingsRepository: SettingsRepository,
    private val goHomeSignal: GoHomeSignal
) : ViewModel() {

    @Volatile private var language = AnnouncementLanguage.INDONESIAN
    @Volatile private var pendingPoints: List<RoutePointEntity> = emptyList()
    private var lastHandledVoiceEventId: Long = 0

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                language = settings.speechLanguage
                ttsManager.setLanguage(settings.speechLanguage)
                voiceCommandManager.startContinuousListening(settings.speechLanguage, settings.assemblyAiApiKey, settings.offlineModeEnabled)
            }
        }
        viewModelScope.launch {
            routeRecorder.status.collect { status -> _uiState.update { it.copy(status = status) } }
        }
        viewModelScope.launch {
            voiceCommandManager.state.collect { voiceState ->
                if (voiceState.status != VoiceCommandStatus.RECOGNIZED || voiceState.eventId == lastHandledVoiceEventId) return@collect
                val command = voiceState.command ?: return@collect
                lastHandledVoiceEventId = voiceState.eventId
                when {
                    // Checked first: a global reset takes priority over this screen's own commands.
                    GoHomeCommandMatcher.isMatch(command) -> goHome()
                    RouteRecordingMatcher.isStart(command) -> startRecording()
                    RouteRecordingMatcher.isStop(command) -> stopRecordingAndAskName()
                }
            }
        }
    }

    fun startRecordingManually() = startRecording()
    fun stopRecordingManually() = stopRecordingAndAskName()

    /** Fallback for when voice capture of the name fails/isn't reliable -- lets the user type it instead. */
    fun submitRouteNameManually(name: String) {
        voiceCommandManager.cancelPendingRawUtterance()
        onRouteNameCaptured(name)
    }

    fun cancelPendingRouteName() {
        voiceCommandManager.cancelPendingRawUtterance()
        pendingPoints = emptyList()
        _uiState.update { it.copy(awaitingRouteName = false) }
    }

    /**
     * "NAVI, kembali" / "kembali ke home" -- see [GoHomeCommandMatcher] and [GoHomeSignal]'s docs.
     * An in-progress recording is discarded, not saved: this is a reset command, not an alternate
     * phrasing of "stop merekam jalan" (which still asks for a name and saves). Mid name-capture
     * dialogue is cancelled the same way [cancelPendingRouteName] already does for its own Cancel
     * button, so the points already stopped-and-pending don't get saved under whatever this
     * "kembali" utterance itself would otherwise be misheard as a name for.
     */
    private fun goHome() {
        if (routeRecorder.isRecording) routeRecorder.stop()
        if (_uiState.value.awaitingRouteName) cancelPendingRouteName()
        speak(goingHomeMessage(language))
        goHomeSignal.trigger()
    }

    private fun goingHomeMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Kembali ke beranda."
        AnnouncementLanguage.ENGLISH -> "Returning to Home."
    }

    private fun startRecording() {
        if (routeRecorder.isRecording) return
        routeRecorder.start()
    }

    private fun stopRecordingAndAskName() {
        if (!routeRecorder.isRecording) return
        pendingPoints = routeRecorder.stop()
        if (pendingPoints.isEmpty()) {
            speak(emptyRouteMessage(language))
            return
        }

        _uiState.update { it.copy(awaitingRouteName = true) }
        // speakThenAwaitRawUtterance, not speak()+awaitNextRawUtterance()
        // separately: the mic must stay muted until NAVI finishes asking
        // for the name, otherwise it captures NAVI's own prompt audio as
        // if it were the answer.
        voiceCommandManager.speakThenAwaitRawUtterance(askRouteNameMessage(language)) { rawName -> onRouteNameCaptured(rawName) }
    }

    private fun onRouteNameCaptured(rawName: String) {
        _uiState.update { it.copy(awaitingRouteName = false) }
        val name = rawName.trim().replace(".","").replace(Regex(".*as "),"")
        val points = pendingPoints
        pendingPoints = emptyList()

        if (name.isBlank()) {
            speak(missingNameMessage(language))
            return
        }
        if (points.isEmpty()) return // shouldn't happen (guarded before asking), defensive no-op

        viewModelScope.launch {
            routeRepository.saveRoute(name, System.currentTimeMillis(), points)
            _uiState.update { it.copy(lastSavedRouteName = name) }
            speak(routeSavedMessage(name, language))
        }
    }

    // voiceCommandManager.speakMuted(), not ttsManager.speak() directly:
    // without muting, the always-on mic picks up NAVI's own confirmation
    // (e.g. "Rute X berhasil disimpan.") as new input the instant it's
    // spoken.
    private fun speak(message: String) = voiceCommandManager.speakMuted(message)

    private fun askRouteNameMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Sebutkan nama rute yang ingin disimpan."
        AnnouncementLanguage.ENGLISH -> "Please say the name you'd like to save this route as."
    }

    private fun routeSavedMessage(name: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Rute $name berhasil disimpan."
        AnnouncementLanguage.ENGLISH -> "Route $name saved successfully."
    }

    private fun missingNameMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Nama rute tidak terdengar, rute tidak disimpan. Anda bisa memberi nama secara manual."
        AnnouncementLanguage.ENGLISH -> "I didn't catch a route name, so it wasn't saved. You can enter a name manually."
    }

    private fun emptyRouteMessage(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Tidak ada titik GPS yang terekam, rute tidak disimpan."
        AnnouncementLanguage.ENGLISH -> "No GPS points were recorded, so nothing was saved."
    }

    // Deliberately no onCleared() override here -- see class doc for why
    // this must NOT call voiceCommandManager.stopListening().
}
