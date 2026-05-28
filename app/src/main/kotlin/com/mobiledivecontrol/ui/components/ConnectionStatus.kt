package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.theme.DiveColors

/**
 * BLE signal strength bars — like phone signal indicator.
 * No text, just 4 bars showing connection quality.
 */
@Composable
fun ConnectionStatus(
    bleState: BleConnectionState,
    modifier: Modifier = Modifier,
) {
    val (activeBars, barColor) = when (bleState) {
        BleConnectionState.Idle -> 0 to DiveColors.TextMuted
        BleConnectionState.Failed -> 0 to DiveColors.Critical
        BleConnectionState.Scanning -> 1 to DiveColors.Warning
        BleConnectionState.Reconnecting -> 1 to DiveColors.Warning
        BleConnectionState.Connecting -> 2 to DiveColors.Warning
        BleConnectionState.DiscoveringServices -> 2 to DiveColors.Warning
        BleConnectionState.Subscribing -> 3 to DiveColors.DiveCyan
        BleConnectionState.Degraded -> 3 to DiveColors.DiveCyan
        BleConnectionState.Ready -> 4 to DiveColors.Success
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier.height(20.dp),
    ) {
        val heights = listOf(5, 9, 13, 17)
        heights.forEachIndexed { index, barHeight ->
            val barIndex = index + 1
            val isActive = barIndex <= activeBars
            val color = if (isActive) barColor else DiveColors.TextMuted.copy(alpha = 0.25f)

            Canvas(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight.dp),
            ) {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
            if (index < 3) Spacer(modifier = Modifier.width(2.dp))
        }
    }
}
