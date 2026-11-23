package com.mobiledivecontrol.ui.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Log
import com.mobiledivecontrol.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/** Passive debug evidence for manual verification; never submits or modifies a request. */
internal object CameraControlTrace {
    data class FocusSample(val atMs: Long, val pipeline: String, val requested: Float?, val actual: Float?, val afMode: Int?,
        val actuator: Int?, val physicalId: String?, val focalLength: Float?, val frameDurationNs: Long?)
    private val actuatorKey = CaptureResult.Key("samsung.android.lens.info.currentInfo", IntArray::class.java)
    @Volatile var focusSample: FocusSample? = null
        private set
    private val lastAt = ConcurrentHashMap<String, Long>()
    private val names = setOf(
        "android.control.afMode", "android.control.afState", "android.control.afTrigger",
        "android.lens.focusDistance", "android.control.aeMode", "android.control.aeLock",
        "android.control.aeExposureCompensation", "android.control.aeTargetFpsRange",
        "android.sensor.sensitivity", "android.sensor.exposureTime", "android.sensor.frameDuration",
        "android.control.awbMode", "android.control.awbLock", "android.colorCorrection.gains",
        "android.flash.mode", "android.control.zoomRatio", "android.control.videoStabilizationMode",
        "android.logicalMultiCamera.activePhysicalId", "android.control.sceneMode",
    )
    private fun relevant(name: String) = name in names || name.contains("lensPos", ignoreCase = true)

    fun record(pipeline: String, request: CaptureRequest, result: CaptureResult) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastAt[pipeline] ?: 0L) < 1000L) return
        lastAt[pipeline] = now
        focusSample = FocusSample(now, pipeline, request.get(CaptureRequest.LENS_FOCUS_DISTANCE),
            result.get(CaptureResult.LENS_FOCUS_DISTANCE), result.get(CaptureResult.CONTROL_AF_MODE),
            runCatching { result.get(actuatorKey)?.getOrNull(3) }.getOrNull(),
            if (android.os.Build.VERSION.SDK_INT >= 28)
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null,
            result.get(CaptureResult.LENS_FOCAL_LENGTH), result.get(CaptureResult.SENSOR_FRAME_DURATION))
        runCatching {
            val requested = request.keys.filter { relevant(it.name) }.joinToString { "${it.name}=${request.get(it)}" }
            val actual = result.keys.filter { relevant(it.name) }.joinToString { "${it.name}=${result.get(it)}" }
            Log.d("DiveControls", "$pipeline request: $requested")
            Log.d("DiveControls", "$pipeline result: $actual")
        }
    }
}
