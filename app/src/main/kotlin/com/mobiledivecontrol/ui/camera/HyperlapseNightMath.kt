package com.mobiledivecontrol.ui.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How [HyperlapseNightAccumulator] merges the camera frames of one output window. */
internal enum class NightAccumulationMode {
    /** Running mean of every frame in the window: noise falls by ~sqrt(frames). */
    AVERAGE,

    /**
     * Running mean plus a noise-gated per-channel maximum: moving lights leave trails while static
     * dark areas keep the clean mean. See [HyperlapseNightMath.trailsComposite].
     */
    TRAILS,
}

/** How the night preview is placed inside the preview view. */
internal enum class NightPreviewScale {
    /** Whole frame visible, letterboxed (black bars). */
    FIT,

    /** View fully covered, centre-cropped (PreviewView FILL_CENTER behaviour). */
    FILL,
}

/**
 * Pure (JVM-testable) math shared by [HyperlapseNightAccumulator] and its shaders. Nothing here
 * touches Android classes.
 */
internal object HyperlapseNightMath {
    /** Upper bound of [HyperlapseNightAccumulator.setExposureGain]. */
    const val MAX_EXPOSURE_GAIN = 16f

    /**
     * Trails noise gate, in display-referred (gamma-encoded) 0..1 units. A pixel's peak above its
     * window mean counts as a light trail once the strongest channel's excursion passes
     * [TRAILS_GATE_LOW] and fully at [TRAILS_GATE_HIGH]. The maximum of N noisy frames sits about
     * 2-2.5 sigma above the mean (sigma ~0.02-0.04 for ISO 1600-3200 night video), so the lower
     * edge rejects noise while headlights, torches and stars moving across a pixel (excursions of
     * 0.3-0.9) pass untouched.
     */
    const val TRAILS_GATE_LOW = 0.06f
    const val TRAILS_GATE_HIGH = 0.20f

    /** Camera frames merged into one encoded frame: round(sensorFps / captureFps), at least 1. */
    fun framesPerOutput(sensorFps: Double, captureFps: Double): Int {
        if (!(sensorFps > 0.0) || !(captureFps > 0.0)) return 1
        return (sensorFps / captureFps).coerceIn(1.0, 100_000.0).roundToInt().coerceAtLeast(1)
    }

    /** Nanoseconds between two kept frames of a MediaRecorder configured with setCaptureRate(captureFps). */
    fun outputIntervalNs(captureFps: Double): Long =
        if (captureFps > 0.0) (1_000_000_000.0 / captureFps).roundToLong() else 0L

    /**
     * Soft-knee tone curve constant. The curve is
     *
     *     f(x) = (1 - exp(-k x)) / (1 - exp(-k))
     *
     * which maps 0 -> 0 and 1 -> 1 (highlights never clip) with shadow slope
     * f'(0) = k / (1 - exp(-k)). This returns the k whose shadow slope equals [gain], i.e. the root
     * of h(k) = k - gain * (1 - exp(-k)). h is convex and increasing beyond its root, and
     * h(gain) = gain * exp(-gain) > 0, so Newton from k = gain converges monotonically.
     * Returns 0 (identity curve) for gain <= 1.
     */
    fun toneCurveK(gain: Float): Float {
        val g = if (gain.isNaN()) 1.0 else gain.toDouble().coerceIn(1.0, MAX_EXPOSURE_GAIN.toDouble())
        if (g <= 1.0005) return 0f
        var k = g
        for (iteration in 0 until 64) {
            val e = exp(-k)
            val next = k - (k - g * (1.0 - e)) / (1.0 - g * e)
            if (abs(next - k) < 1e-10) {
                k = next
                break
            }
            k = next
        }
        return k.toFloat()
    }

    /** 1 / (1 - exp(-k)), the curve normaliser uploaded with k; 1 for the identity curve. */
    fun toneCurveNorm(k: Float): Float =
        if (k <= 0f) 1f else (1.0 / (1.0 - exp(-k.toDouble()))).toFloat()

    /** Scalar reference of the shader tone curve (the shader applies it to max(R,G,B), hue-preserving). */
    fun toneCurve(x: Float, k: Float): Float {
        if (k <= 0f) return x.coerceIn(0f, 1f)
        val v = x.coerceIn(0f, 1f).toDouble()
        return ((1.0 - exp(-k * v)) * toneCurveNorm(k)).toFloat().coerceIn(0f, 1f)
    }

