package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import android.view.SurfaceHolder

/** The S24 uses HAL shooting mode 13 for slow motion. Both surfaces use ordinary Camera2 outputs. */
internal object SamsungUhdSlowMotion {
    const val HEVC_BITRATE = 80_000_000
    const val THERMAL_VIEWFINDER_FPS = 24
    // Both optical routes produced full-size HEVC and aligned AAC on this firmware.
    const val MEASURED_FIRMWARE = "samsung/e1qcsx/e1q:16/BP4A.251205.006/S921WVLS6DZH3:user/release-keys"
    private val shootingMode by lazy { CaptureRequest.Key("samsung.android.control.shootingMode", Int::class.javaObjectType) }
    private val minFps by lazy { CaptureRequest.Key("samsung.android.control.recordingMinFps", Int::class.javaObjectType) }
    private val maxFps by lazy { CaptureRequest.Key("samsung.android.control.recordingMaxFps", Int::class.javaObjectType) }
    private val qualityPolicy by lazy { CaptureRequest.Key("samsung.android.control.specialImageQualityPolicy", Long::class.javaObjectType) }
    private val adaptivePixel by lazy { CaptureRequest.Key("samsung.android.control.adaptivePixelMode", Int::class.javaObjectType) }
    private val streamType by lazy { CaptureRequest.Key("samsung.android.sensor.streamType", Int::class.javaObjectType) }

    fun acceptsLens(model: String, lens: String, cameraId: String, sensorId: String?): Boolean =
        model.startsWith("SM-S921") && cameraId == "0" && when (lens) {
            "0.6x" -> sensorId == "2"
            "1x" -> sensorId == "5"
            else -> false
        }

    val previewSize get() = Size(1280, 720)

    fun discover(context: Context, logicalId: String, sensorId: String?, lens: String): UhdCaptureProfile? {
        if (Build.VERSION.SDK_INT < 33 || !Build.MANUFACTURER.equals("samsung", true) ||
            !acceptsLens(Build.MODEL, lens, logicalId, sensorId) || !SamsungCameraParameters.available) return null
        val manager = context.getSystemService(CameraManager::class.java)
        if (logicalId !in manager.cameraIdList) return null
        val camera = manager.getCameraCharacteristics(logicalId)
        val map = camera[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return null
        val zoom = if (lens == "0.6x") 0.6f else 1f
        if (sensorId !in camera.physicalCameraIds || camera[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.contains(zoom) != true ||
            UhdCaptureProfile.size !in map.getOutputSizes(MediaRecorder::class.java).orEmpty() ||
            previewSize !in map.getOutputSizes(SurfaceHolder::class.java).orEmpty() ||
            !UhdCaptureProfile.hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, 120)) return null
        return UhdCaptureProfile(logicalId, null, lens, zoom, 120, false, TimeLapseVideoCodec.HEVC,
            expectedSensorId = sensorId, samsungSlowMotion = true)
    }

    fun configureCamera(camera: CameraDevice) {
        SamsungCameraParameters.set(camera, "shootingmode=13;recording-fps=120;operation_mode=none;" +
            "recording_dr_mode=sdr;sw-vdis=false;sw-super_vdis=false;stream_type=0;")
    }

    fun configureRequest(builder: CaptureRequest.Builder) {
        builder.set(shootingMode, 13)
        builder.set(minFps, 120)
        builder.set(maxFps, 120)
        builder.set(qualityPolicy, 2L)
        builder.set(adaptivePixel, 0)
        builder.set(streamType, 0)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
    }
}
