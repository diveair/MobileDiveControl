package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.theme.DiveColors

/**
 * The two batteries that can end a dive, side by side.
 *
 * Housing sits on the left because it is the one that strands you: when it dies the buttons stop
 * and the phone is sealed behind a port with no way to reach the screen. The phone dying is worse
 * for the footage but at least it fails visibly.
 *
 * Two readouts of the same shape are easy to confuse at a glance through a flooded mask, so each
 * carries two independent cues — a device glyph *and* a one-character `H`/`P` label. Either one
 * alone would be a single point of failure for a diver who is cold, task-loaded and reading a
 * 16 sp number in bad visibility.
 */
@Composable
fun DualBatteryIndicator(
    housingPercent: Int?,
    phonePercent: Int?,
    modifier: Modifier = Modifier,
) {
    val statusIconSize = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.fontSize.toDp() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        BatteryIndicator(
            percent = housingPercent,
            glyph = Icons.Rounded.Inventory2,
            label = "H",
            deviceName = "Housing",
        )
        Spacer(modifier = Modifier.width(7.dp))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(statusIconSize)
                .background(DiveColors.SurfaceBorder),
        )
        Spacer(modifier = Modifier.width(7.dp))
        BatteryIndicator(
            percent = phonePercent,
            glyph = Icons.Rounded.Smartphone,
            label = "P",
            deviceName = "Phone",
        )
    }
}

/**
 * One battery readout.
 *
 * [percent] is null until the device reports a level. That is deliberately not the same as zero:
 * rendering an unknown battery as "0%" in critical red is a false alarm every time the app starts
 * before the housing connects, and a diver who learns to ignore a red battery will ignore the real
 * one too. Unknown renders muted `--%`.
 */
@Composable
fun BatteryIndicator(
    percent: Int?,
    glyph: ImageVector,
    label: String,
    deviceName: String,
    modifier: Modifier = Modifier,
) {
    val known = percent != null
    val color = DiveColors.Success
    // Match the VACUUM label, including the user's font scaling, rather than fixed dp icons.
    val statusTextStyle = MaterialTheme.typography.labelSmall
    val statusIconSize = with(LocalDensity.current) { statusTextStyle.fontSize.toDp() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = if (known) "$deviceName battery $percent%" else "$deviceName battery unknown",
            tint = color,
            modifier = Modifier.size(statusIconSize),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            color = color,
            style = statusTextStyle,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = if (percent != null) "$percent%" else "--%",
            color = color,
            style = statusTextStyle,
            fontWeight = FontWeight.Bold,
        )
    }
}
