package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.theme.DiveColors

/**
 * Compact seal status — icon only for the HUD top bar.
 * ✓ green = passed, ⚠ amber = warning/monitoring, ✕ red = failed, ? gray = unknown
 */
@Composable
fun SealStatusBadge(
    sealState: SealState,
    modifier: Modifier = Modifier,
) {
    val (icon, color) = when (sealState) {
        SealState.Passed -> Icons.Rounded.CheckCircle to DiveColors.Success
        SealState.Failed -> Icons.Rounded.Error to DiveColors.Critical
        SealState.Warning -> Icons.Rounded.Error to DiveColors.Warning
        SealState.LeakMonitoring -> Icons.Rounded.Shield to DiveColors.DiveCyan
        SealState.Vacuuming,
        SealState.MotorStopping,
        SealState.WaitingForCoverClosed,
        SealState.ReadyToVacuum,
        SealState.CoverOpen -> Icons.Rounded.Shield to DiveColors.Warning
        SealState.Unknown -> Icons.Rounded.HelpOutline to DiveColors.TextMuted
    }

    Icon(
        imageVector = icon,
        contentDescription = "Seal: $sealState",
        tint = color,
        modifier = modifier.size(18.dp),
    )
}
