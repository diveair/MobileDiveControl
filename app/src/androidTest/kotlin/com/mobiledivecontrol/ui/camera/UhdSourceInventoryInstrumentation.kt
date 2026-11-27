package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.os.Build
import android.os.Bundle
import java.util.Locale

/** Reports ordinary-app camera metadata without opening a device or starting a recording. */
class UhdSourceInventoryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            val manager = targetContext.getSystemService(CameraManager::class.java)
            val cameras = manager.cameraIdList
            report.putString("publicIds", cameras.joinToString())
            report.putString("build", Build.FINGERPRINT)
            val sensors = cameras.toMutableSet()
            if (Build.VERSION.SDK_INT >= 28) {
                cameras.forEach { sensors.addAll(manager.getCameraCharacteristics(it).physicalCameraIds) }
            }
            for (id in sensors.sorted()) {
                val c = manager.getCameraCharacteristics(id)
                val lens = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
                val ae = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()
                report.putString("camera${id}_lens", "$lens mm; AE=$ae")
                if (id in cameras && Build.VERSION.SDK_INT >= 35) {
                    report.putString("camera${id}_setup", "supported=${manager.isCameraDeviceSetupSupported(id)}; " +
                        "version=${c.get(CameraCharacteristics.INFO_SESSION_CONFIGURATION_QUERY_VERSION)}")
                }
                if (id in cameras && Build.VERSION.SDK_INT >= 31) {
                    val profile = CamcorderProfile.getAll(id, CamcorderProfile.QUALITY_HIGH_SPEED_2160P)
                    report.putString("camera${id}_uhdEncoderProfiles", profile?.videoProfiles?.joinToString {
                        "${it.width}x${it.height}@${it.frameRate}"
                    } ?: "none")
                }
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
                    report.putString("camera${id}_standard", describe(map))
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    report.putString("camera${id}_maximumResolution", maxMap?.let(::describe) ?: "none")
                }
            }
            finish(0, report)
        } catch (error: Exception) {
            report.putString("failure", error.toString())
            finish(1, report)
        }
    }

    private fun describe(map: StreamConfigurationMap): String = buildString {
        val fast = map.highSpeedVideoSizes.filter { it.width >= 3840 && it.height >= 2160 }
        append("highSpeedUhd=")
        append(fast.joinToString { "$it:${map.getHighSpeedVideoFpsRangesFor(it).joinToString()}" }.ifEmpty { "none" })
        val formats = mapOf(ImageFormat.PRIVATE to "PRIVATE", ImageFormat.YUV_420_888 to "YUV420",
            ImageFormat.YCBCR_P010 to "P010", ImageFormat.RAW_SENSOR to "RAW16", ImageFormat.RAW10 to "RAW10",
            ImageFormat.RAW12 to "RAW12", ImageFormat.YUV_422_888 to "YUV422", ImageFormat.YUV_444_888 to "YUV444")
        for ((format, name) in formats) {
            if (format !in map.outputFormats) continue
            val sizes = (map.getOutputSizes(format).orEmpty().asList() + map.getHighResolutionOutputSizes(format).orEmpty())
                .distinct().filter { it.width >= 3840 && it.height >= 2160 }
            if (sizes.isEmpty()) continue
            val durations = sizes.associateWith { map.getOutputMinFrameDuration(format, it) }
            val fastest = durations.entries.filter { it.value > 0 }.minByOrNull { it.value }
            append("; $name ")
            if (fastest != null) {
                append(String.format(Locale.US, "%s %.2f fps (%d ns)", fastest.key, 1e9 / fastest.value, fastest.value))
            }
            val unknown = durations.filterValues { it == 0L }.keys
            if (unknown.isNotEmpty()) append(" unspecified=$unknown")
        }
    }
}
