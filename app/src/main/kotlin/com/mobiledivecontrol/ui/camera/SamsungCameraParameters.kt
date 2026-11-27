package com.mobiledivecontrol.ui.camera

import android.hardware.camera2.CameraDevice
import dalvik.system.PathClassLoader
import java.io.File

internal object SamsungCameraParameters {
    private const val LIBRARY = "/system/framework/scamera_sdk_util.jar"
    private val setter by lazy {
        if (!File(LIBRARY).isFile) null else runCatching {
            val loader = PathClassLoader(LIBRARY, javaClass.classLoader)
            Class.forName("com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper", true, loader)
                .getMethod("setSamsungParameter", CameraDevice::class.java, String::class.java)
        }.getOrNull()
    }

    val available get() = setter != null

    fun set(camera: CameraDevice, parameters: String) {
        checkNotNull(setter) { "Samsung camera parameters are unavailable." }.invoke(null, camera, parameters)
    }
}
