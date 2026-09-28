package com.apps.naviai.voiceagent

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
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
 * **Agent voice: this class does NOT play the agent's own audio.** `reply.audio` is received and
 * dropped; [com.apps.naviai.ui.viewmodel.ProModeViewModel] speaks `transcript.agent`'s text through the
 * app's own [com.apps.naviai.audio.TextToSpeechManager] instead. This went back and forth twice, so the
 * reasoning is worth keeping: an intermediate version decoded `reply.audio` into an internal `AudioTrack`
 * to use AssemblyAI's full speech-to-speech pipeline end-to-end. Tested on a real device, that path
 * *looked* healthy in the logs (chunks arriving, decoding, `AudioTrack.play()`/`write()` all succeeding,
 * no errors) yet produced no audible sound at all -- most likely `USAGE_ASSISTANT` routing, though it was
 * not worth chasing further, because the same live session showed the deciding constraint: the agent
 * replies in English. Its synthesized voice only covers the six languages listed in
 * [VoiceAgentLanguages], and this app's users speak Indonesian. The app's own TTS is the only output path
 * that can actually say an Indonesian sentence, and it already carries the user's configured language,
 * speech rate and pitch, so it is what Pro Mode uses. Consequences for the caller: it owns the whole
 * "NAVI is speaking" window (mic muting, barge-in, UI state) off its own TTS progress, since no playback
 * event can come from here.
 *
 * **Wire protocol** (re-verified 2026-09-27 against AssemblyAI's currently-published Voice Agent API
 * docs -- specifically the WebSocket Events Reference and the Create Agent API spec pages, not just
 * blog posts -- after the extensive live-session investigation below found real bugs this way; this is
 * a new API and the protocol may still evolve, so treat this as a snapshot, not a permanent contract):
 * - Connect: `wss://agents.assemblyai.com/v1/ws` with an `Authorization: Bearer <key>` header. An earlier
 *   version of this class used `?token=<key>` instead, on advice attributed to AssemblyAI support for the
 *   `input.speech.started`-never-fires issue investigated at length below -- but that investigation's own
 *   notes say the header form was tried too and showed the *identical* symptom, so the auth method was
 *   never actually the differentiator; switched back to the header form since it's what BOTH of AssemblyAI's
 *   own current docs pages document (the query-param form appears in neither).
 * - First client message: `{"type":"session.update","session":{"system_prompt":...,"greeting":...,"tools":[...],
 *   "input":{"format":{"encoding":"audio/pcm","sample_rate":24000},"language_codes":[...],"turn_detection":{...}},
 *   "output":{"format":{"encoding":"audio/pcm","sample_rate":24000}}}}` -- see [buildInlineSessionUpdate] for the
 *   exact shape. Two concrete mismatches against the docs were found and fixed here: `turn_detection` was
 *   sending `min_silence`/`max_silence`/`interrupt_response`/`interruption_delay`, none of which are documented
 *   fields (the Events Reference lists only `vad_threshold`, `silence_duration_ms`, `speech_duration_ms`) --
 *   the server likely silently ignored the unrecognized ones rather than erroring, so `turn_detection` was
 *   effectively running on partial defaults this whole time; and `voice_focus: null` was sent even though the
 *   Create Agent spec explicitly does not list `voice_focus` as an `input` field at all -- removed rather than
 *   guessed at. Neither of these two fixes is confirmed to be *the* fix for the "audio never reaches the
 *   server" finding below (they're independently wrong regardless of that bug); no live API key/device was
 *   available in this environment either, so they're unverified against a real session, same caveat as before.
 * - Server confirms with `{"type":"session.ready",...}` -- only then does this class start capturing/sending audio.
 * - Audio in: `{"type":"input.audio","audio":"<base64 PCM16 mono 24kHz>"}`, sent continuously in small chunks
 *   (see [setMicMuted] for the one case sending is paused without tearing down capture).
 * - Tool calling: server sends `{"type":"tool.call","call_id":...,"name":...,"arguments":{...}}`; this class looks
 *   up the matching handler registered via [registerTool], runs it, and replies with
 *   `{"type":"tool.result","call_id":...,"result":"...","is_error":false}`.
 * - Transcripts: `{"type":"transcript.user","text":...}` / `{"type":"transcript.agent","text":...,"interrupted":...}`
 *   are finalized-only (partial/delta variants exist but aren't surfaced -- Pro Mode's transcript log only needs
 *   the final text of each turn).
 * - Agent audio out: `{"type":"reply.audio","data":"<base64 PCM16 mono 24kHz>"}` -- received and ignored (see
 *   "Agent voice" above). Worth recording since it cost time: the payload key is `data`, not `audio`, and the
 *   version of this class that did play it read `"audio"` exclusively, so it silently decoded nothing at all.
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
 * **Self-listening: the mic is unconditionally muted while NAVI's own TTS is speaking.**
 * [com.apps.naviai.ui.viewmodel.ProModeViewModel] used to gate this on [AcousticEchoCanceler]
 * availability -- leaving the mic open for "real" barge-in on a device that reported AEC support,
 * since [setMicMuted] muting through every reply would otherwise make interruption impossible. That
 * was confirmed WRONG on a real device once the agent's voice moved from an internal `AudioTrack` (the
 * same low-level pipeline the AEC was attached alongside) to [com.apps.naviai.audio.TextToSpeechManager]
 * -- see this class's "Agent voice" doc for why that move happened. Android's system TTS engine is a
 * separate audio session the platform AEC effect has no guaranteed reference to, and on that device it
 * measurably wasn't cancelling it: the agent audibly reacted to its own spoken replies, a self-response
 * loop. [setMicMuted] now runs unconditionally for exactly the window [TextToSpeechManager.isSpeaking]
 * reports (see [com.apps.naviai.ui.viewmodel.ProModeViewModel]'s init block) -- true mid-sentence
 * interruption is gone, but the user can still cut NAVI off right as a reply ends.
 * [AcousticEchoCanceler] is still attached in [startAudioCapture] where the device supports it (harmless,
 * possibly still marginally useful for other noise), but nothing here depends on it working any more.
 * This class deliberately does NOT set `AudioManager.MODE_IN_COMMUNICATION`
 * or use [MediaRecorder.AudioSource.VOICE_COMMUNICATION] for capture -- an
 * earlier version did, on the theory that it would help suppress echo, but
 * that mode changes system-wide audio routing/focus and can silently
 * reroute or duck OTHER apps' audio output (including [com.apps.naviai.audio.TextToSpeechManager]'s
 * own) toward the earpiece/call audio path instead of the normal speaker -- a real, concrete way to
 * end up with "no sound at all" from NAVI's replies while everything else (transcripts, connection)
 * still works.
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
 * **ROOT CAUSE FOUND (2026-09-27): an unsupported `input.language_codes` value.** Everything below this
 * paragraph is the investigation that preceded it, kept because it's what ruled the audio pipeline out so
 * thoroughly. Pro Mode was sending `language_codes: ["id", "en"]` -- Indonesian first -- because this app's
 * users speak Indonesian and AssemblyAI's *speech-to-text product* supports it. The Voice Agent API is a
 * separate pipeline whose own published language list covers only English/Spanish/French/German/Italian/
 * Portuguese, and its events reference notes that `language_codes` is "applied on the next STT connect".
 * An unsupported code there is accepted syntactically (it's just an array of strings -- hence zero
 * `session.error`, and a `session.ready` that echoes the config back verbatim) but leaves the server with
 * no working transcriber for the session. That single fact accounts for every symptom at once: the greeting
 * plays (the LLM/TTS half never depends on the input language), `input.speech.started`/`transcript.user`
 * never fire, the backend session recording's input channel is empty, and changing `turn_detection` makes
 * no difference because no VAD is running at all. It also explains why every audio-level check below came
 * back clean -- the audio was always fine; nothing was listening to it. See [VoiceAgentLanguages], which now
 * filters unsupported codes out and omits the field entirely when nothing supported is left, so the server
 * falls back to its documented automatic detection instead.
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
 * what the server actually stored/processed. This narrowed the loss to
 * somewhere between this class's `webSocket.send()` call (which reports
 * success for every chunk) and AssemblyAI's own audio ingestion -- which
 * was read at the time as "outside this class's control", but is explained
 * by the language-code root cause above: audio for a session with no working
 * transcriber attached is never ingested in the first place.
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

    /** Must be called before [connect]. [handler] runs off the main thread; its return value becomes the tool's `result` text, which the agent's own LLM turns into a spoken reply. */
    fun registerTool(name: String, handler: suspend (JSONObject) -> String) {
        toolHandlers[name] = handler
    }

    /**
     * Stops (or resumes) forwarding captured mic audio to the server without tearing down
     * [AudioRecord] -- the self-listening guard while NAVI's own TTS is speaking, called
     * unconditionally now (see the class doc's "Self-listening" section for why).
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
        // Authorization: Bearer header, per AssemblyAI's currently-published Events Reference and
        // Create Agent API docs (both document header auth; neither documents a ?token= query param --
        // see the class doc's "Wire protocol" section for why this was previously query-param instead).
        val request = Request.Builder()
            .url(WEBSOCKET_URL)
            .header("Authorization", "Bearer $key")
            .build()

        trace("-> WebSocket connect attempt to $WEBSOCKET_URL (Authorization: Bearer header set, ${key.length} char key)")

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
     *   "input":{"format":{"encoding":"audio/pcm","sample_rate":24000},"language_codes":[...],
     *            "transcription_mode":"min_latency",
     *            "turn_detection":{"vad_threshold":0.15,"silence_duration_ms":500}},
     *   "output":{"format":{"encoding":"audio/pcm","sample_rate":24000}}
     * }}
     * ```
     * `language_codes` is present only when at least one requested code is actually supported -- see
     * [VoiceAgentLanguages], and the class doc's root-cause note, for why sending an unsupported one
     * silently breaks the entire session's speech recognition.
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
     * clipping, correct 24kHz pacing throughout.
     *
     * **2026-09-27 update, cross-checked against AssemblyAI's currently-published Events Reference
     * (see the class doc's "Wire protocol" section):** `min_silence`/`max_silence`/`interrupt_response`/
     * `interruption_delay` are not documented `turn_detection` fields at all -- the only ones listed are
     * `vad_threshold`, `silence_duration_ms`, and `speech_duration_ms`. Since the server produced zero
     * `session.error` while these unrecognized fields were being sent, it most likely just ignored them
     * silently rather than rejecting the message -- meaning `turn_detection` has effectively been running
     * on server defaults (beyond `vad_threshold`) this whole time regardless of what this class intended.
     * Renamed to the documented field name (`silence_duration_ms`, keeping the same `500`ms value the old
     * `min_silence` used); `speech_duration_ms` is left unset (server default) since there's no prior value
     * to carry over and no evidence for what it should be. `voice_focus` is also removed: the Create Agent
     * API spec's `input` schema doesn't list it as a field at all, so sending `null` for it was never
     * meaningful. `continuous_partials` went the same way -- also undocumented, and pointless here anyway
     * since [handleServerMessageOrThrow] deliberately ignores every `transcript.*.delta` event. An `output`
     * object was added (previously absent) since the docs show `session.update` has both `input` and
     * `output` format blocks -- this makes the output PCM16/24kHz format explicit rather than relying on
     * an unconfirmed server default.
     */
    private fun buildInlineSessionUpdate(): JSONObject {
        // An unsupported language code here does NOT fail loudly -- it silently prevents the server's
        // internal STT connect from ever succeeding, so the agent greets normally and then never reacts
        // to anything the user says. See VoiceAgentLanguages' doc for the full story; omitting the field
        // (the API's documented default: automatic detection) is strictly safer than forcing a code the
        // Voice Agent pipeline doesn't handle.
        val supportedCodes = VoiceAgentLanguages.filterSupported(languageCodes)
        val dropped = languageCodes.filterNot { VoiceAgentLanguages.isSupported(it) }
        if (dropped.isNotEmpty()) {
            traceWarn(
                "Dropping language code(s) $dropped -- not supported by the Voice Agent API's speech-to-text " +
                    "(supported: English/Spanish/French/German/Italian/Portuguese). " +
                    if (supportedCodes.isEmpty()) {
                        "Sending no language_codes at all, so the server falls back to automatic detection."
                    } else {
                        "Sending $supportedCodes instead."
                    }
            )
        }
        val pcmFormat = JSONObject().put("encoding", "audio/pcm").put("sample_rate", SAMPLE_RATE_HZ)
        val input = JSONObject().apply {
            put("format", pcmFormat)
            if (supportedCodes.isNotEmpty()) {
                put("language_codes", JSONArray().apply { supportedCodes.forEach { put(it) } })
            }
            put("transcription_mode", "min_latency")
            put(
                "turn_detection",
                JSONObject().apply {
                    put("vad_threshold", 0.15)
                    put("silence_duration_ms", 500)
                }
            )
        }
        val output = JSONObject().apply {
            put("format", JSONObject().put("encoding", "audio/pcm").put("sample_rate", SAMPLE_RATE_HZ))
        }
        val session = JSONObject().apply {
            put("system_prompt", systemPrompt)
            put("greeting", greeting)
            put("tools", tools)
            put("input", input)
            put("output", output)
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
            // Barge-in: the caller cuts NAVI's own speech off in response to this (it owns playback now --
            // see the class doc's "Agent voice" section), so nothing to stop on this side.
            "input.speech.started" -> emit(VoiceAgentEvent.UserSpeechStarted)
            "input.speech.stopped" -> emit(VoiceAgentEvent.UserSpeechStopped)
            // extractText tries a few plausible field names/nesting, not just a bare "text" key -- see its doc.
            "transcript.user" -> emit(VoiceAgentEvent.UserTranscript(extractText(json)))
            "transcript.agent" -> emit(VoiceAgentEvent.AgentTranscript(extractText(json), json.optBoolean("interrupted", false)))
            // Authoritative "this turn is over" signal -- see VoiceAgentEvent.ReplyDone's doc for why this
            // resets UI state on its own rather than relying only on transcript.agent having parsed correctly.
            "reply.done" -> emit(VoiceAgentEvent.ReplyDone)
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
            // reply.audio is deliberately dropped: the agent's synthesized voice is not used at all
            // (see the class doc's "Agent voice" section -- the caller speaks transcript.agent's text
            // through the app's own TTS instead, which is the only path that can do Indonesian).
            "reply.audio" -> Unit
            // Confirmed-real protocol bookkeeping from an actual live session (not just docs) that Pro
            // Mode's UI doesn't need: session.updated (session.update's own ack, arrives before
            // session.ready), reply.started (a reply is about to stream, redundant with
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
