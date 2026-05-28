package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.theme.DiveColors

/**
 * Compact temperature display — sits next to battery in top bar.
 * Color-coded: blue (<18°C), cyan (18-22), green (22-28), amber (>28).
 */
@Composable
fun TemperatureDisplay(
    temperatureCelsius: Double?,
    modifier: Modifier = Modifier,
) {
    val displayText = if (temperatureCelsius != null) {
        "%.1f°".format(temperatureCelsius)
    } else {
        "--°"
    }

    val color = when {
        temperatureCelsius == null -> DiveColors.TextMuted
        temperatureCelsius < 18.0 -> DiveColors.CoolBlue
        temperatureCelsius < 22.0 -> DiveColors.DiveCyan
        temperatureCelsius < 28.0 -> DiveColors.Success
        else -> DiveColors.Warning
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Rounded.Thermostat,
            contentDescription = "Water temperature",
            tint = color,
            modifier = Modifier.width(14.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = displayText,
            color = color,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
