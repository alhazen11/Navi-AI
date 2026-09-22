package com.apps.naviai.llm

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to a user-configured OpenAI-compatible chat completions endpoint --
 * Ollama's built-in `/v1` API, LM Studio, OpenRouter, Groq, or any other
 * server implementing the same `/models` + `/chat/completions` shape. Not
 * wired into any feature yet (see [com.apps.naviai.domain.model.AppSettings]
 * doc); this only lets Settings verify a configured endpoint is reachable
 * before it's relied on elsewhere.
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

    sealed interface TestResult {
        data class Success(val message: String) : TestResult
        data class Failure(val message: String) : TestResult
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

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
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
            TestResult.Failure(t.message ?: "Network error")
        } catch (t: Exception) {
            TestResult.Failure(t.message ?: "Unexpected error")
        }
    }

    /** Parses the OpenAI-compatible `{"data": [{"id": "..."}, ...]}` model list shape. */
    private fun extractModelIds(bodyText: String): List<String> {
        val data = JSONObject(bodyText).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).getString("id") }
    }
}
