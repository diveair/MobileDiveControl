package com.mobiledivecontrol.ui.camera

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

/**
 * One function per camera control, shared by every capture pipeline.
 *
 * DiveControl drives the camera through several request writers: the CameraX option bundle
 * (autofocus), the direct Camera2 session (manual focus), [PreparedCamera2Recorder] (Hyperlapse,
 * Slow Motion, high-rate Pro Video, native LOG) and [Camera2EightKRecorder]. They used to carry
 * their own copies of the exposure / white-balance / flash logic, and the copies drifted: torch
 * never lit in video autofocus, 8K ignored manual ISO, LOG and Hyperlapse dropped the lens. Now
 * the runtime resolves CameraState into a [LookPlan] and each migrated writer calls
 * [CameraControls.writeLook] through a [RequestSink], with explicit overrides for constrained high-speed sessions.
 * The catalog clips controls to the selected pipeline.
 *
 * Focus is deliberately NOT part of this file: its writers (manual focus, the wheel fast path, the
 * AF pull/hold/tracking ramp) are the field-proven reference and are called unchanged.
 */
internal interface RequestSink {
    fun <T : Any> set(key: CaptureRequest.Key<T>, value: T)
}

/** Adapter for a raw Camera2 builder (direct session, prepared recorders, 8K). */
internal class Camera2RequestSink(private val builder: CaptureRequest.Builder) : RequestSink {
    override fun <T : Any> set(key: CaptureRequest.Key<T>, value: T) {
        builder.set(key, value)
    }
}

/** Adapter for CameraX's interop option bundle (autofocus path, merged into CameraX requests). */
@ExperimentalCamera2Interop
internal class CameraXRequestSink(private val builder: CaptureRequestOptions.Builder) : RequestSink {
    override fun <T : Any> set(key: CaptureRequest.Key<T>, value: T) {
        builder.setCaptureRequestOption(key, value)
    }
}

/** What auto exposure is asked to do, or the explicit sensor exposure that replaces it. */
internal sealed interface ExposurePlan {
    /**
     * AE on. [evIndex] is the compensation index (null = leave the template value).
     * [writeAeMode] is false on the CameraX bundle: CameraX derives AE_MODE from the still
     * flash mode (ON_AUTO_FLASH / ON_ALWAYS_FLASH), and forcing plain ON would break auto flash.
     */
    data class Auto(
        val evIndex: Int?,
        val meteringRegion: MeteringRectangle?,
        val writeAeMode: Boolean = true,
    ) : ExposurePlan

    /** AE off. Camera2 requires BOTH sensitivity and exposure time in an AE-off request. */
    data class Manual(val iso: Int, val exposureNs: Long, val frameDurationNs: Long?) : ExposurePlan
}

/** Who owns colour for this frame. One precedence function produces it (see runtime). */
internal sealed interface WhitePointPlan {
    data class HalAuto(val locked: Boolean) : WhitePointPlan
    data class Gains(val gains: RggbChannelVector, val transform: ColorSpaceTransform?) : WhitePointPlan
    /** Samsung's own Kelvin renderer (AWB mode 101 + colorTemperature); native LOG only. */
    data class VendorKelvin(val kelvin: Int) : WhitePointPlan
}

/**
 * Everything about a frame's look, resolved once per state change. Immutable and structurally
 * comparable so recorders can skip identical re-submissions (`Controls == Controls`).
 *
 * @param controlMode null = leave CONTROL_MODE untouched (a bound vendor extension owns it).
 * @param torch null = this writer does not own the flash unit (CameraX TorchControl does).
 * @param stabilizationMode null = the pipeline fixes stabilization at session creation.
 */
internal data class LookPlan(
    val controlMode: Int?,
    val sceneMode: Int?,
    val exposure: ExposurePlan,
    val fpsRange: Range<Int>?,
    val whitePoint: WhitePointPlan,
    val torch: Boolean?,
    val stabilizationMode: Int?,
    val superSteady: Boolean = false,
)

internal object CameraControls {

    fun writeScene(sink: RequestSink, plan: LookPlan) {
        val mode = plan.controlMode ?: return
        sink.set(CaptureRequest.CONTROL_MODE, mode)
        if (mode == CameraMetadata.CONTROL_MODE_USE_SCENE_MODE) {
            plan.sceneMode?.let { sink.set(CaptureRequest.CONTROL_SCENE_MODE, it) }
        }
    }

