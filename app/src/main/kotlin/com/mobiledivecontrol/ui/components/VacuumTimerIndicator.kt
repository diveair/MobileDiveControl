package com.mobiledivecontrol.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.delay

/**
 * Compact vacuum monitoring timer — sits in the HUD top bar.
 *
 * Shows "⏱ M:SS P.P kPa" while leak monitoring is active.
 * Pulsing cyan during monitoring, green flash on pass, amber on fail.
 * Only visible when sealState == LeakMonitoring.
 */
@Composable
fun VacuumTimerIndicator(
    monitoringStartEpochMs: Long?,
    currentPressureKpa: Double?,
    durationMs: Long = 5 * 60 * 1000L, // 5 minutes
    modifier: Modifier = Modifier,
) {
    if (monitoringStartEpochMs == null) return

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Tick every second
    LaunchedEffect(monitoringStartEpochMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val elapsedMs = (nowMs - monitoringStartEpochMs).coerceAtLeast(0)
    val remainingMs = (durationMs - elapsedMs).coerceAtLeast(0)
    val remainingSeconds = (remainingMs / 1000).toInt()
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60

    // Gentle pulse
    val infiniteTransition = rememberInfiniteTransition(label = "vacuum_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vacuum_pulse_alpha",
    )

    val pressureText = if (currentPressureKpa != null) {
        "%.1f".format(currentPressureKpa)
    } else {
        "--"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .alpha(pulseAlpha)
            .background(
                DiveColors.DiveCyan.copy(alpha = 0.12f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Timer,
            contentDescription = "Vacuum monitoring",
            tint = DiveColors.DiveCyan,
            modifier = Modifier.width(14.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "%d:%02d".format(minutes, seconds),
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${pressureText}kPa",
            color = DiveColors.Success,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
