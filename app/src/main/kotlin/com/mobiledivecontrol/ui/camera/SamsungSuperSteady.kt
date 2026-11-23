package com.mobiledivecontrol.ui.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import dalvik.system.PathClassLoader
import java.io.File

/** SamsungCamera 16.5.02.36: SemCameraParameter sw-super_vdis + Byte request/result flag. */
internal object SamsungSuperSteady {
    private const val FLAG = "samsung.android.control.swSuperVideoStabilization"
    val requestKey = CaptureRequest.Key(FLAG, Byte::class.javaObjectType)
    val resultKey = CaptureResult.Key(FLAG, Byte::class.javaObjectType)
    private val configure by lazy {
        runCatching {
            val loader = PathClassLoader("/system/framework/scamera_sdk_util.jar", javaClass.classLoader)
            Class.forName("com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper", true, loader)
                .getMethod("setSamsungParameter", CameraDevice::class.java, String::class.java)
        }.getOrNull()
    }

    private val hookAvailable by lazy { Build.MANUFACTURER.equals("samsung", true) &&
        File("/system/framework/scamera_sdk_util.jar").isFile && configure != null }
    private val capabilities = java.util.concurrent.ConcurrentHashMap<CameraCharacteristics, Boolean>()
    fun supported(chars: CameraCharacteristics): Boolean = capabilities.getOrPut(chars) {
        hookAvailable && chars.availableCaptureRequestKeys.any { it.name == FLAG } &&
            chars.availableCaptureResultKeys?.any { it.name == FLAG } == true
    }

    fun configureCamera(camera: CameraDevice) {
        checkNotNull(configure).invoke(null, camera, "sw-super_vdis=true;sw-vdis=true;")
    }
}
