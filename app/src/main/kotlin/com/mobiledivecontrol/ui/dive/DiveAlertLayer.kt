package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.*

internal val LocalAlertRecording = staticCompositionLocalOf { false }

/** One measured stack owns both alerts; neither can draw over the other's text or controls. */
@Composable
fun DiveAlertLayer(state: AppState, useMetric: Boolean, onCommand: (DiveSettingsCommand) -> Unit,
    modifier: Modifier = Modifier) {
    val recording = state.camera.recording && !state.camera.recordingPaused
    val airVisible = airAlertVisible(state)
    val air = state.airAlerts.simulation?.air ?: state.airAlerts
    val profile = airWarningStopProfile(state)
    CompositionLocalProvider(LocalAlertRecording provides recording) {
        Box(modifier.fillMaxSize()) {
            if (airVisible) {
                if (!recording && (air.entry != null || air.buddyConfirmation)) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
                }
                Column(Modifier.align(Alignment.Center).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiveStopBanner(profile, useMetric, onCommand = onCommand,
                        forceCompact = true, recording = recording)
                    AirAlertOverlay(state, onCommand, drawScrim = false)
                }
            } else {
                val compact = profile.stopPresentation == DiveStopPresentation.Minimized && !profile.decompressionHold
                DiveStopBanner(profile, useMetric, onCommand = onCommand, recording = recording,
                    modifier = Modifier.align(if (compact) Alignment.TopCenter else Alignment.Center)
                        .padding(top = if (compact) 8.dp else 0.dp))
            }
        }
    }
}
