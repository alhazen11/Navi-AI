package com.apps.naviai.domain.model

import androidx.camera.core.CameraSelector
import com.apps.naviai.audio.AnnouncementLanguage

/**
 * All user-configurable behavior in one place, persisted via
 * [com.apps.naviai.settings.SettingsRepository] (DataStore Preferences).
 *
 * [modelInputResolution] is informational only for the model bundled with
 * this build: yolo_model.ncnn.param was exported at a fixed 320x320 and its
 * anchor/stride constants are baked for that size, so changing this value
 * has no effect unless a differently-exported model is swapped in (see
 * scripts/export_yolo_to_ncnn.py).
 */
data class AppSettings(
    val confidenceThreshold: Float = 0.45f,
    val iouThreshold: Float = 0.45f,
    val modelInputResolution: Int = 320,
    val useVulkan: Boolean = false,
    val enableDistanceEstimation: Boolean = true,
    val enableTracking: Boolean = true,
    val enableVoiceAssistance: Boolean = true,
    val speechLanguage: AnnouncementLanguage = AnnouncementLanguage.INDONESIAN,
    val speechRate: Float = 1.0f,
    val announcementIntervalMs: Long = 4000L,
    val riskSensitivity: Float = 1.0f,
    val cameraLensFacing: Int = CameraSelector.LENS_FACING_BACK,
    /**
     * User-supplied AssemblyAI API key for the voice command feature's
     * speech-to-text (see audio/AssemblyAiBatchTranscriber.kt).
     * Null/blank until the user pastes their own key in Settings -- there is
     * no bundled key. Stored in plain DataStore Preferences like every other
     * setting here, not a secure keystore; treat it as a locally-scoped
     * convenience, not a secret vault.
     */
    val assemblyAiApiKey: String? = null,
    /**
     * Base URL of an OpenAI-compatible chat completions API (see
     * llm/LlmClient.kt) -- e.g. Ollama's built-in `/v1` endpoint
     * (`http://<host>:11434/v1`), LM Studio, OpenRouter, Groq, or any other
     * server implementing the same `/chat/completions` + `/models` shape.
     * Not used by any feature yet; this is config plumbing for a future
     * command-dispatch step. Null/blank until the user fills it in.
     */
    val llmBaseUrl: String? = null,
    /** Optional -- a local Ollama server typically needs no key; hosted providers do. */
    val llmApiKey: String? = null,
    /** Model name as the endpoint expects it, e.g. "llama3.2" for Ollama or "gpt-4o-mini" for OpenAI. */
    val llmModel: String? = null
)