    fun writeExposure(sink: RequestSink, exposure: ExposurePlan) {
        when (exposure) {
            is ExposurePlan.Manual -> {
                sink.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                sink.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso)
                sink.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureNs)
                exposure.frameDurationNs?.let { sink.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
            }
            is ExposurePlan.Auto -> {
                if (exposure.writeAeMode) sink.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                exposure.meteringRegion?.let { sink.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
                exposure.evIndex?.let { sink.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
            }
        }
    }

    fun writeFrameRate(sink: RequestSink, range: Range<Int>?) {
        range?.let { sink.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
    }

    fun writeWhiteBalance(sink: RequestSink, whitePoint: WhitePointPlan) {
        when (whitePoint) {
            is WhitePointPlan.HalAuto -> {
                sink.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                sink.set(CaptureRequest.CONTROL_AWB_LOCK, whitePoint.locked)
            }
            is WhitePointPlan.Gains -> {
                sink.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                sink.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                sink.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                sink.set(CaptureRequest.COLOR_CORRECTION_GAINS, whitePoint.gains)
                whitePoint.transform?.let { sink.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
            }
            is WhitePointPlan.VendorKelvin -> {
                sink.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                sink.set(CaptureRequest.CONTROL_AWB_MODE, SAMSUNG_AWB_MODE_KELVIN)
                runCatching { sink.set(colorTemperatureKey, whitePoint.kelvin.coerceIn(2300, 10000)) }
            }
        }
    }

    fun writeFlash(sink: RequestSink, torch: Boolean?) {
        torch ?: return
        sink.set(
            CaptureRequest.FLASH_MODE,
            if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )
    }

    fun writeStabilization(sink: RequestSink, mode: Int?) {
        mode?.let { sink.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
    }

    /** The whole look in one fixed, documented order; every pipeline uses exactly this. */
    fun writeLook(sink: RequestSink, plan: LookPlan) {
        writeScene(sink, plan)
        writeStabilization(sink, plan.stabilizationMode)
        if (plan.superSteady) sink.set(SamsungSuperSteady.requestKey, 1.toByte())
        writeWhiteBalance(sink, plan.whitePoint)
        writeExposure(sink, plan.exposure)
        writeFrameRate(sink, plan.fpsRange)
        writeFlash(sink, plan.torch)
    }

    // ── Pure helpers ──────────────────────────────────────────────────────────

    /**
     * EV in stops to a compensation index. Rounded to the nearest step and clamped to [range]
     * (Samsung's vendor window is ±4.0 EV, the public one ±2.0).
     */
    fun evIndex(ev: Double, step: Double, range: Range<Int>): Int {
        val safeStep = if (step > 0.0) step else 0.1
        return Math.round(ev / safeStep).toInt().coerceIn(range.lower, range.upper)
    }

    /**
     * The AE target range for a requested frame rate. Fixed `[fps,fps]` gives constant cadence;
     * [variable] (Auto FPS) prefers the widest range whose UPPER bound is fps, letting AE lengthen
     * exposure in low light exactly like Samsung's Auto FPS. One linear pass, no allocation beyond
     * the comparator-free scan.
     */
    fun selectFpsRange(ranges: Array<Range<Int>>, fps: Int, variable: Boolean): Range<Int>? {
        var best: Range<Int>? = null
        var bestScore = Long.MAX_VALUE
        for (range in ranges) {
            if (fps < range.lower || fps > range.upper) continue
            val score: Long = if (variable) {
                // Upper must equal fps; then the lowest floor wins (more low-light latitude).
                (if (range.upper == fps) 0L else 1_000_000L) + range.lower.toLong()
            } else {
                // Exact fixed range first, then the narrowest, then the closest upper bound.
                (if (range.lower == fps && range.upper == fps) 0L else 1_000_000L) +
                    (range.upper - range.lower).toLong() * 1_000L + kotlin.math.abs(range.upper - fps).toLong()
            }
            if (score < bestScore) {
                bestScore = score
                best = range
            }
        }
        return best
    }

    /** Centred AE metering window: Center = 52% of the active array, Spot = 14%. */
    fun centredMeteringRegion(active: Rect, fraction: Float): MeteringRectangle {
        val w = (active.width() * fraction).toInt().coerceAtLeast(1)
        val h = (active.height() * fraction).toInt().coerceAtLeast(1)
        val left = active.centerX() - w / 2
        val top = active.centerY() - h / 2
        return MeteringRectangle(
            Rect(
                left.coerceAtLeast(active.left),
                top.coerceAtLeast(active.top),
                (left + w).coerceAtMost(active.right),
                (top + h).coerceAtMost(active.bottom),
            ),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
    }

    /**
     * Underwater filter gains RELATIVE to the HAL's current auto-white-balance solution.
     *
     * The previous filter wrote absolute Beer-Lambert gains, so "0 m" (unity gains) rendered the
     * raw sensor balance — a strong green cast — and every depth inherited that error. Now the
     * depth model only supplies the red/green boost RATIOS for the water column, multiplied onto
     * the gains AWB already chose for this scene, then normalised so the smallest gain is 1.0
     * (Camera2 gains must be >= 1 to avoid clipped highlights turning magenta). 0 m therefore
     * equals Off exactly. Constant-time; no allocation besides the result vector.
     */
    fun relativeUnderwaterGains(base: RggbChannelVector, depthMeters: Double): RggbChannelVector {
        val depth = depthMeters.coerceIn(0.0, 50.0)
        val redBoost = kotlin.math.exp((RED_ATTENUATION - BLUE_ATTENUATION) * depth).coerceIn(1.0, 8.0)
        val greenBoost = kotlin.math.exp((GREEN_ATTENUATION - BLUE_ATTENUATION) * depth).coerceIn(1.0, 4.0)
        val r = base.red * redBoost
        val ge = base.greenEven * greenBoost
        val go = base.greenOdd * greenBoost
        val b = base.blue.toDouble()
        val min = minOf(minOf(r, ge), minOf(go, b)).takeIf { it > 0.0 } ?: 1.0
        return RggbChannelVector((r / min).toFloat(), (ge / min).toFloat(), (go / min).toFloat(), (b / min).toFloat())
    }

    /** Clear-seawater diffuse attenuation (1/m) — red is absorbed first, blue last. */
    private const val RED_ATTENUATION = 0.15
    private const val GREEN_ATTENUATION = 0.07
    private const val BLUE_ATTENUATION = 0.03

    /** Samsung vendor AWB mode that honours samsung.android.control.colorTemperature. */
    const val SAMSUNG_AWB_MODE_KELVIN = 101
    const val SAMSUNG_COLOR_TEMPERATURE_KEY = "samsung.android.control.colorTemperature"

    private val colorTemperatureKey: CaptureRequest.Key<Int> by lazy {
        CaptureRequest.Key(SAMSUNG_COLOR_TEMPERATURE_KEY, Int::class.javaObjectType)
    }
}
