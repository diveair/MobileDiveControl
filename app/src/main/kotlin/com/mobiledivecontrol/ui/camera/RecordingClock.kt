package com.mobiledivecontrol.ui.camera

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Live recording telemetry, written by [CameraRuntimeController]'s recorder callbacks and read
 * by the HUD badge. Plain compose state rather than core AppState on purpose: the duration
 * ticks many times a second and has no business on the control-critical reducer path.
 */
object RecordingClock {
    /** Real wall-clock time spent capturing the current logical recording. */
    val durationMs: MutableState<Long> = mutableLongStateOf(0L)
    /** Encoded playback length for Hyperlapse; identical to [durationMs] in ordinary video. */
    val playbackDurationMs: MutableState<Long> = mutableLongStateOf(0L)
    /** Selected Hyperlapse acceleration, used to derive output time between recorder callbacks. */
    val timeLapseSpeedFactor: MutableState<Int> = mutableIntStateOf(1)
    val paused: MutableState<Boolean> = mutableStateOf(false)
    /** URI exists after all finalised session segments have been assembled for preview. */
    val reviewUri: MutableState<Uri?> = mutableStateOf(null)
    val reviewFinalizing: MutableState<Boolean> = mutableStateOf(false)
}
