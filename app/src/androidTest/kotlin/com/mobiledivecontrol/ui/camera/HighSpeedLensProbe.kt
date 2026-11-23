package com.mobiledivecontrol.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object HighSpeedLensProbe {
    @SuppressLint("MissingPermission")
    fun run(context: Context): JSONArray {
        val manager = context.getSystemService(CameraManager::class.java)
        return JSONArray(listOf("5" to 1f, "6" to 1f, "2" to 1f, "0" to 3f, "0" to 0.6f).map { (id, zoom) ->
            val row = JSONObject().put("camera", id).put("zoom", zoom)
            val thread = HandlerThread("HighSpeedLensProbe").apply { start() }
            val handler = Handler(thread.looper)
            val texture = SurfaceTexture(false).apply { setDefaultBufferSize(1920, 1080) }
            val surface = Surface(texture)
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            val failure = AtomicReference<Throwable?>()
            try {
                val opened = CountDownLatch(1)
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(value: CameraDevice) { device = value; opened.countDown() }
                    override fun onDisconnected(value: CameraDevice) { failure.set(IllegalStateException("Disconnected")); opened.countDown() }
                    override fun onError(value: CameraDevice, error: Int) { failure.set(IllegalStateException("Camera error $error")); opened.countDown() }
                }, handler)
                check(opened.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
                val configured = CountDownLatch(1)
                device!!.createConstrainedHighSpeedCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) { session = value; configured.countDown() }
                    override fun onConfigureFailed(value: CameraCaptureSession) { failure.set(IllegalStateException("Configure failed")); configured.countDown() }
                }, handler)
                check(configured.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
                val request = device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 120))
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                }.build()
                val captured = CountDownLatch(1)
                (session as CameraConstrainedHighSpeedCaptureSession).setRepeatingBurst(
                    (session as CameraConstrainedHighSpeedCaptureSession).createHighSpeedRequestList(request),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                            row.put("physical", result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
                                .put("actualZoom", result.get(CaptureResult.CONTROL_ZOOM_RATIO))
                            captured.countDown()
                        }
                    }, handler)
                check(captured.await(5, TimeUnit.SECONDS))
            } catch (error: Throwable) { row.put("failure", error.toString()) }
            finally { session?.close(); device?.close(); surface.release(); texture.release(); thread.quitSafely() }
            row
        })
    }
}
