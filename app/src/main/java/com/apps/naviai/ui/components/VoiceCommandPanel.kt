package com.apps.naviai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.audio.VoiceCommandStatus
import com.apps.naviai.audio.VoiceCommandUiState
import com.apps.naviai.core.permissions.rememberMicrophonePermissionState
import com.apps.naviai.ui.viewmodel.VoiceCommandViewModel

/**
 * Always-on voice command UI: once microphone permission is granted, NAVI
 * starts listening automatically -- no push button needed for each
 * utterance. The mic button here is a pause/resume toggle, not a
 * press-to-talk trigger. Only speech starting with "NAVI" is treated as an
 * addressed command; this first phase only displays what was heard, it
 * does not yet act on it. A garbled attempt to address NAVI (the wake word
 * was heard but no clean command could be parsed) prompts a repeat, both
 * shown here and spoken aloud; ordinary silence or unrelated ambient
 * speech is ignored quietly so NAVI never nags.
 */
@Composable
fun VoiceCommandPanel(modifier: Modifier = Modifier, viewModel: VoiceCommandViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val micPermission = rememberMicrophonePermissionState()

    LaunchedEffect(Unit) {
        if (!micPermission.isGranted) micPermission.request()
    }

    // Auto-start as soon as permission is granted; re-fires only when the
    // grant state itself changes, so a manual pause (tapping the mic) is
    // NOT undone by recomposition -- it stays paused until the user taps
    // to resume.
    LaunchedEffect(micPermission.isGranted) {
        if (micPermission.isGranted) viewModel.startListening()
    }

    val isPausedByUser = uiState.status == VoiceCommandStatus.PAUSED
    val isUnavailable = uiState.status == VoiceCommandStatus.UNAVAILABLE
    val isMicActive = micPermission.isGranted && !isPausedByUser && !isUnavailable

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val micDescription = when {
                !micPermission.isGranted -> "Grant microphone permission for voice commands"
                isUnavailable -> "Voice commands unavailable, tap to retry"
                isPausedByUser -> "Voice listening paused, tap to resume"
                else -> "NAVI is listening automatically, tap to pause"
            }
            IconButton(
                onClick = {
                    when {
                        !micPermission.isGranted -> micPermission.request()
                        isPausedByUser || isUnavailable -> viewModel.startListening()
                        else -> viewModel.stopListening()
                    }
                },
                modifier = Modifier.size(56.dp).semantics { contentDescription = micDescription }
            ) {
                Icon(
                    imageVector = if (isMicActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = null,
                    tint = if (isMicActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.size(12.dp))

            Column {
                Text("NAVI Voice Command", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = statusLabel(uiState.status, micPermission.isGranted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        VoiceCommandResultText(uiState, micPermission.isGranted)
    }
}

@Composable
private fun VoiceCommandResultText(uiState: VoiceCommandUiState, micGranted: Boolean) {
    // A live region so TalkBack announces the result as soon as it changes,
    // without the user needing to explore back to this part of the screen.
    val resultModifier = Modifier
        .fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite }

    if (!micGranted) {
        Text(
            text = "NAVI needs microphone access to listen for voice commands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = resultModifier
        )
        return
    }

    when (uiState.status) {
        VoiceCommandStatus.RECOGNIZED -> Column(resultModifier) {
            Text(
                text = "\"${uiState.command.orEmpty().ifBlank { "(no command after NAVI)" }}\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            uiState.rawRecognizedText?.let {
                Text("Heard: \"$it\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        VoiceCommandStatus.UNCLEAR -> Text(
            text = uiState.message ?: "Please repeat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = resultModifier
        )
        VoiceCommandStatus.UNAVAILABLE -> Text(
            text = uiState.message ?: "Voice commands are unavailable on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = resultModifier
        )
        VoiceCommandStatus.PAUSED -> Text(
            text = "Voice listening paused. Tap the mic to resume.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = resultModifier
        )
        VoiceCommandStatus.TRANSCRIBING -> Text(
            text = "Processing…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = resultModifier
        )
        VoiceCommandStatus.LISTENING, VoiceCommandStatus.IDLE -> Text(
            text = "Listening… say \"NAVI\" followed by your command.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = resultModifier
        )
    }
}

private fun statusLabel(status: VoiceCommandStatus, micGranted: Boolean): String = when {
    !micGranted -> "Microphone permission needed"
    else -> when (status) {
        VoiceCommandStatus.IDLE -> "Starting…"
        VoiceCommandStatus.LISTENING -> "Listening"
        VoiceCommandStatus.TRANSCRIBING -> "Processing"
        VoiceCommandStatus.RECOGNIZED -> "Command recognized"
        VoiceCommandStatus.UNCLEAR -> "Unclear, please repeat"
        VoiceCommandStatus.PAUSED -> "Paused"
        VoiceCommandStatus.UNAVAILABLE -> "Unavailable"
    }
}
