package com.apps.naviai.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.apps.naviai.audio.AnnouncementLanguage
import com.apps.naviai.data.local.settingsDataStore
import com.apps.naviai.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persists [AppSettings] to DataStore Preferences, one key per field. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
        val IOU_THRESHOLD = floatPreferencesKey("iou_threshold")
        val MODEL_INPUT_RESOLUTION = intPreferencesKey("model_input_resolution")
        val USE_VULKAN = booleanPreferencesKey("use_vulkan")
        val ENABLE_DISTANCE = booleanPreferencesKey("enable_distance_estimation")
        val ENABLE_TRACKING = booleanPreferencesKey("enable_tracking")
        val ENABLE_VOICE = booleanPreferencesKey("enable_voice_assistance")
        val SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val ANNOUNCEMENT_INTERVAL_MS = longPreferencesKey("announcement_interval_ms")
        val RISK_SENSITIVITY = floatPreferencesKey("risk_sensitivity")
        val CAMERA_LENS_FACING = intPreferencesKey("camera_lens_facing")
        val ASSEMBLYAI_API_KEY = stringPreferencesKey("assemblyai_api_key")
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val OFFLINE_MODE_ENABLED = booleanPreferencesKey("offline_mode_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            confidenceThreshold = prefs[Keys.CONFIDENCE_THRESHOLD] ?: defaults.confidenceThreshold,
            iouThreshold = prefs[Keys.IOU_THRESHOLD] ?: defaults.iouThreshold,
            modelInputResolution = prefs[Keys.MODEL_INPUT_RESOLUTION] ?: defaults.modelInputResolution,
            useVulkan = prefs[Keys.USE_VULKAN] ?: defaults.useVulkan,
            enableDistanceEstimation = prefs[Keys.ENABLE_DISTANCE] ?: defaults.enableDistanceEstimation,
            enableTracking = prefs[Keys.ENABLE_TRACKING] ?: defaults.enableTracking,
            enableVoiceAssistance = prefs[Keys.ENABLE_VOICE] ?: defaults.enableVoiceAssistance,
            speechLanguage = prefs[Keys.SPEECH_LANGUAGE]?.let { runCatching { AnnouncementLanguage.valueOf(it) }.getOrNull() }
                ?: defaults.speechLanguage,
            speechRate = prefs[Keys.SPEECH_RATE] ?: defaults.speechRate,
            announcementIntervalMs = prefs[Keys.ANNOUNCEMENT_INTERVAL_MS] ?: defaults.announcementIntervalMs,
            riskSensitivity = prefs[Keys.RISK_SENSITIVITY] ?: defaults.riskSensitivity,
            cameraLensFacing = prefs[Keys.CAMERA_LENS_FACING] ?: defaults.cameraLensFacing,
            assemblyAiApiKey = prefs[Keys.ASSEMBLYAI_API_KEY] ?: defaults.assemblyAiApiKey,
            llmBaseUrl = prefs[Keys.LLM_BASE_URL] ?: defaults.llmBaseUrl,
            llmApiKey = prefs[Keys.LLM_API_KEY] ?: defaults.llmApiKey,
            llmModel = prefs[Keys.LLM_MODEL] ?: defaults.llmModel,
            offlineModeEnabled = prefs[Keys.OFFLINE_MODE_ENABLED] ?: defaults.offlineModeEnabled
        )
    }

    suspend fun setConfidenceThreshold(value: Float) = update { it[Keys.CONFIDENCE_THRESHOLD] = value.coerceIn(0.05f, 0.95f) }
    suspend fun setIouThreshold(value: Float) = update { it[Keys.IOU_THRESHOLD] = value.coerceIn(0.05f, 0.95f) }
    suspend fun setModelInputResolution(value: Int) = update { it[Keys.MODEL_INPUT_RESOLUTION] = value }
    suspend fun setUseVulkan(value: Boolean) = update { it[Keys.USE_VULKAN] = value }
    suspend fun setEnableDistanceEstimation(value: Boolean) = update { it[Keys.ENABLE_DISTANCE] = value }
    suspend fun setEnableTracking(value: Boolean) = update { it[Keys.ENABLE_TRACKING] = value }
    suspend fun setEnableVoiceAssistance(value: Boolean) = update { it[Keys.ENABLE_VOICE] = value }
    suspend fun setSpeechLanguage(value: AnnouncementLanguage) = update { it[Keys.SPEECH_LANGUAGE] = value.name }
    suspend fun setSpeechRate(value: Float) = update { it[Keys.SPEECH_RATE] = value.coerceIn(0.5f, 2.0f) }
    suspend fun setAnnouncementIntervalMs(value: Long) = update { it[Keys.ANNOUNCEMENT_INTERVAL_MS] = value.coerceIn(500L, 15000L) }
    suspend fun setRiskSensitivity(value: Float) = update { it[Keys.RISK_SENSITIVITY] = value.coerceIn(0.5f, 2.0f) }
    suspend fun setCameraLensFacing(value: Int) = update { it[Keys.CAMERA_LENS_FACING] = value }
    suspend fun setAssemblyAiApiKey(value: String?) = update {
        if (value.isNullOrBlank()) it.remove(Keys.ASSEMBLYAI_API_KEY) else it[Keys.ASSEMBLYAI_API_KEY] = value.trim()
    }
    suspend fun setLlmBaseUrl(value: String?) = update {
        if (value.isNullOrBlank()) it.remove(Keys.LLM_BASE_URL) else it[Keys.LLM_BASE_URL] = value.trim().trimEnd('/')
    }
    suspend fun setLlmApiKey(value: String?) = update {
        if (value.isNullOrBlank()) it.remove(Keys.LLM_API_KEY) else it[Keys.LLM_API_KEY] = value.trim()
    }
    suspend fun setLlmModel(value: String?) = update {
        if (value.isNullOrBlank()) it.remove(Keys.LLM_MODEL) else it[Keys.LLM_MODEL] = value.trim()
    }
    suspend fun setOfflineModeEnabled(value: Boolean) = update { it[Keys.OFFLINE_MODE_ENABLED] = value }

    suspend fun resetToDefaults() {
        context.settingsDataStore.edit { it.clear() }
    }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
