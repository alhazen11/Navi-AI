package com.apps.naviai.llm

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to a user-configured OpenAI-compatible chat completions endpoint --
 * Ollama's built-in `/v1` API, LM Studio, OpenRouter, Groq, or any other
 * server implementing the same `/models` + `/chat/completions` shape.
 */
@Singleton
class LlmClient @Inject constructor(httpClient: OkHttpClient) {

    // The app-wide OkHttpClient has an unbounded read timeout (tuned for the
    // voice command WebSocket/polling); a "Test connection" button in the UI
    // must not be able to hang indefinitely, so this uses its own bounded
    // client instead of the shared instance.
    private val client = httpClient.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    // A vision chat completion can be slow, especially a local Ollama server
    // running a vision model on CPU -- 10s (the connectivity-check timeout
    // above) would false-fail a real, working, just-slow setup.
    private val chatClient = httpClient.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed interface TestResult {
        data class Success(val message: String) : TestResult
        data class Failure(val message: String) : TestResult
    }

    sealed interface ChatResult {
        data class Success(val text: String) : ChatResult
        data class Failure(val message: String) : ChatResult
    }

    /**
     * Hits `{baseUrl}/models` (the standard OpenAI-compatible model listing
     * endpoint) as a lightweight reachability + auth check. Does not send
     * any chat request. Must be called off the main thread.
     */
    fun testConnection(baseUrl: String, apiKey: String?, model: String?): TestResult {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        if (normalizedBaseUrl.isBlank()) return TestResult.Failure("Base URL is empty")

        val requestBuilder = Request.Builder().url("$normalizedBaseUrl/models")
        if (!apiKey.isNullOrBlank()) requestBuilder.addHeader("Authorization", "Bearer $apiKey")

        Log.d(TAG, "testConnection request: GET $normalizedBaseUrl/models (apiKey=${if (apiKey.isNullOrBlank()) "none" else "set"})")

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                Log.d(TAG, "testConnection response: HTTP ${response.code}, body=${bodyText.take(LOG_BODY_MAX_CHARS)}")
                if (!response.isSuccessful) {
                    return TestResult.Failure("HTTP ${response.code}: ${bodyText.take(200).ifBlank { "no response body" }}")
                }

                val modelIds = runCatching { extractModelIds(bodyText) }.getOrNull()
                when {
                    modelIds == null -> TestResult.Success("Connected (response wasn't a recognizable model list, but the endpoint responded OK)")
                    !model.isNullOrBlank() && modelIds.none { it.equals(model, ignoreCase = true) } ->
                        TestResult.Success("Connected, but \"$model\" wasn't in the ${modelIds.size} model(s) this endpoint reported")
                    else -> TestResult.Success("Connected -- ${modelIds.size} model(s) available")
                }
            }
        } catch (t: IOException) {
            Log.w(TAG, "testConnection failed", t)
            TestResult.Failure(t.message ?: "Network error")
        } catch (t: Exception) {
            Log.w(TAG, "testConnection failed", t)
            TestResult.Failure(t.message ?: "Unexpected error")
        }
    }

    /** Parses the OpenAI-compatible `{"data": [{"id": "..."}, ...]}` model list shape. */
    private fun extractModelIds(bodyText: String): List<String> {
        val data = JSONObject(bodyText).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).getString("id") }
    }

    /**
     * Sends [prompt] plus [jpeg] (a single image) to `{baseUrl}/chat/completions`
     * using the OpenAI-compatible vision chat format (a `content` array mixing
     * a text part and an `image_url` data-URI part) and returns the model's
     * reply text. Requires a vision-capable model (e.g. Ollama's `llava`,
     * `minicpm-v`, `bakllava`, or a hosted multimodal model) -- a text-only
     * model will either error or ignore the image depending on the server.
     * Must be called off the main thread.
     */
    fun chatWithImage(baseUrl: String, apiKey: String?, model: String, prompt: String, jpeg: ByteArray): ChatResult {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        if (normalizedBaseUrl.isBlank()) return ChatResult.Failure("Base URL is empty")
        if (model.isBlank()) return ChatResult.Failure("Model is empty")

        val base64Image = Base64.getEncoder().encodeToString(jpeg)
        val payload = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                                .put(
                                    JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
                                    }
                                )
                        )
                    }
                )
            )
            put("max_tokens", 10000)
            put("temperature", 0.2)
        }

        val requestBuilder = Request.Builder()
            .url("$normalizedBaseUrl/chat/completions")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (!apiKey.isNullOrBlank()) requestBuilder.addHeader("Authorization", "Bearer $apiKey")

        // The base64 image data URI is deliberately never logged -- it can
        // be megabytes, way past logcat's per-line limit, and useless to
        // read anyway; imageBytes/promptLength are the useful signal.
        Log.d(
            TAG,
            "chatWithImage request: POST $normalizedBaseUrl/chat/completions " +
                "model=$model imageBytes=${jpeg.size} apiKey=${if (apiKey.isNullOrBlank()) "none" else "set"}\nprompt:\n$prompt"
        )

        return try {
            chatClient.newCall(requestBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                Log.d(TAG, "chatWithImage response: HTTP ${response.code}, body=${bodyText.take(LOG_BODY_MAX_CHARS)}")
                if (!response.isSuccessful) {
                    return ChatResult.Failure("HTTP ${response.code}: ${bodyText.take(200).ifBlank { "no response body" }}")
                }
                val text = JSONObject(bodyText)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                if (text.isBlank()) ChatResult.Failure("Empty response from model") else ChatResult.Success(text)
            }
        } catch (t: IOException) {
            Log.w(TAG, "chatWithImage failed", t)
            ChatResult.Failure(t.message ?: "Network error")
        } catch (t: Exception) {
            Log.w(TAG, "chatWithImage failed", t)
            ChatResult.Failure(t.message ?: "Unexpected error")
        }
    }

    private companion object {
        const val TAG = "LlmClient"

        /** Caps how much of a response body gets logged -- an error page from a misconfigured endpoint could be large HTML, not just a small JSON error. */
        const val LOG_BODY_MAX_CHARS = 2000
    }
}
