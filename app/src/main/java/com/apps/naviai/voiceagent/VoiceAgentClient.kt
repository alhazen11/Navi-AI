package com.apps.naviai.voiceagent

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * Full-duplex client for AssemblyAI's Voice Agent API -- the engine behind
 * Pro Mode (Conversation Mode). Unlike [com.apps.naviai.audio.AssemblyAiBatchTranscriber]
 * (record an utterance, upload, wait for text back), this holds a single
 * long-lived WebSocket that streams microphone audio in continuously, with
 * the agent's own LLM handling turn detection and tool calling -- no
 * wake-word gating, no local VAD, no regex command matchers.
 *
 * **The agent's own synthesized voice (`reply.audio`) is played directly**
 * through an internal [AudioTrack] (see [handleReplyAudio]/[ensureAudioTrack]) --
 * base64 PCM16 24kHz mono chunks decoded and streamed as they arrive.
 * [com.apps.naviai.ui.viewmodel.ProModeViewModel] no longer speaks replies
 * through [com.apps.naviai.audio.TextToSpeechManager]; it only shows
 * `transcript.agent`'s text in the on-screen transcript and reacts to
 * [VoiceAgentEvent.AgentAudioStarted]/[VoiceAgentEvent.AgentAudioStopped]
 * for mic-muting/UI state. An earlier version of this class deliberately
 * discarded `reply.audio` and used the app's own TTS instead, to sidestep
 * `ProModeViewModel`'s documented uncertainty around whether AssemblyAI's
 * own voice output covers Indonesian well -- that tradeoff was reversed on
 * explicit request to use AssemblyAI's full speech-to-speech pipeline
 * end-to-end; if Indonesian voice quality turns out to be poor in practice,
 * that's the thing to revisit.
 *
 * **Wire protocol** (verified against AssemblyAI's Voice Agent API docs and
 * blog posts as of this writing -- this is a new API and the protocol may
 * evolve, so treat this as a snapshot, not a permanent contract):
 * - Connect: `wss://agents.assemblyai.com/v1/ws?token=<key>` -- auth via query param, per AssemblyAI
 *   support's own reference client for this exact issue (a session sent via an `Authorization: Bearer`
 *   header instead still connected and the greeting still worked, but `input.speech.started` never once
 *   fired even with confirmed non-silent, deliberately loud microphone input -- switched to match
 *   support's example exactly rather than keep guessing at header-vs-query-param on our own). An earlier
 *   version also added `&sample_rate=...&encoding=...` here based on a DIFFERENT AssemblyAI product's
 *   convention; that was wrong for this endpoint (audio format belongs in `input.format.encoding` below,
 *   not the URL) and has been removed.
 * - First client message: `{"type":"session.update","session":{"system_prompt":...,"greeting":...,"tools":[...],
 *   "input":{"format":{"encoding":"audio/pcm"},"language_codes":[...],"turn_detection":{...}}}}` -- see
 *   [buildInlineSessionUpdate] for the exact shape and why `input.format.encoding` matters.
 * - Server confirms with `{"type":"session.ready",...}` -- only then does this class start capturing/sending audio.
 * - Audio in: `{"type":"input.audio","audio":"<base64 PCM16 mono 24kHz>"}`, sent continuously in small chunks
 *   (see [setMicMuted] for the one case sending is paused without tearing down capture).
 * - Tool calling: server sends `{"type":"tool.call","call_id":...,"name":...,"arguments":{...}}`; this class looks
 *   up the matching handler registered via [registerTool], runs it, and replies with
 *   `{"type":"tool.result","call_id":...,"result":"...","is_error":false}`.
 * - Transcripts: `{"type":"transcript.user","text":...}` / `{"type":"transcript.agent","text":...,"interrupted":...}`
 *   are finalized-only (partial/delta variants exist but aren't surfaced -- Pro Mode's transcript log only needs
 *   the final text of each turn).
 * - End: this class sends `{"type":"session.end"}` and closes; the server may also end the session itself
 *   (`{"type":"session.ended"}`) or report a protocol error (`{"type":"session.error",...}`).
 * - Resume: `session.ready` carries a `session_id`, saved here. If the WebSocket drops for any reason
 *   OTHER than this class's own [disconnect] (a transient network blip, a server-side idle close, etc.),
 *   [handleUnexpectedDisconnect] reopens a new WebSocket and sends `{"type":"session.resume","session_id":...}`
 *   instead of a fresh `session.update`, per the protocol's ~30s resume window -- mic capture is left
 *   running throughout so nothing about the conversation is lost, only the socket itself is replaced.
 *   Without this, ANY transient disconnect (which is not rare on a real, non-Wi-Fi network) would silently
 *   end the whole Pro Mode session -- "sometimes it just stops responding" -- with no way back short of
 *   leaving and re-entering Pro Mode.
 *
 * **Real barge-in / self-listening**: replies are now played through
 * [audioTrack] (see above) while this class's mic capture stays
 * continuously on -- deliberately NOT muted during that playback, unlike an
 * earlier version. Muting during every reply would make interruption
 * impossible (the user would have to wait out the whole reply before the
 * mic listened again, regardless of when they actually wanted to speak);
 * instead, [isEchoCancellationAvailable] exposes whether the device
 * supports [AcousticEchoCanceler] -- attached to the capture session in
 * [startAudioCapture] when it does, so the mic doesn't feed NAVI's own
 * voice back to the server as if it were the user talking.
 * [com.apps.naviai.ui.viewmodel.ProModeViewModel] checks this before
 * connecting: on a device that reports it, a real `input.speech.started`
 * while NAVI is speaking is now handled entirely inside this class --
 * [stopAgentAudioPlayback] flushes [audioTrack] immediately (see
 * `"input.speech.started"`'s handling), so playback actually stops rather
 * than just being talked over; on a device without AEC,
 * [com.apps.naviai.ui.viewmodel.ProModeViewModel] falls back to
 * [setMicMuted] around [VoiceAgentEvent.AgentAudioStarted]/
 * [VoiceAgentEvent.AgentAudioStopped] instead, trading barge-in away for
 * avoiding a self-listening loop AEC would otherwise have prevented.
 * [AcousticEchoCanceler] is a best-effort platform feature, not a guarantee
 * -- quality still varies by device even where it reports available.
 * This class deliberately does NOT set `AudioManager.MODE_IN_COMMUNICATION`
 * or use [MediaRecorder.AudioSource.VOICE_COMMUNICATION] for capture -- an
 * earlier version did, on the theory that it would help suppress echo, but
 * that mode changes system-wide audio routing/focus and can silently
 * reroute or duck OTHER apps' audio output (including this app's own
 * [com.apps.naviai.audio.TextToSpeechManager] calls) toward the earpiece/
 * call audio path instead of the normal speaker -- a real, concrete way to
 * end up with "no sound at all" from NAVI's replies while everything else
 * (transcripts, connection) still works. [AcousticEchoCanceler] (a per-session
 * audio effect) achieves the same echo-suppression goal without touching
 * global audio routing the way that mode did.
 *
 * Not a `@Singleton` -- a fresh instance is created per
 * [com.apps.naviai.ui.viewmodel.ProModeViewModel], living only as long as
 * Pro Mode is on screen.
 *
 * **`input.speech.started` never firing -- CONCLUSIVELY not a client-side bug.**
 * Across many live sessions, the server never once emitted `input.speech.started`
 * / `transcript.user`, despite `input.audio` being sent continuously with a
 * genuinely non-silent signal. Every part of that pipeline this class
 * controls has since been individually verified correct, not assumed:
 * - `input.format` (nested object `{"encoding":"audio/pcm"}`) -- confirmed
 *   by the server's own strict validation (rejects a string variant outright).
 * - Sample rate -- [AudioRecord.getSampleRate] and the server's own
 *   `session.ready` echo both confirm 24000Hz, matching [SAMPLE_RATE_HZ].
 * - Signal quality -- RMS/peak/clipped/DC-offset diagnostics (see the
 *   capture loop below) show real, varying, non-clipped, near-zero-DC-offset
 *   audio, with peaks well into audibly-loud range.
 * - Wall-clock pacing -- chunks are sent at exactly the real-time rate
 *   24kHz/50ms-per-chunk implies, ruling out a silent capture-rate mismatch.
 * - `turn_detection` -- removed from `session.update` entirely for one full
 *   session (server's own adaptive defaults apply); the symptom was
 *   identical, ruling out every value this class had been sending.
 * - **The actual audio content** -- this class also writes every raw PCM16
 *   chunk it sends to a local WAV file (see [startWavCapture]/[writeWavChunk]),
 *   saved to the device's public Downloads folder on session end. Played
 *   back and confirmed by a human listener to contain real, audible speech
 *   -- not silence, not noise, not corrupted/misaligned samples.
 *
 * **CONFIRMED server-side via the session's own backend record.** Fetching
 * a completed session through AssemblyAI's backend API
 * (`GET /v1/sessions/{session_id}`) and downloading its own `audio`
 * artifact (a stereo recording: left channel = user input, right = agent
 * output, per AssemblyAI support) showed the LEFT channel completely
 * silent/empty for a session where this class's own locally-saved WAV (the
 * literal same bytes sent as `input.audio`, see above) contained clear,
 * audible speech for the same time window. The session's `resolved input
 * config` in that same backend response also matched exactly what
 * [buildInlineSessionUpdate] sent (`format`, `turn_detection`,
 * `language_codes`, etc. all echoed back correctly) -- so the session was
 * configured exactly as intended, but the audio itself never made it into
 * what the server actually stored/processed. This is airtight proof the
 * loss happens somewhere between this class's `webSocket.send()` call
 * (which reports success for every chunk) and AssemblyAI's own audio
 * ingestion/storage -- entirely outside this class's control. There is no
 * further client-side change that could fix this; the remaining action is
 * reporting this exact finding to AssemblyAI support.
 */
class VoiceAgentClient @Inject constructor(
    private val httpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var onEvent: ((VoiceAgentEvent) -> Unit)? = null
    private val toolHandlers = mutableMapOf<String, suspend (JSONObject) -> String>()
    @Volatile private var connected = false
    @Volatile private var micMuted = false

    // ---- Agent voice playback (reply.audio -> AudioTrack) -- see handleReplyAudio()'s doc. ----
    private var audioTrack: AudioTrack? = null
    @Volatile private var agentAudioPlaying = false

    // ---- Debug WAV capture -- see startWavCapture()'s doc. ----
    private var wavFile: RandomAccessFile? = null
    private var wavTempFile: File? = null
    private var wavBytesWritten: Long = 0

    // ---- Reconnect/resume state -- see the class doc's "Resume" section. ----
    private var apiKey: String? = null
    private var systemPrompt: String? = null
    private var greeting: String? = null
    private var tools: JSONArray? = null
    private var languageCodes: List<String> = emptyList()
    @Volatile private var sessionId: String? = null
    @Volatile private var deliberateDisconnect = false
    private var reconnectAttempts = 0

    /**
     * Whether this device can attach [AcousticEchoCanceler] to a capture
     * session -- a static platform capability check, safe to call before
     * [connect]. See the class doc's "Real barge-in" section: the caller
     * should use this to decide whether it's safe to allow real interruption
     * (mic stays open through NAVI's own speech) or should fall back to
     * [setMicMuted] around each reply instead.
     */
    fun isEchoCancellationAvailable(): Boolean = AcousticEchoCanceler.isAvailable()

    /** Must be called before [connect]. [handler] runs off the main thread; its return value becomes the tool's `result` text, which the agent's own LLM turns into a spoken reply. */
    fun registerTool(name: String, handler: suspend (JSONObject) -> String) {
        toolHandlers[name] = handler
    }

    /**
     * Stops (or resumes) forwarding captured mic audio to the server without
     * tearing down [AudioRecord] -- the fallback self-listening guard for a
     * device where [isEchoCancellationAvailable] is false (see the class
     * doc's "Real barge-in" section). Calling this on a device where AEC is
     * active is harmless but unnecessary -- the caller decides which mode
     * it's in, this class doesn't gate on [isEchoCancellationAvailable] itself.
     */
    fun setMicMuted(muted: Boolean) {
        micMuted = muted
    }

    /**
     * Opens the session. [languageCodes] is one or more plain language tags
     * (e.g. `listOf("id", "en")`) -- the Voice Agent API's `input.language_codes`
     * accepts an array specifically to support code-switching (the caller
     * speaking more than one language across a session; see
     * [com.apps.naviai.ui.viewmodel.ProModeViewModel]'s class doc for the
     * caveat on how well this is actually documented/supported for
     * Indonesian specifically). Must hold RECORD_AUDIO; caller is
     * responsible for the permission check (same convention as
     * [com.apps.naviai.audio.AssemblyAiBatchTranscriber]).
     */
    @SuppressLint("MissingPermission")
    fun connect(
        apiKey: String,
        systemPrompt: String,
        greeting: String,
        tools: JSONArray,
        languageCodes: List<String>,
        onEvent: (VoiceAgentEvent) -> Unit
    ) {
        disconnect()
        this.onEvent = onEvent
        this.apiKey = apiKey
        this.systemPrompt = systemPrompt
        this.greeting = greeting
        this.tools = tools
        this.languageCodes = languageCodes
        deliberateDisconnect = false
        reconnectAttempts = 0
        sessionId = null
        emit(VoiceAgentEvent.Connecting)
        trace("connect() called, opening WebSocket to $WEBSOCKET_URL")
        openWebSocket()
    }

    private fun openWebSocket() {
        val key = apiKey ?: run {
            traceWarn("openWebSocket() called with no apiKey set -- connect() was never called successfully")
            return
        }
        // Auth via ?token= query param, NOT an Authorization header -- per
        // AssemblyAI support's own example code for this exact issue (an
        // Authorization: Bearer header still let the session connect and
        // the greeting work, but query-param auth is what their reference
        // client actually uses; audio format is declared inside
        // session.update's input.format instead of a query param, per the
        // actual spec -- see buildInlineSessionUpdate).
        val wsUrl = "$WEBSOCKET_URL?token=${URLEncoder.encode(key, "UTF-8")}"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        trace("-> WebSocket connect attempt to $WEBSOCKET_URL (?token= query param set, ${key.length} char key)")

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trace("<- WebSocket onOpen: HTTP ${response.code} ${response.message}, headers=${response.headers}")
                    // A saved session_id means this socket is replacing one
                    // that dropped unexpectedly -- resume that same session
                    // instead of starting a fresh one (see class doc).
                    val message = sessionId?.let { id ->
                        JSONObject().put("type", "session.resume").put("session_id", id)
                    } ?: buildInlineSessionUpdate()
                    // Not truncated to LOG_MESSAGE_MAX_CHARS like the per-chunk/per-message traces below --
                    // this is sent once per connection (or resume), and its whole point is letting the
                    // input.format/turn_detection fields actually be checked, which a truncated tools array
                    // (15 verbose tool descriptions) would otherwise hide entirely.
                    trace("-> ${message.optString("type")}: $message")
                    val sent = webSocket.send(message.toString())
                    trace("-> ${message.optString("type")} send() returned $sent")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    traceWarn(
                        "<- WebSocket onFailure: ${t::class.simpleName}: ${t.message}" +
                            (response?.let { " (HTTP ${it.code} ${it.message})" } ?: ""),
                        t
                    )
                    handleUnexpectedDisconnect(t.message ?: "Connection failed")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    trace("<- WebSocket onClosing: code=$code reason=\"$reason\" deliberateDisconnect=$deliberateDisconnect")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    trace("<- WebSocket onClosed: code=$code reason=\"$reason\" deliberateDisconnect=$deliberateDisconnect")
                    if (deliberateDisconnect) {
                        teardown()
                    } else {
                        handleUnexpectedDisconnect("Connection closed ($code): $reason")
                    }
                }
            }
        )
    }

    /**
     * The `session.update` sent for a brand-new (non-resumed) session --
     * split out from [openWebSocket] so it's easy to read against the spec:
     * ```
     * {"type":"session.update","session":{
     *   "system_prompt":..., "greeting":..., "tools":[...],
     *   "input":{"format":{"encoding":"audio/pcm"},"language_codes":[...],
     *            "transcription_mode":"min_latency","continuous_partials":true,"voice_focus":null,
     *            "turn_detection":{"vad_threshold":0.15,"min_silence":500,"max_silence":1500,
     *                              "interrupt_response":true,"interruption_delay":0}}
     * }}
     * ```
     * `input.format` is a nested JSON OBJECT (`{"encoding":"audio/pcm"}`),
     * NOT a plain string -- this was briefly changed to a string based on
     * three AssemblyAI blog posts' own example payloads (two of which
     * showed a string, one of which omitted the field), but the live
     * server rejected that immediately with an explicit
     * `session.error{code:"invalid_value", message:"Audio format must be a
     * JSON object", param:"format"}` -- i.e. the blog examples are stale
     * for whatever API version is actually live now. The object shape here
     * is the one confirmed BOTH by that rejection (it complains only when
     * given something that isn't an object) AND by an earlier live session
     * where this exact shape was sent, produced no error, and was echoed
     * back by the server in `session.ready` as
     * `{"encoding":"audio/pcm","sample_rate":24000}` -- i.e. accepted and
     * interpreted exactly as intended.
     *
     * `turn_detection`'s VALUES (not its shape -- `vad_threshold`/
     * `min_silence`/`max_silence`/`interrupt_response`, nested inside
     * `input`, all syntactically fine) were ruled out at the higher values
     * this class used before (`0.5`/`1000`/`3000`): removing the block
     * entirely for one live session (server's adaptive defaults apply with
     * nothing here at all) produced the identical symptom -- zero
     * `input.speech.started` across 114 seconds despite RMS peaks up to
     * 15173 (unambiguously loud, real audio), zero `session.error`, zero
     * clipping, correct 24kHz pacing throughout. `vad_threshold` is now
     * lowered to `0.15` (much more sensitive) with a new `interruption_delay`
     * field, plus three fields never tried before at all --
     * `transcription_mode: "min_latency"`, `continuous_partials: true`,
     * `voice_focus: null` (per AssemblyAI's own more current example) --
     * since those are genuinely untested variables, not ones already
     * eliminated by the defaults-only test above.
     */
    private fun buildInlineSessionUpdate(): JSONObject {
        val languageCodesArray = JSONArray().apply { languageCodes.forEach { put(it) } }
        val input = JSONObject().apply {
            put("format", JSONObject().put("encoding", "audio/pcm"))
            put("language_codes", languageCodesArray)
            put("transcription_mode", "min_latency")
            put("continuous_partials", true)
            put("voice_focus", JSONObject.NULL)
            put(
                "turn_detection",
                JSONObject().apply {
                    put("vad_threshold", 0.15)
                    put("min_silence", 500)
                    put("max_silence", 1500)
                    put("interrupt_response", true)
                    put("interruption_delay", 0)
                }
            )
        }
        val session = JSONObject().apply {
            put("system_prompt", systemPrompt)
            put("greeting", greeting)
            put("tools", tools)
            put("input", input)
        }
        return JSONObject().put("type", "session.update").put("session", session)
    }

    /**
     * The WebSocket dropped without this class asking it to. Tries a
     * `session.resume` reconnect (see class doc's "Resume" section) up to
     * [MAX_RECONNECT_ATTEMPTS] times, as long as a `session_id` was ever
     * actually obtained (nothing to resume otherwise -- e.g. the very first
     * connection attempt failed before `session.ready`). Deliberately does
     * NOT touch [connected]/[stopAudioRecord] here: the mic keeps capturing
     * throughout a resume attempt (send calls on the down socket just no-op
     * briefly) so there's no audible gap or re-initialization on success.
     */
    private fun handleUnexpectedDisconnect(reason: String) {
        val canResume = sessionId != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS
        if (!canResume) {
            traceWarn("Giving up after disconnect (attempts=$reconnectAttempts, hasSessionId=${sessionId != null}): $reason")
            emit(VoiceAgentEvent.Error(reason, fatal = true))
            teardown()
            return
        }
        reconnectAttempts++
        traceWarn("Unexpected disconnect, attempting session.resume ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS): $reason")
        emit(VoiceAgentEvent.Reconnecting)
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            openWebSocket()
        }
    }

    /** Ends the session and releases the mic. Safe to call even if never connected, or more than once. */
    fun disconnect() {
        if (webSocket != null) trace("disconnect() called -- sending session.end and closing")
        deliberateDisconnect = true
        webSocket?.let { ws ->
            runCatching { ws.send(JSONObject().put("type", "session.end").toString()) }
                .onFailure { traceWarn("Failed to send session.end", it) }
            runCatching { ws.close(1000, "client disconnect") }
                .onFailure { traceWarn("Failed to close WebSocket cleanly", it) }
        }
        teardown()
    }

    private fun teardown() {
        if (!connected && webSocket == null && audioRecord == null) return // already torn down
        trace("teardown() -- releasing mic and clearing session state")
        connected = false
        micMuted = false
        sessionId = null
        reconnectAttempts = 0
        captureJob?.cancel()
        captureJob = null
        stopAudioRecord()
        stopAudioTrackPlayback()
        webSocket = null
        emit(VoiceAgentEvent.Ended)
    }

    /**
     * Called directly from [WebSocketListener.onMessage], which OkHttp
     * invokes on its own reader thread for this connection -- an uncaught
     * exception here (a malformed/unexpected message shape given this
     * protocol was never verified against a live session, or an unrelated
     * failure like [startAudioCapture]'s [AudioRecord] setup throwing) would
     * otherwise kill that reader thread silently: the socket stays open but
     * no further server message is ever processed again, which looks
     * exactly like "the agent stopped responding" with no visible crash or
     * error. Every path through this function is therefore guarded.
     */
    private fun handleServerMessage(text: String) {
        try {
            handleServerMessageOrThrow(text)
        } catch (t: Throwable) {
            traceError("Unhandled exception processing server message: ${text.take(LOG_MESSAGE_MAX_CHARS)}", t)
            emit(VoiceAgentEvent.Error(t.message ?: "Failed to process a Voice Agent message", fatal = false))
        }
    }

    private fun handleServerMessageOrThrow(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: run {
            traceWarn("Non-JSON server message, ignored: ${text.take(LOG_MESSAGE_MAX_CHARS)}")
            return
        }
        val type = json.optString("type")
        // The exact wire shape here was assembled from AssemblyAI's docs/blog
        // posts, not verified against a live session (no API key was
        // available in the environment this was built in) -- traced at a
        // level visible in the in-app debug log (see VoiceAgentEvent.DebugLog)
        // so a real session's actual message shapes can be diffed against
        // what this class expects if something doesn't behave as documented.
        // reply.audio is excluded: it arrives many times per second and its
        // content (base64 PCM) is never used (see class doc) -- tracing it
        // was flooding the capped debug log, pushing genuinely useful lines
        // (the initial session.update, the first audio-flow confirmation)
        // out of the buffer before anyone could read them.
        if (type != "reply.audio") trace("<- $type: ${text.take(LOG_MESSAGE_MAX_CHARS)}")
        when (type) {
            "session.ready" -> {
                // If connected was already true, this socket replaced one
                // that dropped (see handleUnexpectedDisconnect) -- the mic
                // never stopped, so starting a second AudioRecord here would
                // be a real bug (two capture loops fighting over one mic).
                val isResume = connected
                connected = true
                reconnectAttempts = 0
                json.optString("session_id").takeIf { it.isNotBlank() }?.let { sessionId = it }
                // Per AssemblyAI support: the config echoed back here (if any) is the server's
                // *effective* input format, not this client's assumption -- if it ever disagrees
                // with what buildInlineSessionUpdate() sent (e.g. a different sample rate or
                // encoding the server silently fell back to), that's the actual root cause, not
                // anything on the Android side. opt() (not optJSONObject()) because format is a
                // plain string ("audio/pcm"), not a nested object -- see buildInlineSessionUpdate's doc.
                val effectiveInput = json.optJSONObject("session")?.optJSONObject("input")
                    ?: json.optJSONObject("config")?.optJSONObject("input")
                effectiveInput?.opt("format")?.let { effectiveFormat ->
                    trace("session.ready effective input.format (server-reported): $effectiveFormat")
                }
                emit(VoiceAgentEvent.Ready)
                if (!isResume) startAudioCapture()
            }
            // Real barge-in: flush any of NAVI's own reply audio still playing/queued BEFORE
            // emitting the event, so a caller reacting to UserSpeechStarted never races against
            // audio that's about to keep coming out of the speaker anyway -- see stopAgentAudioPlayback's doc.
            "input.speech.started" -> {
                stopAgentAudioPlayback(flush = true)
                emit(VoiceAgentEvent.UserSpeechStarted)
            }
            "input.speech.stopped" -> emit(VoiceAgentEvent.UserSpeechStopped)
            // extractText tries a few plausible field names/nesting, not just a bare "text" key -- see its doc.
            "transcript.user" -> emit(VoiceAgentEvent.UserTranscript(extractText(json)))
            "transcript.agent" -> emit(VoiceAgentEvent.AgentTranscript(extractText(json), json.optBoolean("interrupted", false)))
            // Authoritative "this turn is over" signal -- see VoiceAgentEvent.ReplyDone's doc for why this
            // resets UI state on its own rather than relying only on transcript.agent having parsed correctly.
            // stopAgentAudioPlayback(flush = false) here too: no more reply.audio chunks are coming for
            // this turn, so the "NAVI is speaking" window (AgentAudioStarted/Stopped) should end here even
            // though whatever's still queued in AudioTrack keeps draining out to the speaker naturally.
            "reply.done" -> {
                emit(VoiceAgentEvent.ReplyDone)
                stopAgentAudioPlayback(flush = false)
            }
            "tool.call" -> handleToolCall(json)
            // "error" as an alias for "session.error" -- AssemblyAI support's own reference client checks
            // for both type names on the same error-handling branch. Per support: log the whole event
            // (code/message/param), not just message -- e.g. audio_rate_violation (sending faster than
            // realtime) surfaces as a specific `code` that a bare message string would hide.
            "session.error", "error" -> {
                val code = json.optString("code")
                val message = json.optString("message", "Voice agent error")
                val param = json.optString("param")
                traceError("Voice agent session.error: code=\"$code\" message=\"$message\" param=\"$param\" raw=${text.take(LOG_MESSAGE_MAX_CHARS)}")
                emit(VoiceAgentEvent.Error(message, fatal = false))
            }
            "session.ended" -> {
                // Per AssemblyAI support: this is the most direct proof of whether the server
                // actually received/recorded audio for this session -- a positive number means
                // it did, null means it never recorded anything despite whatever this class sent.
                val audioDurationSeconds = json.opt("audio_duration_seconds")
                trace("session.ended audio_duration_seconds=$audioDurationSeconds -- ${if (audioDurationSeconds == null || audioDurationSeconds == JSONObject.NULL) "server recorded NO audio for this session" else "server did receive audio"}")
                teardown()
            }
            "reply.audio" -> handleReplyAudio(json)
            // Confirmed-real protocol bookkeeping from an actual live session (not just docs) that Pro
            // Mode's UI doesn't need: session.updated (session.update's own ack, arrives before
            // session.ready), reply.started (a reply is about to stream, redundant with reply.audio/
            // transcript.agent already starting to arrive), transcript.*.delta (word-by-word streaming
            // versions of the transcript.user/transcript.agent finals this class already uses).
            "session.updated", "reply.started", "transcript.agent.delta", "transcript.user.delta" -> Unit
            else -> {
                // A WARN (not just the DEBUG line above) for anything this
                // class doesn't recognize at all -- if the real protocol
                // uses different event names than documented, this is what
                // should stand out in logcat instead of scrolling past
                // dozens of DEBUG lines looking for it.
                if (type.isNotBlank()) traceWarn("Unhandled Voice Agent message type \"$type\": ${text.take(LOG_MESSAGE_MAX_CHARS)}")
            }
        }
    }

    /**
     * Looks for a transcript's text under a bare `text` key first, then a
     * short list of other plausible names/one level of nesting -- this
     * protocol was never verified against a live session (see class doc),
     * so this is deliberately more forgiving than trusting a single exact
     * field path, which would otherwise silently produce an empty string
     * (and therefore no reply at all, see [com.apps.naviai.ui.viewmodel.ProModeViewModel.appendTranscript]'s
     * blank-text guard) if the real field is named or nested differently.
     */
    private fun extractText(json: JSONObject): String {
        for (key in TEXT_KEYS) {
            val value = json.optString(key)
            if (value.isNotBlank()) return value
        }
        for (wrapperKey in listOf("transcript", "data", "message", "delta", "item")) {
            val nested = json.optJSONObject(wrapperKey) ?: continue
            for (key in TEXT_KEYS) {
                val value = nested.optString(key)
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun handleToolCall(json: JSONObject) {
        val callId = json.optString("call_id")
        val name = json.optString("name")
        val arguments = json.optJSONObject("arguments") ?: JSONObject()
        val handler = toolHandlers[name]
        emit(VoiceAgentEvent.ToolInvoked(name))
        scope.launch {
            val (result, isError) = if (handler == null) {
                traceWarn("tool.call for unregistered tool \"$name\"")
                "Unknown tool: $name" to true
            } else {
                runCatching { handler(arguments) }.fold(
                    onSuccess = { it to false },
                    onFailure = { (it.message ?: "Tool failed") to true }
                )
            }
            val toolResult = JSONObject().apply {
                put("type", "tool.result")
                put("call_id", callId)
                put("result", result)
                put("is_error", isError)
            }
            trace("-> tool.result for \"$name\": ${toolResult.toString().take(LOG_MESSAGE_MAX_CHARS)}")
            webSocket?.send(toolResult.toString())
        }
    }

    // ---- Agent voice playback: reply.audio -> AudioTrack ----

    /**
     * Decodes one `reply.audio` chunk's base64 PCM16 payload and streams it
     * straight into [audioTrack] -- this class now plays the agent's own
     * synthesized voice directly (matching AssemblyAI's bidirectional
     * "input and output audio are both PCM16 mono 24kHz" spec), replacing
     * the previous design where [com.apps.naviai.ui.viewmodel.ProModeViewModel]
     * spoke [VoiceAgentEvent.AgentTranscript]'s text through the app's own
     * [com.apps.naviai.audio.TextToSpeechManager] instead. [AgentAudioStarted]
     * fires on the first chunk of a turn (edge-triggered via [agentAudioPlaying])
     * so callers get one clean "NAVI started talking" signal rather than one
     * per chunk -- chunks arrive many times per second.
     */
    private fun handleReplyAudio(json: JSONObject) {
        val base64Audio = json.optString("audio")
        if (base64Audio.isBlank()) return
        val bytes = runCatching { Base64.decode(base64Audio, Base64.NO_WRAP) }
            .onFailure { traceWarn("Failed to base64-decode a reply.audio chunk", it) }
            .getOrNull() ?: return
        if (bytes.isEmpty()) return
        val track = runCatching { ensureAudioTrack() }
            .onFailure { traceError("Failed to create playback AudioTrack", it) }
            .getOrNull() ?: return
        if (!agentAudioPlaying) {
            agentAudioPlaying = true
            runCatching { track.play() }.onFailure { traceWarn("AudioTrack.play() failed", it) }
            trace("Agent audio playback started")
            emit(VoiceAgentEvent.AgentAudioStarted)
        }
        runCatching { track.write(bytes, 0, bytes.size) }
            .onFailure { traceWarn("AudioTrack.write() failed for a reply.audio chunk", it) }
    }

    /**
     * Lazily builds the playback [AudioTrack] -- 24kHz mono PCM16 output,
     * same rate/format as [SAMPLE_RATE_HZ] since AssemblyAI's Voice Agent
     * API uses the same PCM16/24kHz spec both directions. `USAGE_ASSISTANT`/
     * `CONTENT_TYPE_SPEECH` (not `USAGE_MEDIA`) so this plays through the
     * same audio path a voice assistant reply normally would, consistent
     * with the app's other spoken output.
     */
    private fun ensureAudioTrack(): AudioTrack {
        audioTrack?.let { return it }
        val minBufferBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, CHUNK_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        return track
    }

    /**
     * Ends the current "NAVI is speaking" window. [flush] distinguishes the
     * two ways this happens: `false` from `reply.done` (no more audio is
     * coming for this turn, but whatever's already queued in the track
     * should keep draining out naturally -- stopping it there would cut the
     * tail end of NAVI's own reply), `true` from a real barge-in
     * (`input.speech.started` while still playing -- the queued audio must
     * stop immediately, not finish playing over the user).
     */
    private fun stopAgentAudioPlayback(flush: Boolean) {
        if (!agentAudioPlaying && !flush) return
        val wasPlaying = agentAudioPlaying
        agentAudioPlaying = false
        if (flush) {
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            }.onFailure { traceWarn("Failed to flush agent audio playback for barge-in", it) }
        }
        if (wasPlaying) {
            trace("Agent audio playback stopped (flush=$flush)")
            emit(VoiceAgentEvent.AgentAudioStopped)
        }
    }

    private fun stopAudioTrackPlayback() {
        audioTrack?.apply {
            runCatching { stop() }
            runCatching { release() }
        }
        audioTrack = null
        agentAudioPlaying = false
    }

    private fun emit(event: VoiceAgentEvent) {
        onEvent?.invoke(event)
    }

    /** Logs to Logcat and also surfaces the same line as [VoiceAgentEvent.DebugLog] -- see that event's doc for why. */
    private fun trace(message: String) {
        Log.d(TAG, message)
        emit(VoiceAgentEvent.DebugLog(message))
    }

    private fun traceWarn(message: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
        emit(VoiceAgentEvent.DebugLog("WARN: $message"))
    }

    private fun traceError(message: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        emit(VoiceAgentEvent.DebugLog("ERROR: $message"))
    }

    // ---- Audio capture: mic -> input.audio ----

    /**
     * Called from [handleServerMessageOrThrow] (already exception-guarded by
     * its caller, [handleServerMessage]), but wrapped in its own try/catch
     * regardless -- [AudioRecord]'s constructor can throw (not just fail to
     * initialize) if RECORD_AUDIO isn't actually granted yet at this exact
     * moment, e.g. a race against [com.apps.naviai.ui.screens.ProModeScreen]'s
     * own permission request dialog still being on screen when
     * `session.ready` arrives.
     */
    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        try {
            startAudioCaptureOrThrow()
        } catch (t: Throwable) {
            traceError("Failed to start audio capture: ${t.message}", t)
            emit(VoiceAgentEvent.Error(t.message ?: "Failed to start the microphone", fatal = true))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureOrThrow() {
        val minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferBytes <= 0) {
            emit(VoiceAgentEvent.Error("AudioRecord unsupported on this device", fatal = true))
            return
        }

        val record = AudioRecord(
            // VOICE_RECOGNITION (not VOICE_COMMUNICATION) -- same source
            // com.apps.naviai.audio.AssemblyAiBatchTranscriber already uses
            // elsewhere in this app. VOICE_COMMUNICATION pairs with
            // AudioManager.MODE_IN_COMMUNICATION for call-style echo
            // cancellation, but this class no longer sets that mode -- see
            // the class doc for why (it broke TextToSpeechManager's own
            // audio output routing).
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, CHUNK_BYTES * 2)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            emit(VoiceAgentEvent.Error("Microphone failed to initialize", fatal = true))
            return
        }
        // AssemblyAI support's own diagnosis for "server never detects
        // speech despite real non-zero RMS": Android's audio framework is
        // supposed to transparently resample to the rate an app requests,
        // but this has never been directly confirmed on the devices this
        // was tested on. getSampleRate() reports what AudioRecord itself
        // believes it's delivering -- if this ever prints anything other
        // than SAMPLE_RATE_HZ, every chunk sent so far was mislabeled to
        // the server (we always declare 24kHz in session.update regardless).
        if (record.sampleRate != SAMPLE_RATE_HZ) {
            traceError("AudioRecord reports actual sample rate ${record.sampleRate}Hz, requested $SAMPLE_RATE_HZ Hz -- audio sent to the server will be mislabeled and likely unrecognizable as speech")
        }

        audioRecord = record
        record.startRecording()

        echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }
                .onFailure { traceWarn("Failed to attach AcousticEchoCanceler", it) }
                .getOrNull()
        } else {
            null
        }
        trace("Audio capture started (${SAMPLE_RATE_HZ}Hz mono PCM16), echoCanceler=${echoCanceler != null}")
        startWavCapture()

        captureJob = scope.launch {
            val buffer = ByteArray(CHUNK_BYTES)
            var chunksSent = 0
            var mutedChunks = 0
            var sendFailures = 0
            // Real-time cross-check for the same "silent rate mismatch"
            // hypothesis as the getSampleRate() log above, but from the
            // other direction: each chunk is CHUNK_BYTES of PCM16 mono,
            // which represents exactly 50ms of audio *if* it's truly
            // 24kHz. If AudioRecord is actually handing back samples
            // captured at a different real rate, read() still fills the
            // same byte count per call, so wall-clock time between reads
            // (not the byte count) is what would reveal the mismatch --
            // e.g. if reads keep completing much faster than 50ms apart,
            // the buffer is draining audio that represents less than
            // 50ms of real-world time per chunk, meaning it's actually
            // sped up relative to what 24kHz assumes. Counted against
            // *every* successful read (totalChunksRead), not just sent
            // ones -- a muted stretch still consumes real time reading
            // from the mic and would otherwise look like a rate mismatch.
            val captureStartMs = SystemClock.elapsedRealtime()
            var totalChunksRead = 0
            while (isActive && connected) {
                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (t: Throwable) {
                    traceError("AudioRecord.read failed, stopping capture: ${t.message}", t)
                    emit(VoiceAgentEvent.Error(t.message ?: "Microphone read failed", fatal = true))
                    break
                }
                if (read <= 0) continue
                totalChunksRead++
                if (micMuted) {
                    mutedChunks++
                    continue
                }
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                writeWavChunk(chunk)
                val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val sent = webSocket?.send(JSONObject().put("type", "input.audio").put("audio", base64).toString()) ?: false
                if (sent) chunksSent++ else sendFailures++
                // Not just a one-time confirmation -- a periodic count with
                // the chunk's RMS (signal level), so a capture loop that
                // silently stops mid-conversation (rather than never
                // starting at all) also shows up in the debug log: if this
                // line stops appearing while the user is still talking, the
                // mic loop itself died. If it keeps appearing with a real
                // (non-near-zero) RMS but the agent never reacts, the audio
                // really is reaching the server as intelligible sound and
                // the problem is server-side. If RMS stays near zero the
                // whole time even while the user is visibly speaking, the
                // mic is capturing silence/near-silence -- an audio
                // capture/routing bug on this end, not a server issue.
                if (chunksSent == 1 || chunksSent % AUDIO_TRACE_EVERY_N_CHUNKS == 0) {
                    val expectedMs = totalChunksRead * 50L
                    val actualMs = SystemClock.elapsedRealtime() - captureStartMs
                    val ratePct = if (expectedMs > 0) (actualMs * 100 / expectedMs) else 100
                    val stats = pcmStats(chunk)
                    // ~2400 raw bytes at 50ms/24kHz/mono/PCM16 should base64 to ~3200 chars (4/3 expansion,
                    // no padding at this exact size) -- a value far off that would mean something other than
                    // raw PCM16 mono is being sent (a WAV header, stereo, a different bit depth, ...).
                    trace(
                        "Audio: $chunksSent chunk(s) sent, $mutedChunks muted, $sendFailures send failure(s), " +
                            "RMS=${stats.rms.toInt()} peak=${stats.peak} clipped=${stats.clipped} dcOffset=${"%.1f".format(stats.dcOffsetPct)}% " +
                            "(chunk ${chunk.size}B -> base64 ${base64.length} chars), wallClock=${actualMs}ms vs expected=${expectedMs}ms (${ratePct}%)"
                    )
                    if (ratePct < 80 || ratePct > 125) {
                        traceWarn("Capture wall-clock time is ${ratePct}% of what $totalChunksRead chunks at 24kHz should take -- AudioRecord may be delivering audio at a different real-world rate than declared to the server")
                    }
                    if (stats.clipped > 0) {
                        traceWarn("${stats.clipped} clipped sample(s) in this chunk -- input gain may be too hot, which can also confuse server-side VAD")
                    }
                    if (kotlin.math.abs(stats.dcOffsetPct) > 5.0) {
                        traceWarn("DC offset ${"%.1f".format(stats.dcOffsetPct)}% of full scale -- non-zero mean can look like signal is present without being recognizable speech")
                    }
                }
            }
            trace("Audio capture loop ended (isActive=$isActive, connected=$connected), $chunksSent chunk(s) sent total")
        }
    }

    /**
     * Decodes one chunk's 16-bit little-endian PCM samples and reports the
     * handful of stats AssemblyAI support specifically called out as things
     * a healthy-looking RMS can still hide: [PcmStats.peak]/[PcmStats.clipped]
     * (distortion from a too-hot input gain), and [PcmStats.dcOffsetPct] (a
     * non-zero mean -- a hardware/driver bug some devices have, and on its
     * own enough to confuse a VAD even with plenty of real signal on top of
     * it). [PcmStats.rms] is the same energy check this already had.
     */
    private data class PcmStats(val rms: Double, val peak: Int, val clipped: Int, val dcOffsetPct: Double)

    private fun pcmStats(buffer: ByteArray): PcmStats {
        if (buffer.size < 2) return PcmStats(0.0, 0, 0, 0.0)
        var sumSquares = 0.0
        var sum = 0.0
        var peak = 0
        var clipped = 0
        var sampleCount = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            sumSquares += (sample * sample).toDouble()
            sum += sample
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            if (magnitude >= 32760) clipped++
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return PcmStats(0.0, 0, 0, 0.0)
        val rms = kotlin.math.sqrt(sumSquares / sampleCount)
        val dcOffsetPct = (sum / sampleCount) / Short.MAX_VALUE * 100.0
        return PcmStats(rms, peak, clipped, dcOffsetPct)
    }

    private fun stopAudioRecord() {
        echoCanceler?.apply { runCatching { release() } }
        echoCanceler = null
        audioRecord?.apply {
            runCatching { stop() }
            release()
        }
        audioRecord = null
        stopWavCaptureAndSave()
    }

    /**
     * Debug-only: mirrors the exact raw PCM16 bytes this class actually
     * sends to the server (see the `writeWavChunk` call right where
     * `input.audio` is built) into a local WAV file, so the audio can be
     * played back and listened to directly -- AssemblyAI support's own
     * point: a healthy-looking RMS/peak doesn't prove the bytes decode to
     * intelligible speech (wrong sample interleaving, corrupted framing,
     * etc. can still produce plausible-looking energy numbers). Writing to
     * [Context.getCacheDir] first (not directly to a MediaStore [android.net.Uri],
     * which doesn't support seeking back to patch the header) because the
     * final `data`/`RIFF` chunk sizes in a WAV header aren't known until
     * capture stops.
     *
     * Deliberately unconditional (not gated behind a settings toggle) for
     * now, while `input.speech.started` still never fires in any live
     * session -- this should be removed or gated behind an explicit debug
     * flag before this ever ships, since it writes every Pro Mode
     * conversation's raw audio to the device's public Downloads folder.
     */
    private fun startWavCapture() {
        runCatching {
            val tempFile = File(context.cacheDir, "pro_mode_capture.wav")
            val raf = RandomAccessFile(tempFile, "rw")
            raf.setLength(0)
            raf.write(ByteArray(WAV_HEADER_SIZE)) // placeholder, patched by stopWavCaptureAndSave()
            wavFile = raf
            wavTempFile = tempFile
            wavBytesWritten = 0
            trace("Debug WAV capture started -> ${tempFile.absolutePath} (will be saved to Downloads/NaviAI on session end)")
        }.onFailure { traceWarn("Failed to start debug WAV capture", it) }
    }

    private fun writeWavChunk(chunk: ByteArray) {
        val raf = wavFile ?: return
        runCatching {
            raf.write(chunk)
            wavBytesWritten += chunk.size
        }.onFailure {
            traceWarn("Failed to write debug WAV chunk, stopping WAV capture for this session", it)
            runCatching { raf.close() }
            wavFile = null
        }
    }

    /** Patches the WAV header with the final size now that it's known, then copies the finished file into the public Downloads/NaviAI folder via MediaStore so it's playable without `adb`. */
    private fun stopWavCaptureAndSave() {
        val raf = wavFile ?: return
        val tempFile = wavTempFile
        val dataSize = wavBytesWritten
        wavFile = null
        wavTempFile = null
        wavBytesWritten = 0
        runCatching {
            raf.seek(0)
            raf.write(buildWavHeader(dataSize))
            raf.close()
        }.onFailure {
            traceWarn("Failed to finalize debug WAV header", it)
            runCatching { raf.close() }
            return
        }
        if (tempFile == null || dataSize <= 0) return
        // MediaStore.Downloads and MediaColumns.RELATIVE_PATH both require API 29+ (minSdk here is
        // 26) -- below that, leave the finished WAV in the app cache dir (still logged below) rather
        // than attempt an API that doesn't exist on those devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            trace("Debug WAV saved: ${tempFile.absolutePath} ($dataSize bytes raw PCM, ${dataSize / (SAMPLE_RATE_HZ * 2)}s) -- device is pre-Android 10, so this couldn't be copied to the public Downloads folder; needs adb or a file manager with app-cache access to retrieve")
            return
        }
        runCatching {
            val filename = "navi_pro_mode_${System.currentTimeMillis()}.wav"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NaviAI")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Failed to open output stream for $uri")
            tempFile.delete()
            trace("Debug WAV saved: Downloads/NaviAI/$filename ($dataSize bytes raw PCM, ${dataSize / (SAMPLE_RATE_HZ * 2)}s) -- play this back to verify the audio actually sent to the server is real, intelligible speech")
        }.onFailure { traceWarn("Failed to save debug WAV to Downloads, left at ${tempFile.absolutePath}", it) }
    }

    private fun buildWavHeader(dataSize: Long): ByteArray {
        val riffSize = 36 + dataSize
        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffSize.toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // fmt chunk size (PCM)
            putShort(1) // audio format: PCM
            putShort(1) // channels: mono
            putInt(SAMPLE_RATE_HZ)
            putInt(SAMPLE_RATE_HZ * 2) // byte rate = sampleRate * channels * bytesPerSample
            putShort(2) // block align = channels * bytesPerSample
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize.toInt())
        }.array()
    }

    private companion object {
        const val TAG = "VoiceAgentClient"
        const val WEBSOCKET_URL = "wss://agents.assemblyai.com/v1/ws"
        const val SAMPLE_RATE_HZ = 24000

        /** Caps how much of a logged message body prints -- a malformed/unexpected response could be large. */
        const val LOG_MESSAGE_MAX_CHARS = 500

        /** 16-bit samples = 2 bytes/sample; 50ms of 24kHz mono audio per chunk sent. */
        const val CHUNK_BYTES = SAMPLE_RATE_HZ * 2 * 50 / 1000

        /** Standard canonical WAV/RIFF header size (no extra chunks) -- see buildWavHeader. */
        const val WAV_HEADER_SIZE = 44

        /** Plausible field names for a transcript's text -- see [extractText]. */
        val TEXT_KEYS = listOf("text", "transcript", "content", "message")

        /** How many session.resume attempts to make before giving up on an unexpected disconnect -- see handleUnexpectedDisconnect. */
        const val MAX_RECONNECT_ATTEMPTS = 3

        /** Brief backoff before each resume attempt, so a rapidly-flapping connection doesn't hammer the server in a tight loop. */
        const val RECONNECT_DELAY_MS = 500L

        /** How often (in chunks sent) to log an audio-flow progress line -- 40 chunks = ~2s at the 50ms-per-chunk rate. */
        const val AUDIO_TRACE_EVERY_N_CHUNKS = 40
    }
}
