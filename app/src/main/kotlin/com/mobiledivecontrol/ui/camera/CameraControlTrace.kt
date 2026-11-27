package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Log
import com.mobiledivecontrol.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

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

    private data class TraceKeys(val requests: List<CaptureRequest.Key<*>>, val results: List<CaptureResult.Key<*>>)
    private val sessionKeys = WeakHashMap<CameraCaptureSession, TraceKeys>()

    fun record(pipeline: String, request: CaptureRequest, result: CaptureResult, context: Context, session: CameraCaptureSession) {
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
            // Discover optional vendor tags once per session. Values and their presence still
            // come from this specific request/result, including changes between capture modes.
            val keys = synchronized(sessionKeys) {
                sessionKeys[session] ?: runCatching {
                    val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(session.device.id)
                    TraceKeys(((chars.availableCaptureRequestKeys.orEmpty() + request.keys).filter { relevant(it.name) }).distinct(),
                        ((chars.availableCaptureResultKeys.orEmpty() + result.keys).filter { relevant(it.name) }).distinct())
                }.getOrNull()?.also { sessionKeys[session] = it }
            }
            val requestKeys = keys?.requests ?: request.keys.filter { relevant(it.name) }
            val resultKeys = keys?.results ?: result.keys.filter { relevant(it.name) }
            val requested = requestKeys.mapNotNull { key -> request.get(key)?.let { "${key.name}=$it" } }.joinToString()
            val actual = resultKeys.mapNotNull { key -> result.get(key)?.let { "${key.name}=$it" } }.joinToString()
            Log.d("DiveControls", "$pipeline request: $requested")
            Log.d("DiveControls", "$pipeline result: $actual")
        }
    }
}
