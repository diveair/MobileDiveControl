package com.mobiledivecontrol.ui.camera

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * "Auto" EV for video: smooth, cinematic auto exposure.
 *
 * Phone AE re-converges in a few frames, so every cloud, torch sweep or fin kick pumps the
 * brightness of a recording. This controller takes exposure away from AE and drives ISO and
 * shutter itself, from the analysis stream's luminance, with three properties a camera operator
 * would give a manual exposure pull:
 *
 * 1. **Dead band with hysteresis.** Small metering noise (|error| < [START_EV]) never moves
 *    exposure; once moving it settles to within [STOP_EV], so there is no hunting.
 * 2. **Critically damped motion.** Exposure (in log2 space, i.e. stops) follows a critically
 *    damped spring toward the target: x'' = w^2 (target - x) - 2 w x'. It eases in and out with
 *    no overshoot, the look of a hand pull. The velocity is also slew-limited ([MAX_SLEW_EV_PER_S]).
 * 3. **Shutter-angle priority.** The exposure product E = ISO * t is split with the shutter held
 *    at a 180 degree angle (t = 1 / (2 fps)) for natural motion blur. ISO moves first, inside
 *    [Limits]. Only when ISO hits a bound does the shutter leave 180 degrees: shorter in bright
 *    water (no ND filter underwater), longer in the dark, but never beyond the frame period.
 *
 * Everything is O(1) per frame except metering, which samples a fixed 32 x 24 grid
 * (768 samples) through precomputed sRGB-to-linear and centre-weight tables: about 3 us.
 * Not thread-safe: called from the single analysis thread.
 */
internal class CinematicExposureController {

    data class Limits(
        val isoMin: Int,
        val isoMax: Int,
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
        /** Recording frame period; the shutter can never exceed it (no frame-rate sag). */
        val framePeriodNs: Long,
    )

    data class Output(val iso: Int, val exposureNs: Long)

    private var logExposure = Double.NaN // log2(ISO * seconds)
    private var velocity = 0.0          // stops per second
    private var moving = false
    private var lastEmittedLog = Double.NaN
    private var lastTimestampNs = 0L
    private var lastEmitNs = 0L

    val isSeeded: Boolean get() = !logExposure.isNaN()

    /** Starts from what AE was doing, so entering Auto never jumps. */
    fun seed(iso: Int, exposureNs: Long) {
        if (iso <= 0 || exposureNs <= 0L) return
        logExposure = log2(iso * (exposureNs / 1e9))
        velocity = 0.0
        moving = false
        lastEmittedLog = Double.NaN
        lastTimestampNs = 0L
    }

    fun reset() {
        logExposure = Double.NaN
        velocity = 0.0
        moving = false
        lastEmittedLog = Double.NaN
        lastTimestampNs = 0L
    }

    /**
     * Advances the controller by one metered frame. Returns a new sensor exposure only when it
     * differs from the last emitted one by at least [EMIT_THRESHOLD_EV] (so the camera request
     * rate stays low), or null.
     */
    fun onFrame(meter: Meter, timestampNs: Long, limits: Limits, fps: Int): Output? {
        if (!isSeeded || meter.meanLinearLuma <= 0.0) return null
        val dt = if (lastTimestampNs == 0L) 1.0 / 30.0
            else ((timestampNs - lastTimestampNs) / 1e9).coerceIn(1e-3, 0.1)
        lastTimestampNs = timestampNs

        // Highlight protection: when more than 2% of the frame is clipped, aim lower (max -1 EV)
        // so speculars and the surface stay legible underwater.
        val clipExcess = (meter.clippedFraction - CLIP_ALLOWANCE).coerceAtLeast(0.0)
        val targetLuma = MID_GREY * 2.0.pow(-(clipExcess * CLIP_PENALTY_EV_PER_UNIT).coerceAtMost(1.0))
        val error = log2(targetLuma / meter.meanLinearLuma)

        if (!moving && abs(error) > START_EV) moving = true
        if (moving && abs(error) < STOP_EV && abs(velocity) < STOP_VELOCITY) moving = false

        if (moving) {
            val target = logExposure + error
            val accel = OMEGA * OMEGA * (target - logExposure) - 2.0 * OMEGA * velocity
            velocity = (velocity + accel * dt).coerceIn(-MAX_SLEW_EV_PER_S, MAX_SLEW_EV_PER_S)
        } else {
            velocity *= exp(-dt * OMEGA * 2.0) // coast to rest, never an abrupt stop
        }
        logExposure += velocity * dt

        val out = split(logExposure, limits, fps)
        // Feed the clamp back so the state never winds up beyond what the sensor can do.
        logExposure = log2(out.iso * (out.exposureNs / 1e9))
        if (!lastEmittedLog.isNaN() &&
            (abs(logExposure - lastEmittedLog) < EMIT_THRESHOLD_EV || timestampNs - lastEmitNs < MIN_EMIT_INTERVAL_NS)
        ) return null
        lastEmittedLog = logExposure
        lastEmitNs = timestampNs
        return out
    }

