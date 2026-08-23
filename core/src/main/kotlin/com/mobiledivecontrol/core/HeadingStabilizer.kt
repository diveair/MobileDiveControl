package com.mobiledivecontrol.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Motion-aware circular heading filter.
 *
 * A magnetometer can wander while the phone is physically still; an ordinary low-pass filter
 * merely follows that wander more slowly. This filter instead uses gyroscope motion as the
 * authority for whether yaw is allowed to move. During a real rotation it follows promptly and
 * settles smoothly. Once the gyro has remained quiet for [SETTLE_WINDOW_MS], the heading is held
 * exactly until physical rotation resumes.
 */
class HeadingStabilizer {
    private var stableHeading: Double? = null
    private var lastSampleAtMs: Long? = null
    private var lastMotionAtMs: Long? = null

    fun update(
        rawHeadingDegrees: Double,
        angularSpeedRadPerSecond: Double,
        sampledAtMs: Long,
    ): Double {
        val raw = HeadingMath.normalize(rawHeadingDegrees)
        val current = stableHeading
        if (current == null) {
            stableHeading = raw
            lastSampleAtMs = sampledAtMs
            // Let the initial sensor fusion settle before establishing the first hard hold.
            lastMotionAtMs = sampledAtMs
            return raw
        }

        val previousSampleAt = lastSampleAtMs ?: sampledAtMs
        val elapsedMs = (sampledAtMs - previousSampleAt).coerceIn(1L, MAX_SAMPLE_GAP_MS)
        lastSampleAtMs = sampledAtMs
        if (angularSpeedRadPerSecond >= MOTION_THRESHOLD_RAD_PER_SECOND) {
            lastMotionAtMs = sampledAtMs
        }

        val followingMotion = lastMotionAtMs?.let { sampledAtMs - it <= SETTLE_WINDOW_MS } == true
        if (!followingMotion) return current

        val delta = HeadingMath.shortestDelta(current, raw)
        if (abs(delta) <= HEADING_DEADBAND_DEGREES) return current

        // Remove the deadband before filtering so crossing it cannot produce a visible step.
        val actionableDelta = sign(delta) * (abs(delta) - HEADING_DEADBAND_DEGREES)
        val alpha = 1.0 - exp(-elapsedMs.toDouble() / FOLLOW_TIME_CONSTANT_MS)
        return HeadingMath.normalize(current + actionableDelta * alpha).also {
            stableHeading = it
        }
    }

    fun reset() {
        stableHeading = null
        lastSampleAtMs = null
        lastMotionAtMs = null
    }

    private companion object {
        /** QTI gyro noise is far below this; even a deliberate ~1 degree/second pan crosses it. */
        const val MOTION_THRESHOLD_RAD_PER_SECOND = 0.015

        /** Keep following briefly after the gyro quiets so the rotation-vector fusion can settle. */
        const val SETTLE_WINDOW_MS = 420L

        /** Fast enough to feel attached to the camera, slow enough to remove one-frame yaw steps. */
        const val FOLLOW_TIME_CONSTANT_MS = 85.0

        /** Changes below this are sensor/display noise, not useful navigation information. */
        const val HEADING_DEADBAND_DEGREES = 0.35
        const val MAX_SAMPLE_GAP_MS = 100L
    }
}
