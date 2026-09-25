package com.apps.naviai.ui.screens

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.camera.CameraManager
import com.apps.naviai.core.permissions.rememberCameraPermissionState
import com.apps.naviai.core.permissions.rememberMicrophonePermissionState
import com.apps.naviai.ui.viewmodel.ProModeConnectionStatus
import com.apps.naviai.ui.viewmodel.ProModeViewModel
import com.apps.naviai.ui.viewmodel.TranscriptEntry
import com.apps.naviai.ui.viewmodel.TranscriptSpeaker
import java.util.concurrent.Executors

/**
 * Pro Mode (Conversation Mode): a visually distinct screen from
 * [DetectionScreen] -- full-duplex conversation with NAVI over
 * [com.apps.naviai.voiceagent.VoiceAgentClient], reached by saying
 * "NAVI, mode pro" and left by saying "NAVI, matikan mode pro" (recognized
 * client-side by [com.apps.naviai.voiceagent.ProModeCommandMatcher], not a
 * tool call -- see [ProModeViewModel]'s class doc) or the back button.
 *
 * No detection overlay/bounding boxes here (unlike [DetectionScreen]) --
 * this screen deliberately doesn't run the object-detection pipeline; the
 * camera preview only feeds the vision-backed tools a current frame.
 */
@Composable
fun ProModeScreen(onBack: () -> Unit, viewModel: ProModeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberCameraPermissionState()
    val micPermission = rememberMicrophonePermissionState()
    var showDebugLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!micPermission.isGranted) micPermission.request()
        if (!cameraPermission.isGranted) cameraPermission.request()
    }

    // Tells the ViewModel as soon as RECORD_AUDIO is confirmed granted (or
    // not) -- connecting the Voice Agent session before this is confirmed
    // is exactly the race documented in ProModeViewModel.onMicPermissionResult.
    LaunchedEffect(micPermission.isGranted) {
        viewModel.onMicPermissionResult(micPermission.isGranted)
    }

    LaunchedEffect(Unit) {
        viewModel.exitEvents.collect { onBack() }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onScreenClosed() }
    }

    val cameraManager = remember { CameraManager(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    LaunchedEffect(previewView, cameraPermission.isGranted) {
        val view = previewView ?: return@LaunchedEffect
        if (!cameraPermission.isGranted) return@LaunchedEffect

        val provider = cameraManager.awaitProvider()
        val lensFacing = CameraSelector.LENS_FACING_BACK
        if (!cameraManager.hasLensFacing(provider, lensFacing)) return@LaunchedEffect

        val preview = cameraManager.buildPreviewUseCase().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = cameraManager.buildAnalysisUseCase()
        analysis.setAnalyzer(analysisExecutor, viewModel.createFrameAnalyzer(mirror = false))
        cameraManager.bind(lifecycleOwner, provider, preview, analysis, lensFacing)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermission.isGranted) {
            AndroidView(factory = { ctx -> PreviewView(ctx).apply { previewView = this } }, modifier = Modifier.fillMaxSize())
        }

        // Dim overlay so the transcript/status text stays readable over any camera content -- Pro Mode is
        // a conversation experience, the preview is secondary context for the vision tools, not the focus.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Exit Pro Mode" }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("Pro Mode", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            // Raw protocol trace, in-app -- see ProModeUiState.debugLog's doc for why this exists instead
            // of asking for `adb logcat` output: this protocol has never been verified against a live
            // session, and being able to copy the actual exchange is the only way to debug it for real.
            IconButton(
                onClick = { showDebugLog = !showDebugLog },
                modifier = Modifier.semantics { contentDescription = if (showDebugLog) "Hide debug log" else "Show debug log" }
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color.White)
            }
        }

        if (showDebugLog) {
            DebugLogPanel(
                lines = uiState.debugLog,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp)
            )
        } else {
            VoiceAgentPanel(
                status = uiState.status,
                activeToolLabel = uiState.activeToolLabel,
                isProcessing = uiState.isProcessing,
                isMicMuted = uiState.isMicMuted,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )

            TranscriptLog(
                entries = uiState.transcript,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }
}

