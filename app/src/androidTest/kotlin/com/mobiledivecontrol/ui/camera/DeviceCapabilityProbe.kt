package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject

internal object DeviceCapabilityProbe {
    fun run(context: Context): JSONArray {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = (manager.cameraIdList.toList() + manager.cameraIdList.flatMap {
            manager.getCameraCharacteristics(it).physicalCameraIds
        }).distinct()
        return JSONArray(ids.map { id ->
            val c = manager.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            JSONObject().put("id", id).put("physical", JSONArray(c.physicalCameraIds.toList()))
                .put("focal", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString())
                .put("iso", c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).toString())
                .put("ev", c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).toString())
                .put("fps", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString())
                .put("video", JSONArray(map.getOutputSizes(MediaRecorder::class.java).map { size ->
                    "$size:${map.getOutputMinFrameDuration(MediaRecorder::class.java, size)}"
                }))
                .put("highSpeed", JSONArray(runCatching { map.highSpeedVideoSizes.map { size ->
                    "$size:${map.getHighSpeedVideoFpsRangesFor(size).joinToString()}"
                } }.getOrDefault(emptyList())))
                .put("maximumResolution", c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    ?.getOutputSizes(ImageFormat.JPEG)?.joinToString())
                .put("still", JSONObject().apply {
                    for ((name, format) in listOf("jpeg" to ImageFormat.JPEG, "raw" to ImageFormat.RAW_SENSOR, "heic" to ImageFormat.HEIC)) {
                        put(name, JSONArray(map.getOutputSizes(format)?.map { it.toString() }.orEmpty()))
                        put("${name}High", JSONArray(map.getHighResolutionOutputSizes(format)?.map { it.toString() }.orEmpty()))
                    }
                })
        })
    }
}
