package com.mobiledivecontrol.ui.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build

/** SamsungCamera 16.5.02.36: SemCameraParameter sw-super_vdis + Byte request/result flag. */
internal object SamsungSuperSteady {
    private const val FLAG = "samsung.android.control.swSuperVideoStabilization"
    val requestKey = CaptureRequest.Key(FLAG, Byte::class.javaObjectType)
    val resultKey = CaptureResult.Key(FLAG, Byte::class.javaObjectType)
    private val hookAvailable by lazy { Build.MANUFACTURER.equals("samsung", true) && SamsungCameraParameters.available }
    private val capabilities = java.util.concurrent.ConcurrentHashMap<CameraCharacteristics, Boolean>()
    fun supported(chars: CameraCharacteristics): Boolean = capabilities.getOrPut(chars) {
        hookAvailable && chars.availableCaptureRequestKeys.any { it.name == FLAG } &&
            chars.availableCaptureResultKeys?.any { it.name == FLAG } == true
    }

    fun configureCamera(camera: CameraDevice) {
        SamsungCameraParameters.set(camera, "sw-super_vdis=true;sw-vdis=true;")
    }
}