    /** Luminance summary of one analysis frame. */
    data class Meter(val meanLinearLuma: Double, val clippedFraction: Double)

    companion object {
        const val MID_GREY = 0.18
        const val START_EV = 0.33
        const val STOP_EV = 0.08
        const val STOP_VELOCITY = 0.05
        const val MAX_SLEW_EV_PER_S = 0.9
        /** Natural frequency of the exposure spring: ~1.6 s settle for a 1-stop change. */
        const val OMEGA = 2.6
        const val EMIT_THRESHOLD_EV = 1.0 / 48.0
        /** At most ~15 sensor updates per second: smooth to the eye, cheap for the HAL. */
        const val MIN_EMIT_INTERVAL_NS = 66_000_000L
        const val CLIP_ALLOWANCE = 0.02
        const val CLIP_PENALTY_EV_PER_UNIT = 8.0

        private val LN2 = ln(2.0)
        fun log2(x: Double): Double = ln(x) / LN2

        /** Splits log2(ISO*s) into ISO + shutter with 180 degree shutter priority. Pure. */
        fun split(logExposure: Double, limits: Limits, fps: Int): Output {
            val e = 2.0.pow(logExposure) // ISO * seconds
            val maxShutterS = minOf(limits.exposureMaxNs, limits.framePeriodNs) / 1e9
            val minShutterS = limits.exposureMinNs / 1e9
            val preferredS = (1.0 / (2.0 * fps.coerceAtLeast(1))).coerceIn(minShutterS, maxShutterS)
            var shutterS = preferredS
            var iso = e / shutterS
            if (iso > limits.isoMax) {
                iso = limits.isoMax.toDouble()
                shutterS = (e / iso).coerceIn(minShutterS, maxShutterS)
            } else if (iso < limits.isoMin) {
                iso = limits.isoMin.toDouble()
                shutterS = (e / iso).coerceIn(minShutterS, maxShutterS)
            }
            return Output(
                iso = iso.toInt().coerceIn(limits.isoMin, limits.isoMax),
                exposureNs = (shutterS * 1e9).toLong().coerceIn(limits.exposureMinNs, minOf(limits.exposureMaxNs, limits.framePeriodNs)),
            )
        }

        // ── Metering ────────────────────────────────────────────────────────────────────────
        private const val GRID_W = 32
        private const val GRID_H = 24

        /** sRGB 8-bit code -> linear light, computed once. */
        private val SRGB_TO_LINEAR = FloatArray(256) { code ->
            val c = code / 255.0
            (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
        }

        /** Gaussian centre weighting (sigma = 0.35 of the half-diagonal), normalised. */
        private val WEIGHTS: FloatArray = run {
            val w = FloatArray(GRID_W * GRID_H)
            var sum = 0.0
            for (y in 0 until GRID_H) for (x in 0 until GRID_W) {
                val dx = (x + 0.5) / GRID_W - 0.5
                val dy = (y + 0.5) / GRID_H - 0.5
                val v = exp(-(dx * dx + dy * dy) / (2 * 0.35 * 0.35 * 0.5))
                w[y * GRID_W + x] = v.toFloat()
                sum += v
            }
            for (i in w.indices) w[i] = (w[i] / sum).toFloat()
            w
        }

        /**
         * Centre-weighted mean linear luminance (Rec.709 weights) and clipped fraction of an
         * RGBA_8888 plane, sampling a fixed grid. [buffer] is read with absolute gets, so its
         * position is untouched.
         */
        fun meter(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int = 4): Meter {
            var luma = 0.0
            var clipped = 0
            val limit = buffer.limit()
            for (gy in 0 until GRID_H) {
                val y = ((gy + 0.5) * height / GRID_H).toInt().coerceIn(0, height - 1)
                val row = y * rowStride
                for (gx in 0 until GRID_W) {
                    val x = ((gx + 0.5) * width / GRID_W).toInt().coerceIn(0, width - 1)
                    val i = row + x * pixelStride
                    if (i + 2 >= limit) continue
                    val r = buffer.get(i).toInt() and 0xFF
                    val g = buffer.get(i + 1).toInt() and 0xFF
                    val b = buffer.get(i + 2).toInt() and 0xFF
                    if (r >= 250 || g >= 250 || b >= 250) clipped++
                    val y709 = 0.2126f * SRGB_TO_LINEAR[r] + 0.7152f * SRGB_TO_LINEAR[g] + 0.0722f * SRGB_TO_LINEAR[b]
                    luma += y709 * WEIGHTS[gy * GRID_W + gx]
                }
            }
            return Meter(luma, clipped.toDouble() / (GRID_W * GRID_H))
        }
    }
}
