package com.mobiledivecontrol.ui.safety

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.theme.DiveColors

/**
 * Safety screen — single-step-at-a-time guided wizard.
 *
 * Each seal state maps to a full-screen step. The diver sees ONE thing
 * to do. Press OK to proceed. Large icons and text readable through port.
 *
 * After Step 5 (solenoid closed), auto-returns to Camera mode.
 * The 5-minute leak monitor runs non-blocking in the HUD top bar.
 */
@Composable
fun SafetyScreen(
    safety: SafetyState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack),
    ) {
        AnimatedContent(
            targetState = safety.sealState,
            transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(200)))
            },
            label = "safety_step",
            modifier = Modifier.fillMaxSize(),
        ) { sealState ->
            when (sealState) {
                SealState.Unknown -> StepPrompt(
                    icon = Icons.Rounded.Speed,
                    title = "Seal Check",
                    subtitle = "Verify housing is watertight before diving",
                    instruction = "Press OK to begin",
                    accentColor = DiveColors.DiveCyan,
                )
                SealState.CoverOpen -> StepPrompt(
                    icon = Icons.Rounded.LockOpen,
                    title = "Step 1: Open Suction Cover",
                    subtitle = coverStatusText(safety.coverOpen),
                    instruction = "Open the suction cover, then press OK",
                    accentColor = DiveColors.DiveCyan,
                    showLiveSensor = true,
                    sensorLabel = "Cover",
                    sensorValue = if (safety.coverOpen == true) "OPEN ✓" else "CLOSED",
                    sensorOk = safety.coverOpen == true,
                )
                SealState.ReadyToVacuum -> StepPrompt(
                    icon = Icons.Rounded.FlipCameraAndroid,
                    title = "Step 2: Opening Solenoid Valve",
                    subtitle = "Valve opening to allow air extraction",
                    instruction = "Press OK to start pump",
                    accentColor = DiveColors.DiveTeal,
                )
                SealState.Vacuuming -> VacuumingStep(
                    currentPressure = safety.barometricPressureKpa,
                    targetPressure = 50.0,
                )
                SealState.MotorStopping -> StepPrompt(
                    icon = Icons.Rounded.CheckCircle,
                    title = "Step 3: Target Reached",
                    subtitle = "Motor stopping. Pressure: %.1f kPa".format(
                        safety.barometricPressureKpa ?: 0.0
                    ),
                    instruction = "Waiting for motor to stop...",
                    accentColor = DiveColors.Success,
                    pulsing = true,
                )
                SealState.WaitingForCoverClosed -> StepPrompt(
                    icon = Icons.Rounded.Lock,
                    title = "Step 4: Close Suction Cover",
                    subtitle = "Close the cover for accurate leak detection",
                    instruction = "Close the cover, then press OK",
                    accentColor = DiveColors.Warning,
                    showLiveSensor = true,
                    sensorLabel = "Cover",
                    sensorValue = if (safety.coverOpen == false) "CLOSED ✓" else "OPEN",
                    sensorOk = safety.coverOpen == false,
                )
                SealState.LeakMonitoring -> StepPrompt(
                    icon = Icons.Rounded.CheckCircle,
                    title = "Monitoring Active",
                    subtitle = "Seal check running in background",
                    instruction = "Press Back to return to camera\nTimer is in the top bar",
                    accentColor = DiveColors.DiveCyan,
                )
                SealState.Passed -> ResultStep(
                    passed = true,
                    pressure = safety.barometricPressureKpa,
                )
                SealState.Warning -> StepPrompt(
                    icon = Icons.Rounded.Error,
                    title = "⚠ Warning",
                    subtitle = safety.warning ?: "Pressure anomaly detected",
                    instruction = "Press OK to acknowledge",
                    accentColor = DiveColors.Warning,
                )
                SealState.Failed -> ResultStep(
                    passed = false,
                    pressure = safety.barometricPressureKpa,
                )
            }
        }
    }
}

@Composable
private fun StepPrompt(
    icon: ImageVector,
    title: String,
    subtitle: String,
    instruction: String,
    accentColor: androidx.compose.ui.graphics.Color,
    pulsing: Boolean = false,
    showLiveSensor: Boolean = false,
    sensorLabel: String = "",
    sensorValue: String = "",
    sensorOk: Boolean = false,
) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "step_pulse")
        val a by transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "pulse",
        )
        a
    } else 1f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier
                .size(72.dp)
                .alpha(alpha),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            color = DiveColors.TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            color = DiveColors.TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        if (showLiveSensor) {
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        if (sensorOk) DiveColors.Success.copy(alpha = 0.1f)
                        else DiveColors.Warning.copy(alpha = 0.1f),
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        1.dp,
                        if (sensorOk) DiveColors.Success.copy(alpha = 0.3f)
                        else DiveColors.Warning.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (sensorOk) DiveColors.Success else DiveColors.Warning,
                            CircleShape,
                        ),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "$sensorLabel: $sensorValue",
                    color = if (sensorOk) DiveColors.Success else DiveColors.Warning,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = instruction,
            color = DiveColors.DiveCyan.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VacuumingStep(
    currentPressure: Double?,
    targetPressure: Double,
) {
    val progress = if (currentPressure != null) {
        val start = 101.3 // atmospheric
        ((start - currentPressure) / (start - targetPressure)).toFloat().coerceIn(0f, 1f)
    } else 0f

    // Pulsing animation for pump
    val transition = rememberInfiniteTransition(label = "pump_pulse")
    val pumpAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pump",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Air,
            contentDescription = null,
            tint = DiveColors.DiveCyan,
            modifier = Modifier
                .size(72.dp)
                .alpha(pumpAlpha),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Step 3: Pumping Air",
            color = DiveColors.TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            color = DiveColors.DiveCyan,
            trackColor = DiveColors.SurfaceBorder,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(8.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "%.1f kPa → %.1f kPa".format(
                currentPressure ?: 101.3, targetPressure
            ),
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Motor running. Will stop at target pressure.",
            color = DiveColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ResultStep(
    passed: Boolean,
    pressure: Double?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Icon(
            imageVector = if (passed) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            contentDescription = null,
            tint = if (passed) DiveColors.Success else DiveColors.Critical,
            modifier = Modifier.size(96.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (passed) "SEAL PASSED" else "SEAL FAILED",
            color = if (passed) DiveColors.Success else DiveColors.Critical,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (passed) "Housing is watertight"
            else "Pressure anomaly detected — check housing",
            color = DiveColors.TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        if (pressure != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Final: %.1f kPa".format(pressure),
                color = DiveColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Press OK to return to camera",
            color = DiveColors.DiveCyan.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun coverStatusText(coverOpen: Boolean?): String = when (coverOpen) {
    true -> "Cover is open — ready"
    false -> "Cover is closed — please open it"
    null -> "Cover state unknown"
}
