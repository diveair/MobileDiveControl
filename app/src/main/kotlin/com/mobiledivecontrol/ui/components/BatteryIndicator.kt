package com.mobiledivecontrol.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery0Bar
import androidx.compose.material.icons.rounded.Battery2Bar
import androidx.compose.material.icons.rounded.Battery4Bar
import androidx.compose.material.icons.rounded.Battery6Bar
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.theme.DiveColors

@Composable
fun BatteryIndicator(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        targetValue = DiveColors.batteryColor(percent),
        animationSpec = tween(500),
        label = "battery_color",
    )

    // Pulse when critically low
    val alpha = if (percent <= 10) {
        val infiniteTransition = rememberInfiniteTransition(label = "battery_pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "battery_pulse_alpha",
        )
        pulseAlpha
    } else {
        1f
    }

    val icon = when {
        percent <= 10 -> Icons.Rounded.Battery0Bar
        percent <= 30 -> Icons.Rounded.Battery2Bar
        percent <= 55 -> Icons.Rounded.Battery4Bar
        percent <= 80 -> Icons.Rounded.Battery6Bar
        else -> Icons.Rounded.BatteryFull
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.alpha(alpha),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Battery $percent%",
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$percent%",
            color = color,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}
