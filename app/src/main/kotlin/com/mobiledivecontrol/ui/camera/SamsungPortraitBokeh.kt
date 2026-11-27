package com.mobiledivecontrol.ui.camera

import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider

/** The S24 extension maps percent back to a dial step with floor(percent * 8 / 101). */
internal fun samsungBokehPercent(level: Int): Int = (level.coerceIn(0, 7) * 101 + 7) / 8

internal data class SamsungPortraitBokeh(
    val selector: CameraSelector,
    val cameraId: String,
    val zoom: Float,
    val supportsAnalysis: Boolean,
) {
    companion object {
        @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
        fun resolve(
            provider: ProcessCameraProvider,
            manager: ExtensionsManager?,
            logical: CameraSelector,
            requestedZoom: Float,
            physicalId: String?,
        ): SamsungPortraitBokeh? {
            if (manager == null) return null
            fun candidate(base: CameraSelector, zoom: Float): SamsungPortraitBokeh? = try {
                if (!manager.isExtensionAvailable(base, ExtensionMode.BOKEH)) null else {
                    val selector = manager.getExtensionEnabledCameraSelector(base, ExtensionMode.BOKEH)
                    val info = provider.getCameraInfo(selector)
                    val range = info.zoomState.value
                    // An extension with a fixed strength would silently ignore the housing dial.
                    if (!manager.getCameraExtensionsInfo(info).isExtensionStrengthAvailable ||
                        (zoom != 1f && (range == null || zoom !in range.minZoomRatio..range.maxZoomRatio))) null
                    else SamsungPortraitBokeh(selector, Camera2CameraInfo.from(info).cameraId, zoom,
                        manager.isImageAnalysisSupported(base, ExtensionMode.BOKEH))
                }
            } catch (error: RuntimeException) {
                Log.i("DiveCameraCtrl", "Samsung bokeh unavailable for this selector", error)
                null
            }

            // A public physical lens can have its own extension even when the logical extension
            // cannot zoom out to it. Standalone physical camera coordinates start at 1x.
            if (physicalId != null && requestedZoom != 1f) {
                val physical = CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == physicalId }
                }.build()
                candidate(physical, 1f)?.let { return it }
            }
            return candidate(logical, requestedZoom)
        }
    }
}
