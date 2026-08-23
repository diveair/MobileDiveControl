package com.mobiledivecontrol.core

import kotlin.math.sqrt

/** Pure thresholds for releasing a settled autofocus plane; Android wiring lives in the app. */
object AutofocusHoldPolicy {
    /** About 5.7 degrees/second: above measured hand tremor, responsive to a slow reframe. */
    const val MOTION_THRESHOLD_RADIANS_PER_SECOND = 0.10

    /** A 640x480 centre-gradient sample is cheap; 12.5 Hz keeps detection below 300 ms. */
    const val HOLD_MONITOR_INTERVAL_MS = 80L

    /** Two settling frames prevent the lock result itself from looking like focus loss. */
    const val HOLD_MONITOR_GRACE_MS = 120L

    /** A sustained loss this large is a new subject/plane, not ordinary frame-to-frame noise. */
    const val SHARPNESS_RELEASE_RATIO = 0.78

    /** Two consecutive samples reject a single noisy frame without adding visible lag. */
    const val SHARPNESS_RELEASE_SAMPLES = 2

    /** Nominal detection budget; real wall time still depends on camera-frame scheduling. */
    const val STATIC_REFOCUS_DETECTION_BUDGET_MS =
        HOLD_MONITOR_GRACE_MS + HOLD_MONITOR_INTERVAL_MS * SHARPNESS_RELEASE_SAMPLES

    fun isIntentionalCameraMotion(x: Float, y: Float, z: Float): Boolean {
        val angularSpeed = sqrt((x * x + y * y + z * z).toDouble())
        return angularSpeed >= MOTION_THRESHOLD_RADIANS_PER_SECOND
    }

    fun isSustainedFocusLossSample(baseline: Double, current: Double): Boolean =
        baseline > 0.0 && current >= 0.0 && current < baseline * SHARPNESS_RELEASE_RATIO
}
