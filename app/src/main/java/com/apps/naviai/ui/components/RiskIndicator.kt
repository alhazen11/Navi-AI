package com.apps.naviai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.ui.theme.RiskCritical
import com.apps.naviai.ui.theme.RiskHigh
import com.apps.naviai.ui.theme.RiskLow
import com.apps.naviai.ui.theme.RiskMedium
import com.apps.naviai.ui.theme.RiskSafe

fun RiskLevel.color() = when (this) {
    RiskLevel.SAFE -> RiskSafe
    RiskLevel.LOW -> RiskLow
    RiskLevel.MEDIUM -> RiskMedium
    RiskLevel.HIGH -> RiskHigh
    RiskLevel.CRITICAL -> RiskCritical
}

fun RiskLevel.label() = when (this) {
    RiskLevel.SAFE -> "Safe"
    RiskLevel.LOW -> "Low risk"
    RiskLevel.MEDIUM -> "Medium risk"
    RiskLevel.HIGH -> "High risk"
    RiskLevel.CRITICAL -> "Critical risk"
}

@Composable
fun RiskIndicator(riskLevel: RiskLevel, modifier: Modifier = Modifier) {
    val color = riskLevel.color()
    val text = riskLevel.label()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(2.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Risk level: $text" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text = text, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
