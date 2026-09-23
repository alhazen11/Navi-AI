package com.apps.naviai.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.core.permissions.rememberCameraPermissionState
import com.apps.naviai.detection.detector.DetectorState
import com.apps.naviai.ui.theme.NaviPrimary
import com.apps.naviai.ui.viewmodel.DetectionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    viewModel: DetectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permission = rememberCameraPermissionState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!permission.isGranted) permission.request()
    }

    LaunchedEffect(permission.isGranted) {
        if (permission.isGranted && uiState.detectorState is DetectorState.Uninitialized) {
            viewModel.initializeDetector()
        }
    }

    LaunchedEffect(uiState.detectorState, permission.isGranted) {
        if (permission.isGranted && uiState.detectorState is DetectorState.Ready) {
            delay(400) // let the "Ready" state be visible briefly, avoid a jarring instant cut
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            NaviAILogo()
            Spacer(Modifier.height(24.dp))
            Row {
                Text("Navi", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text("AI", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = NaviPrimary)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Independent Mobility,\nOne Step at a Time",
                fontSize = 18.sp,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(48.dp))

            when {
                !permission.isGranted -> StatusBlock(
                    message = "Camera permission is required for NAVI AI to detect obstacles.",
                    actionLabel = "Grant camera permission",
                    onAction = { permission.request() }
                )
                uiState.detectorState is DetectorState.Error -> StatusBlock(
                    message = "Couldn't start the on-device detector:\n${uiState.lastErrorMessage}",
                    actionLabel = "Retry",
                    onAction = { scope.launch { viewModel.initializeDetector() } }
                )
                else -> LoadingIndicator(
                    label = when (uiState.detectorState) {
                        is DetectorState.Ready -> "Ready"
                        is DetectorState.Initializing -> "Loading detection model…"
                        else -> "Preparing…"
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusBlock(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun LoadingIndicator(label: String) {
    val transition = rememberInfiniteTransition(label = "loading")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(NaviPrimary.copy(alpha = alpha)))
            Box(Modifier.size(14.dp).clip(CircleShape).background(NaviPrimary.copy(alpha = 0.75f)))
            Box(Modifier.size(14.dp).clip(CircleShape).background(NaviPrimary.copy(alpha = 0.5f)))
        }
        Spacer(Modifier.height(16.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NaviAILogo(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.size(180.dp)
    ) {

        val width = size.width
        val height = size.height

        val gradient = Brush.linearGradient(
            colors = listOf(
                Color(0xFF42A5F5),
                Color(0xFF3155F5)
            ),
            start = Offset(0f, 0f),
            end = Offset(width, height)
        )

        val strokeWidth = width * 0.22f

        val path = Path().apply {

            // Left vertical stroke
            moveTo(width * 0.25f, height * 0.35f)

            lineTo(width * 0.25f, height * 0.72f)

            // Rounded left stroke
            lineTo(width * 0.25f, height * 0.72f)
        }

        // Draw N using thick rounded lines
        drawLine(
            brush = gradient,
            start = Offset(width * 0.25f, height * 0.70f),
            end = Offset(width * 0.25f, height * 0.30f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        drawLine(
            brush = gradient,
            start = Offset(width * 0.25f, height * 0.30f),
            end = Offset(width * 0.73f, height * 0.70f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF8DCBFA),
                    Color(0xFF55A4F5)
                )
            ),
            start = Offset(width * 0.73f, height * 0.30f),
            end = Offset(width * 0.73f, height * 0.70f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Dot above right stroke
        drawCircle(
            brush = gradient,
            radius = width * 0.10f,
            center = Offset(width * 0.73f, height * 0.12f)
        )
    }
}