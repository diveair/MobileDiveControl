package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.theme.DiveColors

/**
 * The one thing a diver must be able to read when the housing buttons stop responding: why.
 *
 * Without it the failure mode is silent — presses do nothing and the diver has no way to tell a
 * dropped link from a jammed button, at depth, through a mask, wearing gloves. So this is a solid
 * block of colour with short words, not an icon or a status dot.
 *
 * Colour follows the aviation caution/warning convention (black on amber, white on red) because
 * white on bright amber measures barely 2:1 and is the first thing to disappear in low visibility.
 * There is no animation: a flashing banner is harder to read and burns OLED power on a screen that
 * stays on for the whole dive.
 *
 * Renders nothing at all when the link is [BleConnectionState.Ready] — the viewfinder is the
 * product, and an always-present banner would eat it.
 */
@Composable
fun HousingLinkBanner(
    bleState: BleConnectionState,
    modifier: Modifier = Modifier,
    bluetoothEnabled: Boolean = true,
    housingBatteryPercent: Int? = null,
) {
    // A radio that is switched off says nothing about the housing, so it must never be reported as
    // a housing fault. It also outranks every link state below it: no scan can succeed until it is
    // fixed, and it is the diver's own two-tap fix.
    val alert = if (!bluetoothEnabled) {
        info("BLUETOOTH IS OFF", "Turn on Bluetooth to connect the housing")
    } else {
        alertFor(bleState, housingBatteryPercent) ?: return
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(
                color = alert.background.copy(alpha = 0.92f),
                // Bluetooth-off owns the full display width. Square horizontal edges make the
                // filled region genuinely edge-to-edge; rounded corners would leave four camera
                // slivers even after the parent removed its margins.
                shape = if (bluetoothEnabled) RoundedCornerShape(6.dp)
                else RoundedCornerShape(0.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Auto-shrinking, not clipping. "HOUSING DISCONNECTED" at 24 sp with letter spacing is
        // wider than a portrait phone, and the word that would fall off the end is the one that
        // says what happened. Small and complete beats large and truncated.
        AutoShrinkText(
            text = alert.headline,
            color = alert.foreground,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (alert.action != null) {
            AutoShrinkText(
                text = alert.action,
                color = alert.foreground,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * @param action what the diver should physically do. Null when there is nothing to do but wait —
 *   inventing an instruction for a state that resolves itself trains people to ignore the banner.
 */
internal data class LinkAlert(
    val headline: String,
    val action: String?,
    val background: Color,
    val foreground: Color,
)

internal fun alertFor(
    bleState: BleConnectionState,
    housingBatteryPercent: Int? = null,
): LinkAlert? = when (bleState) {
    BleConnectionState.Ready -> null
    BleConnectionState.Idle,
    BleConnectionState.Scanning,
        -> caution("HOUSING NOT CONNECTED", "Hold SHUTTER to turn on the housing and connect Bluetooth")
    BleConnectionState.Connecting,
    BleConnectionState.DiscoveringServices,
    BleConnectionState.Subscribing,
        -> caution("CONNECTING TO HOUSING", null)
    BleConnectionState.Degraded -> caution("HOUSING LINK DEGRADED", "Some sensors unavailable")
    BleConnectionState.Reconnecting ->
        warning("HOUSING DISCONNECTED", "Reconnecting — if the housing is off, hold SHUTTER for 3 seconds")
    BleConnectionState.Failed -> if (housingBatteryPercent == 0) {
        warning("HOUSING UNAVAILABLE", "Charge the housing, then hold SHUTTER to turn it on")
    } else {
        // A failed scan/connection says nothing about battery charge. Startup commonly passes
        // through Failed while Bluetooth permission is being established, so do not invent a
        // dead-battery diagnosis without an actual zero-percent reading from the housing.
        caution("HOUSING NOT CONNECTED", "Hold SHUTTER to turn on the housing and connect Bluetooth")
    }
}


/** Blue, not amber or red: this is information and an easy fix, not a fault or a danger. */
private fun info(headline: String, action: String?) = LinkAlert(
    headline = headline,
    action = action,
    background = DiveColors.DiveCyanDim,
    foreground = Color.White,
)

private fun caution(headline: String, action: String?) = LinkAlert(
    headline = headline,
    action = action,
    background = DiveColors.Warning,
    foreground = DiveColors.DeepBlack,
)

private fun warning(headline: String, action: String?) = LinkAlert(
    headline = headline,
    action = action,
    background = DiveColors.Critical,
    foreground = Color.White,
)
