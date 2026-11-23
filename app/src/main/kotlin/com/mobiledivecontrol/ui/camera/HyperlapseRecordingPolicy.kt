package com.mobiledivecontrol.ui.camera

internal const val TIME_LAPSE_PLAYBACK_FPS = 30

internal enum class TimeLapseVideoCodec {
    H264,
    HEVC,
}

/** Accept persisted aliases while exposing only the two physically distinct encoders. */
internal fun hyperlapseVideoCodec(value: String?): TimeLapseVideoCodec = when (value) {
    "HEVC", "H.265", "HEVC / H.265", "H.265 / HEVC" -> TimeLapseVideoCodec.HEVC
    else -> TimeLapseVideoCodec.H264
}

/**
 * Explicit rungs stay exact. The public recorder cannot change capture rate mid-recording, so
 * Auto uses Samsung's measured 15x normal/long-exposure cadence and switches to 45x when the
 * native vendor result (or the user's Night selection) requests the night cadence. On the S24,
 * a 72-second native Auto recording with suggestion=0 encoded 147 frames at 30 fps (4.9 seconds),
 * which is the expected start/stop-quantized result of a 2 fps / 15x capture cadence.
 */
internal fun hyperlapseSpeedFactor(
    value: String?,
    dayNight: String?,
    samsungSuggestedMotionSpeedMode: Int? = null,
): Int = when (value) {
    "Night 45x" -> 45
    "Night 15x" -> 15
    "5x" -> 5
    "10x" -> 10
    "15x" -> 15
    "30x" -> 30
    "60x" -> 60
    else -> if (
        dayNight == "Night" || hyperlapseAutoUsesNightCadence(samsungSuggestedMotionSpeedMode)
    ) 45 else 15
}

internal fun hyperlapseCaptureRateFps(speedFactor: Int): Double =
    TIME_LAPSE_PLAYBACK_FPS.toDouble() / speedFactor.coerceIn(1, 60)

internal fun hyperlapseFrameIntervalSeconds(speedFactor: Int): Double =
    1.0 / hyperlapseCaptureRateFps(speedFactor)

internal fun hyperlapsePlaybackDurationMs(elapsedDurationMs: Long, speedFactor: Int): Long =
    elapsedDurationMs.coerceAtLeast(0L) / speedFactor.coerceAtLeast(1)