    /** GLSL smoothstep. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Trails blend (single-channel reference of the shader; the shader gates on the strongest
     * channel's excursion so saturated red tail-lights are kept):
     *
     *     excursion = max(maxAccum - mean, 0)
     *     out       = mean + excursion * smoothstep(GATE_LOW, GATE_HIGH, excursion)
     *
     * i.e. a "lighten" blend whose contribution is noise-gated: where a light passed through a
     * pixel the result is the light's full brightness (out -> maxAccum), elsewhere it is the clean
     * mean (out -> mean). Pure max-stacking would instead lift every dark pixel by the maximum of
     * its noise.
     */
    fun trailsComposite(mean: Float, maxAccum: Float): Float {
        val excursion = max(maxAccum - mean, 0f)
        return mean + excursion * smoothstep(TRAILS_GATE_LOW, TRAILS_GATE_HIGH, excursion)
    }

    /**
     * Writes the GL viewport (x, y, width, height) into [out] for drawing content of
     * [contentAspect] (width / height, as it should appear on screen) into an EGL window buffer of
     * [bufferWidth] x [bufferHeight] that the compositor stretches to a view of
     * [viewWidth] x [viewHeight]. The fit is computed in view space (so a fixed-size SurfaceView
     * buffer is handled) and then mapped to buffer pixels. FILL rectangles may extend beyond the
     * buffer; GL clips them.
     */
    fun previewViewport(
        out: IntArray,
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        contentAspect: Float,
        scale: NightPreviewScale,
    ) {
        val vw = viewWidth.coerceAtLeast(1).toFloat()
        val vh = viewHeight.coerceAtLeast(1).toFloat()
        val viewAspect = vw / vh
        val aspect = if (contentAspect > 0f && contentAspect.isFinite()) contentAspect else viewAspect
        val widthLimited = (aspect >= viewAspect) == (scale == NightPreviewScale.FIT)
        val w: Float
        val h: Float
        if (widthLimited) {
            w = vw
            h = vw / aspect
        } else {
            h = vh
            w = vh * aspect
        }
        val sx = bufferWidth.coerceAtLeast(1) / vw
        val sy = bufferHeight.coerceAtLeast(1) / vh
        out[0] = ((vw - w) * 0.5f * sx).roundToInt()
        out[1] = ((vh - h) * 0.5f * sy).roundToInt()
        out[2] = (w * sx).roundToInt().coerceAtLeast(1)
        out[3] = (h * sy).roundToInt().coerceAtLeast(1)
    }
}

/**
 * Decides which camera frame closes an output window. GL-thread confined (not thread-safe).
 *
 * Count mode ([outputIntervalNs] == 0): every [framesPerOutput]-th frame closes the window.
 *
 * Interval mode ([outputIntervalNs] > 0, recommended): windows close on a fixed timestamp grid
 * `anchor + k * interval` (anchor = first frame + interval), at the frame nearest each grid point
 * (first frame with `ts >= deadline - min(frameDuration / 2, interval / 4)`). Emissions therefore
 * stay within about one frame duration of MediaRecorder's own snapped time-lapse grid, so its
 * GraphicBufferSource keeps every emitted frame and assigns consecutive output slots, regardless of
 * AE frame-rate changes. A gap longer than an interval (dropped frames) re-anchors the grid.
 */
internal class NightOutputCadence {
    var framesPerOutput: Int = 1
        set(value) { field = value.coerceAtLeast(1) }

    var outputIntervalNs: Long = 0L
        set(value) {
            val next = value.coerceAtLeast(0L)
            if (field != next) {
                field = next
                deadlineNs = UNSET
            }
        }

    /** Frames accumulated in the current window (0 right after a window closed). */
    var framesInWindow: Int = 0
        private set

    private var deadlineNs = UNSET
    private var lastTimestampNs = UNSET
    private var frameDurationNs = 0L

    /** Call before accumulating a frame; returns its 1-based index n in the window (weight 1/n). */
    fun beginFrame(): Int {
        framesInWindow++
        return framesInWindow
    }

    /** Call after accumulating the frame; true when this frame closes the window (emit + reset). */
    fun endFrame(timestampNs: Long): Boolean {
        if (lastTimestampNs != UNSET) {
            val delta = timestampNs - lastTimestampNs
            if (delta > 0L) frameDurationNs = delta else deadlineNs = UNSET
        }
        lastTimestampNs = timestampNs
        val interval = outputIntervalNs
        val emit = if (interval <= 0L) {
            framesInWindow >= framesPerOutput
        } else if (deadlineNs == UNSET) {
            deadlineNs = timestampNs + interval
            false
        } else {
            val tolerance = min(frameDurationNs / 2, interval / 4)
            if (timestampNs >= deadlineNs - tolerance) {
                deadlineNs += interval
                if (deadlineNs - tolerance <= timestampNs) deadlineNs = timestampNs + interval
                true
            } else {
                false
            }
        }
        if (emit) framesInWindow = 0
        return emit
    }

    /** Discards the current window (e.g. on a blend-mode change); the timestamp grid is kept. */
    fun restartWindow() {
        framesInWindow = 0
    }

    fun reset() {
        framesInWindow = 0
        deadlineNs = UNSET
        lastTimestampNs = UNSET
        frameDurationNs = 0L
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
