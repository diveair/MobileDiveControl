package com.mobiledivecontrol.ui.camera

import android.net.Uri
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
    /** URI exists after all finalised session segments have been assembled for preview. */
    val reviewUri: MutableState<Uri?> = mutableStateOf(null)
    val reviewFinalizing: MutableState<Boolean> = mutableStateOf(false)
}
