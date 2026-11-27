package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import com.mobiledivecontrol.core.PRESSURE_STALE_MS
import com.mobiledivecontrol.core.pressureMonotonicMs
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.delay

internal const val CONNECTED_NOTICE_MS = 1_500L
private const val DISCONNECT_NOTICE_GRACE_MS = 2_500L

internal enum class LinkNotice { Connected, AwaitingDisconnect }
internal data class TimedLinkNotice(val notice: LinkNotice, val untilMs: Long)

private val connectionMayCover = setOf(Banner.B00, Banner.B01, Banner.B03, Banner.B11, Banner.B12,
    Banner.Cap, Banner.Status, Banner.Reached, Banner.ConfirmRelease, Banner.Release)

/** Presentation only. Seal monitoring and motor interlocks run on the original telemetry deadlines. */
internal fun timedLinkNotice(state: AppState, now: Long): TimedLinkNotice? {
    if (state.bleConnectionState != BleConnectionState.Ready) return null
    val seal = state.safety.sealMonitor
    if (seal.invalidWater || seal.invalidBaro || seal.banner !in connectionMayCover) return null
    val connectedUntil = state.housing.connectedAtMs?.plus(CONNECTED_NOTICE_MS)
    if (connectedUntil != null && now < connectedUntil) return TimedLinkNotice(LinkNotice.Connected, connectedUntil)

    if (seal.banner != Banner.B12 && seal.banner != Banner.B11) return null
    val water = state.waterPressureTelemetry ?: return null
    val baro = state.barometricPressureTelemetry ?: return null
    val missing = if (seal.banner == Banner.B12) water else baro
    if (missing.isFresh(now)) return null // A live but invalid or borderline reading is a sensor warning.
    val newest = maxOf(water.receivedAtMonotonicMs, baro.receivedAtMonotonicMs)
    if (now - newest < PRESSURE_STALE_MS / 2) return null // The other stream is still reporting.
    val until = missing.receivedAtMonotonicMs + PRESSURE_STALE_MS + DISCONNECT_NOTICE_GRACE_MS
    return if (now < until) TimedLinkNotice(LinkNotice.AwaitingDisconnect, until) else null
}

@Composable
internal fun rememberLinkNotice(state: AppState): LinkNotice? {
    var expiredAt by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val timed = timedLinkNotice(state, maxOf(expiredAt, pressureMonotonicMs()))
    val deadline = timed?.untilMs
    LaunchedEffect(deadline) {
        if (deadline != null) {
            delay((deadline - pressureMonotonicMs()).coerceAtLeast(0L))
            expiredAt = deadline
        }
    }
    return timed?.notice
}

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
 * A successful connection shows a brief green confirmation before returning to the viewfinder.
 */
@Composable
fun HousingLinkBanner(
    bleState: BleConnectionState,
    modifier: Modifier = Modifier,
    bluetoothEnabled: Boolean = true,
    housingBatteryPercent: Int? = null,
    reconnectingSession: Boolean = false,
    showConnected: Boolean = false,
) {
    // A radio that is switched off says nothing about the housing, so it must never be reported as
    // a housing fault. It also outranks every link state below it: no scan can succeed until it is
    // fixed, and it is the diver's own two-tap fix.
    val alert = if (!bluetoothEnabled) {
        info("BLUETOOTH IS OFF", "Turn on Bluetooth to connect the housing")
    } else {
        alertFor(bleState, housingBatteryPercent, reconnectingSession, showConnected) ?: return
    }

    if (alert == disconnectedAlert) {
        MessageBanner(alert.background.copy(alpha = 0.92f), modifier)
    } else {
        MessageBanner(alert.background.copy(alpha = 0.92f), modifier) { HousingLinkText(alert) }
    }
}

/** Each banner is measured against the actual disconnected message at the current width and font scale. */
@Composable
internal fun MessageBanner(
    background: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Layout(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(background, shape).then(if (border == null) Modifier else Modifier.border(border, shape)),
        content = {
            Box(if (content == null) Modifier else Modifier.clearAndSetSemantics {}) { HousingLinkText(disconnectedAlert) }
            if (content != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
        },
    ) { children, constraints ->
        val natural = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val reference = children.first().measure(natural)
        val height = constraints.constrainHeight(reference.height)
        val body = children.getOrNull(1)?.measure(natural) ?: reference
        layout(reference.width, height) {
            if (body.height <= height) {
                body.placeRelative((reference.width - body.width) / 2, (height - body.height) / 2)
            } else {
                // Fit the entire message, including interactive controls, inside the reference frame.
                val scale = height.toFloat() / body.height
                body.placeWithLayer(((reference.width - body.width * scale) / 2).roundToInt(), 0) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                }
            }
        }
    }
}

@Composable
private fun HousingLinkText(alert: LinkAlert) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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

private val disconnectedAlert = caution("HOUSING NOT CONNECTED", "Hold SHUTTER to turn on the housing and connect Bluetooth")

internal fun alertFor(
    bleState: BleConnectionState,
    housingBatteryPercent: Int? = null,
    reconnectingSession: Boolean = false,
    showConnected: Boolean = false,
): LinkAlert? = when (bleState) {
    BleConnectionState.Ready -> if (showConnected)
        LinkAlert("CONNECTED TO HOUSING", null, DiveColors.Success, DiveColors.DeepBlack) else null
    BleConnectionState.Idle,
    BleConnectionState.Reconnecting,
    BleConnectionState.Scanning,
        -> disconnectedAlert
    BleConnectionState.Connecting,
    BleConnectionState.DiscoveringServices,
    BleConnectionState.Subscribing,
        -> LinkAlert(if (reconnectingSession) "RECONNECTING TO HOUSING" else "CONNECTING TO HOUSING",
            null, DiveColors.OceanBlue, Color.White)
    BleConnectionState.Degraded -> caution("HOUSING LINK DEGRADED", "Some sensors unavailable")
    BleConnectionState.Failed -> if (housingBatteryPercent == 0) {
        warning("HOUSING BATTERY EMPTY", "Charge the housing, then hold SHUTTER to turn it on")
    } else {
        // A failed scan/connection says nothing about battery charge. Startup commonly passes
        // through Failed while Bluetooth permission is being established, so do not invent a
        // dead-battery diagnosis without an actual zero-percent reading from the housing.
        disconnectedAlert
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
