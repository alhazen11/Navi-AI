package com.apps.naviai.voiceagent

/**
 * High-level, protocol-agnostic events [VoiceAgentClient] surfaces to
 * [com.apps.naviai.ui.viewmodel.ProModeViewModel] -- everything below is
 * derived from AssemblyAI Voice Agent API WebSocket messages (see
 * [VoiceAgentClient]'s class doc for the wire protocol this was verified
 * against), but callers never see raw JSON.
 */
sealed interface VoiceAgentEvent {
    /** WebSocket opened, `session.update` sent, waiting for `session.ready`. */
    data object Connecting : VoiceAgentEvent

    /**
     * The WebSocket dropped unexpectedly (not from a deliberate
     * [VoiceAgentClient.disconnect] call) and [VoiceAgentClient] is trying
     * to resume the same session (`session.resume` with the saved
     * `session_id`, within the ~30s window the protocol allows for this)
     * rather than ending the conversation outright. Mic capture keeps
     * running through this -- only the WebSocket itself is being replaced.
     */
    data object Reconnecting : VoiceAgentEvent

    /** `session.ready` received -- mic capture starts right after this. */
    data object Ready : VoiceAgentEvent

    /** `input.speech.started` -- the user has started talking. */
    data object UserSpeechStarted : VoiceAgentEvent

    /** `input.speech.stopped` -- the user has finished their turn; the agent is now generating a reply (used to show a "thinking" indicator until [ToolInvoked] or [AgentTranscript] arrives). */
    data object UserSpeechStopped : VoiceAgentEvent

    /** A finalized user utterance (`transcript.user`). */
    data class UserTranscript(val text: String) : VoiceAgentEvent

    /**
     * A finalized agent reply's text (`transcript.agent`), for the on-screen
     * transcript only -- [VoiceAgentClient] plays the agent's own
     * synthesized voice itself (`reply.audio`, decoded and streamed through
     * an internal `AudioTrack`), so the caller (see
     * [com.apps.naviai.ui.viewmodel.ProModeViewModel]) never needs to speak
     * this text through [com.apps.naviai.audio.TextToSpeechManager] --
     * see [AgentAudioStarted]/[AgentAudioStopped] for the actual "NAVI is
     * speaking" signal, which is driven by real playback, not this event.
     */
    data class AgentTranscript(val text: String, val interrupted: Boolean) : VoiceAgentEvent

    /**
     * The agent's own synthesized voice (`reply.audio`) started playing
     * through [VoiceAgentClient]'s internal `AudioTrack` -- the real
     * "NAVI is speaking" signal callers should use to mute the mic (on
     * devices without echo cancellation) and show a "Speaking…" state,
     * replacing any text-length-based estimate.
     */
    data object AgentAudioStarted : VoiceAgentEvent

    /**
     * The agent's own synthesized voice finished playing -- either the
     * buffered audio fully drained after [ReplyDone], or it was cut short
     * by a real barge-in ([UserSpeechStarted] flushes it immediately).
     */
    data object AgentAudioStopped : VoiceAgentEvent

    /** The agent invoked one of the registered tools -- shown in the UI as "using <name>...". */
    data class ToolInvoked(val name: String) : VoiceAgentEvent

    /**
     * `reply.done` -- the agent's turn (spoken or tool-driven) has fully
     * ended. Used as the authoritative signal to return the UI to a
     * listening-ready state, independent of whether [AgentTranscript] also
     * fired correctly for this turn -- see [VoiceAgentClient]'s class doc:
     * this protocol was never verified live, so a turn-completion signal
     * that resets state on its own (rather than relying solely on
     * [AgentTranscript]'s text having parsed correctly) is what keeps a
     * transcript-parsing miss from leaving the UI stuck mid-turn forever.
     */
    data object ReplyDone : VoiceAgentEvent

    /** `session.error`, a WebSocket failure, or a local audio-device failure. [fatal] means the session is unusable and was torn down. */
    data class Error(val message: String, val fatal: Boolean) : VoiceAgentEvent

    /** `session.ended` or a clean local disconnect. */
    data object Ended : VoiceAgentEvent

    /**
     * A raw protocol trace line (message sent/received, capture/echo-canceler
     * status, reconnect attempts) -- the same content [VoiceAgentClient]
     * already writes to Logcat, also surfaced as an event so
     * [com.apps.naviai.ui.viewmodel.ProModeViewModel] can keep an in-app
     * copy. Exists because this protocol was assembled from docs/blog posts,
     * never verified against a live session, and asking someone without
     * `adb` access to fetch Logcat output has not been a workable way to get
     * real diagnostic evidence -- an in-app, copyable log is.
     */
    data class DebugLog(val line: String) : VoiceAgentEvent
}
