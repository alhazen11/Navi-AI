package com.apps.naviai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.audio.VoiceCommandManager
import com.apps.naviai.audio.VoiceCommandUiState
import com.apps.naviai.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceCommandViewModel @Inject constructor(
    private val voiceCommandManager: VoiceCommandManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<VoiceCommandUiState> = voiceCommandManager.state

    @Volatile private var currentLanguage: AnnouncementLanguage = AnnouncementLanguage.INDONESIAN
    @Volatile private var currentApiKey: String? = null
    @Volatile private var currentOfflineModeEnabled: Boolean = false

    /**
     * True once the UI has asked to listen (auto-start on permission grant,
     * or a manual resume tap). Settings load from DataStore asynchronously,
     * so the very first [startListening] call can race ahead of the first
     * settings emission and see [currentApiKey] still null even though a
     * key is saved -- that used to leave the manager stuck on UNAVAILABLE
     * until the user visited Settings and came back (which happened to
     * retrigger this). Re-driving [startListening] on every subsequent
     * settings emission, while this flag is set, fixes that: once the real
     * key arrives, listening starts without any extra user action.
     */
    @Volatile private var listeningRequested = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentLanguage = settings.speechLanguage
                currentApiKey = settings.assemblyAiApiKey
                currentOfflineModeEnabled = settings.offlineModeEnabled
                if (listeningRequested) startListening()
            }
        }
    }

    /** Starts (or resumes) always-on listening. Must be called from the main thread (Compose event handlers already are). */
    fun startListening() {
        listeningRequested = true
        voiceCommandManager.startContinuousListening(currentLanguage, currentApiKey, currentOfflineModeEnabled)
    }

    /** Pauses listening; no auto-restart happens again until [startListening] is called. */
    fun stopListening() {
        listeningRequested = false
        voiceCommandManager.stopListening()
    }

    fun reset() {
        listeningRequested = false
        voiceCommandManager.reset()
    }

    override fun onCleared() {
        // Only stops this screen's in-flight listening session (closes the
        // WebSocket + mic capture) -- unlike the NCNN detector/TTS engine,
        // VoiceCommandManager's connection is cheap to reopen, so this is
        // safe to do per-screen (see DetectionViewModel's onCleared() for
        // why the same is NOT true of the detector/TTS singletons).
        voiceCommandManager.stopListening()
        super.onCleared()
    }
}
