package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.ui.SEAL_TOP
import kotlin.math.roundToInt

internal val LocalAlertRecording = staticCompositionLocalOf { false }

internal fun compactStopVisible(state: AppState): Boolean {
    val profile = airWarningStopProfile(state)
    return profile.stopBannerVisible && (profile.continuationPending || airAlertVisible(state) ||
        (profile.stopPresentation == DiveStopPresentation.Minimized && !profile.decompressionHold))
}

/** One measured stack owns both alerts; neither can draw over the other's text or controls. */
@Composable
fun DiveAlertLayer(state: AppState, useMetric: Boolean, onCommand: (DiveSettingsCommand) -> Unit,
    modifier: Modifier = Modifier, compactTop: Dp = SEAL_TOP) {
    val recording = state.camera.recording && !state.camera.recordingPaused
    val airVisible = airAlertVisible(state)
    val air = state.airAlerts.simulation?.air ?: state.airAlerts
    val profile = airWarningStopProfile(state)
    val compact = compactStopVisible(state)
    if (!profile.stopBannerVisible && !airVisible) return
    CompositionLocalProvider(LocalAlertRecording provides recording) {
        Box(modifier.fillMaxSize()) {
            if (airVisible && !recording && (air.entry != null || air.buddyConfirmation)) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
            }
            if (compact || airVisible) {
                Column(Modifier.fillMaxSize().padding(top = if (compact) compactTop else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (compact) DiveStopBanner(profile, useMetric, onCommand = onCommand,
                        forceCompact = airVisible, recording = recording, continuationOwnsOk = state.continuationOwnsOk(),
                        modifier = Modifier.padding(horizontal = 8.dp))
                    if (airVisible) Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.fitAlert(Orientation.Vertical)) {
                            AirAlertOverlay(state, onCommand, drawScrim = false, modifier =
                                if (airIsSimpleReminder(air)) Modifier else Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            } else {
                DiveStopBanner(profile, useMetric, onCommand = onCommand, recording = recording,
                    modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Fit the whole alert in its available slot, including its labels and controls. */
internal fun Modifier.fitAlert(orientation: Orientation): Modifier = layout { measurable, constraints ->
    val horizontal = orientation == Orientation.Horizontal
    val natural = if (horizontal) constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        else constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
    val card = measurable.measure(natural)
    val available = if (horizontal) constraints.maxWidth else constraints.maxHeight
    val size = if (horizontal) card.width else card.height
    val scale = if (size > available) available.toFloat() / size else 1f
    layout((card.width * scale).roundToInt(), (card.height * scale).roundToInt()) {
        card.placeWithLayer(0, 0) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
        }
    }
}
