package com.mobiledivecontrol.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Isolated hardware probe; experimental requests never enter the application's focus path. */
internal object InfinityFocusProbe {
    @SuppressLint("MissingPermission")
    fun run(context: Context, physical: String): JSONObject {
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = manager.getCameraCharacteristics(physical)
        val report = JSONObject().put("physical", physical)
            .put("distanceCalibration", chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION))
            .put("factoryTable", JSONArray(chars.get(CameraCharacteristics.Key(
                "samsung.android.lens.info.focusCalibration", IntArray::class.java))?.toList().orEmpty()))
        val thread = HandlerThread("InfinityProbe").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val failure = AtomicReference<Throwable?>()
        try {
            val opened = CountDownLatch(1)
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
                override fun onDisconnected(camera: CameraDevice) { failure.set(IllegalStateException("Disconnected")); opened.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { failure.set(IllegalStateException("Camera error $error")); opened.countDown() }
            }, handler)
            check(opened.await(8, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            val configured = CountDownLatch(1)
            val output = OutputConfiguration(reader.surface).apply { setPhysicalCameraId(physical) }
            checkNotNull(device).createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(output), { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) { session = value; configured.countDown() }
                    override fun onConfigureFailed(value: CameraCaptureSession) { failure.set(IllegalStateException("Configure failed")); configured.countDown() }
                }))
            check(configured.await(8, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            val rows = JSONArray()
            for (distance in listOf(1f, 0.1f, 0f, -0.1f, -1f, 0f)) {
                val row = JSONObject().put("requested", distance)
                try {
                    val last = AtomicReference<TotalCaptureResult?>()
                    val request = checkNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, if (physical == "6") 3f else 1f)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    }.build()
                    checkNotNull(session).setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { last.set(result) }
                    }, handler)
                    SystemClock.sleep(2000)
                    val result = checkNotNull(last.get())
                    row.put("actual", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                        .put("actuator", result.get(CaptureResult.Key("samsung.android.lens.info.currentInfo", IntArray::class.java))?.getOrNull(3))
                        .put("physical", result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
                } catch (error: Throwable) { row.put("failure", error.toString()) }
                rows.put(row)
            }
            return report.put("samples", rows)
        } finally {
            session?.close(); device?.close(); reader.close(); thread.quitSafely()
        }
    }
}
