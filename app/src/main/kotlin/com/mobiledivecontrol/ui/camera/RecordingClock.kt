package com.mobiledivecontrol.ui.camera

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Live recording telemetry, written by [CameraRuntimeController]'s recorder callbacks and read
 * by the HUD badge. Plain compose state rather than core AppState on purpose: the duration
 * ticks many times a second and has no business on the control-critical reducer path.
 */
object RecordingClock {
    val durationMs: MutableState<Long> = mutableStateOf(0L)
    val paused: MutableState<Boolean> = mutableStateOf(false)
}
