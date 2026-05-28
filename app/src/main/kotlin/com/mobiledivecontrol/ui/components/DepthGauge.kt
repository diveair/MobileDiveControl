package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.theme.DiveColors

/**
 * Depth gauge — bottom center of camera UI.
 * No icon. Just clean text: "12.5 m" or "41.0 ft".
 * Color-coded by depth range.
 */
@Composable
fun DepthGauge(
    waterPressureKpa: Double?,
    atmosphericPressureKpa: Double?,
    useMetric: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val depthMeters = if (waterPressureKpa != null && atmosphericPressureKpa != null) {
        val diff = (waterPressureKpa - atmosphericPressureKpa).coerceAtLeast(0.0)
        diff / 9.81
    } else {
        null
    }

    val displayText = when {
        depthMeters == null -> if (useMetric) "-- m" else "-- ft"
        useMetric -> "%.1f m".format(depthMeters)
        else -> "%.1f ft".format(depthMeters * 3.28084)
    }

    val color = when {
        depthMeters == null -> DiveColors.TextMuted
        depthMeters < 0.5 -> DiveColors.DiveCyan
        depthMeters < 30.0 -> DiveColors.DiveCyan
        depthMeters < 40.0 -> DiveColors.Warning
        else -> DiveColors.Critical
    }

    Text(
        text = displayText,
        color = color,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}