/**
 * A copyable panel of [com.apps.naviai.voiceagent.VoiceAgentEvent.DebugLog] lines -- toggled by the bug
 * icon in the top bar. Exists so a person without `adb` access can still
 * hand over real protocol evidence (what was actually sent/received) instead
 * of a description of symptoms, which is what this feature's debugging has
 * been stuck on so far -- see [com.apps.naviai.voiceagent.VoiceAgentClient]'s
 * class doc.
 */
@Composable
private fun DebugLogPanel(lines: List<String>, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        modifier = modifier
            .heightIn(max = 480.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Debug log (${lines.size})", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = { copyDebugLog(clipboard, lines) },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "Copy debug log" }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Copy")
            }
        }
        Spacer(Modifier.size(8.dp))
        if (lines.isEmpty()) {
            Text(
                "Nothing logged yet -- this fills in as the Voice Agent connects and exchanges messages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lines) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun copyDebugLog(clipboard: ClipboardManager, lines: List<String>) {
    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
}

/**
 * The Voice Agent's own status panel -- Pro Mode's equivalent of
 * [com.apps.naviai.ui.components.VoiceCommandPanel] on the regular
 * Detection screen (same rounded-card look), but reflecting a full-duplex
 * conversation's states instead of a single wake-word-gated command:
 * connecting, actively listening, thinking (between the user's turn ending
 * and a reply arriving -- see [com.apps.naviai.voiceagent.VoiceAgentEvent.UserSpeechStopped]),
 * using a tool, speaking a reply (mic muted, see [ProModeViewModel.speakAgentReply]),
 * or unavailable/error/ended.
 */
@Composable
private fun VoiceAgentPanel(
    status: ProModeConnectionStatus,
    activeToolLabel: String?,
    isProcessing: Boolean,
    isMicMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val isListening = status == ProModeConnectionStatus.Ready && !isMicMuted && !isProcessing && activeToolLabel == null
    val isBusy = status == ProModeConnectionStatus.Connecting || status == ProModeConnectionStatus.Reconnecting || isProcessing || activeToolLabel != null
    val isErrorLike = status is ProModeConnectionStatus.Unavailable || status is ProModeConnectionStatus.Error

    val label = when (status) {
        ProModeConnectionStatus.Connecting -> "Connecting…"
        // The connection dropped briefly and is being restored automatically -- mic keeps listening the
        // whole time (see VoiceAgentClient's "Resume" doc), the user doesn't need to do anything.
        ProModeConnectionStatus.Reconnecting -> "Reconnecting…"
        ProModeConnectionStatus.Ready -> when {
            activeToolLabel != null -> "Using $activeToolLabel…"
            isProcessing -> "Thinking…"
            isMicMuted -> "Speaking…"
            else -> "Listening…"
        }
        is ProModeConnectionStatus.Unavailable -> status.message
        is ProModeConnectionStatus.Error -> status.message
        ProModeConnectionStatus.Ended -> "Session ended"
    }
    val micDescription = when {
        isErrorLike -> "Voice agent unavailable"
        status == ProModeConnectionStatus.Ended -> "Voice agent session ended"
        status == ProModeConnectionStatus.Reconnecting -> "Voice agent reconnecting"
        isMicMuted -> "NAVI is speaking, microphone paused"
        isListening -> "Voice agent listening"
        else -> "Voice agent working on a reply"
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
            }
            Icon(
                imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp).semantics { contentDescription = micDescription },
                tint = when {
                    isErrorLike -> MaterialTheme.colorScheme.error
                    isListening -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Spacer(Modifier.size(12.dp))
        Text("NAVI Voice Agent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = label,
            color = if (isErrorLike) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun TranscriptLog(entries: List<TranscriptEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries) { entry ->
            val isUser = entry.speaker == TranscriptSpeaker.USER
            Text(
                text = "${if (isUser) "You" else "NAVI"}: ${entry.text}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
    }
}
