package com.apps.naviai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apps.naviai.core.performance.PerformanceStats
import kotlin.math.roundToInt

/** Small on-screen readout of live FPS/latency, toggleable from Settings. */
@Composable
fun PerformanceStatsOverlay(stats: PerformanceStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "${stats.fps.roundToInt()} FPS  ·  ${stats.detectionCount} objects",
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}
