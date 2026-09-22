package com.apps.naviai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.TrackedObject
import kotlin.math.roundToInt

/** Detail card for the single most relevant tracked object (highest risk / closest). */
@Composable
fun DetectionInfoCard(tracked: TrackedObject, modifier: Modifier = Modifier) {
    val label = tracked.detection.label.replaceFirstChar { it.uppercase() }
    val distanceText = tracked.estimatedDistanceMeters?.let { "about ${formatMeters(it)}" } ?: "unknown"
    val movementText = movementLabel(tracked.movementDirection)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(16.dp)
            .semantics {
                contentDescription = "$label, $distanceText away, " +
                    "${(tracked.detection.confidence * 100).roundToInt()} percent confidence, " +
                    "${tracked.riskLevel.label()}, $movementText"
            }
    ) {
        Row {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoRow("Distance", distanceText)
        InfoRow("Confidence", "${(tracked.detection.confidence * 100).roundToInt()}%")
        InfoRow("Tracking ID", "#${tracked.trackingId}")
        InfoRow("Movement", movementText)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("$label: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatMeters(distanceMeters: Float): String =
    if (distanceMeters < 1f) "less than 1 m" else "${"%.1f".format(distanceMeters)} m"

private fun movementLabel(direction: MovementDirection): String = when (direction) {
    MovementDirection.UNKNOWN -> "Unknown movement"
    MovementDirection.STATIONARY -> "Stationary"
    MovementDirection.APPROACHING -> "Approaching"
    MovementDirection.RECEDING -> "Moving away"
    MovementDirection.MOVING_LEFT_TO_RIGHT -> "Moving left to right"
    MovementDirection.MOVING_RIGHT_TO_LEFT -> "Moving right to left"
}
