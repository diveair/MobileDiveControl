package com.mobiledivecontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.components.BatteryIndicator
import com.mobiledivecontrol.ui.components.ConnectionStatus
import com.mobiledivecontrol.ui.components.DepthGauge
import com.mobiledivecontrol.ui.components.TemperatureDisplay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun CameraHudOverlay(
    state: AppState,
    useMetric: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()

        OverlayPill(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BatteryIndicator(percent = state.housing.batteryPercent ?: 0)
                Spacer(modifier = Modifier.width(10.dp))
                ConnectionStatus(bleState = state.bleConnectionState)
            }
        }

        OverlayPill(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        ) {
            TemperatureDisplay(temperatureCelsius = state.safety.waterTemperatureC)
        }

        OverlayPill(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 16.dp),
        ) {
            Text(
                text = rememberClockText(),
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }

        val isCameraMode = state.mode in listOf(AppMode.CameraLive, AppMode.CameraAdjust)
        val bottomPadding = if (isCameraMode) 86.dp else 28.dp

        OverlayPill(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding),
        ) {
            DepthGauge(
                waterPressureKpa = state.safety.waterPressureKpa,
                atmosphericPressureKpa = state.safety.barometricPressureKpa,
                useMetric = useMetric,
            )
        }
    }
}

@Composable
private fun OverlayPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                color = DiveColors.DeepBlack.copy(alpha = 0.62f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
    }
}

@Composable
private fun rememberClockText(): String {
    var text by remember { mutableStateOf(formatTimestamp()) }

    LaunchedEffect(Unit) {
        while (true) {
            text = formatTimestamp()
            delay(1000)
        }
    }

    return text
}

private fun formatTimestamp(): String {
    return LocalDateTime.now(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    )
}
