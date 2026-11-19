package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.HardwareBuffer
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs as DiveControl's UID: shell or Samsung Camera capability lists are not equivalent. */
class SamsungLogAccessInstrumentation : Instrumentation() {
    private var requestKey: String? = null
    private var requestValue = 0
    private var sessionProbe = false
    private var samsungParameter: String? = null
    private var outputProfile = DynamicRangeProfiles.HLG10
    private var recordProbe = false
    private var streamUseCase = 0L
    private var recorderMode = 27
    private var exposureNs = 0L
    private var preparedProbe = false
    private var preparedUhd = false
    private var sceneProbe = false
    private var sceneIso = 800
    private var sceneFocus = 2.94117641f
    private var clipCount = 2
    private var clipMs = 0L
    private var recordAudio = true
    private var sceneFhd = false
    private var suspendProbe = false
    private var controlsProbe = false
    private var stabilized = true
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestKey = arguments?.getString("requestKey")
        requestValue = arguments?.getString("requestValue")?.toIntOrNull() ?: 0
        sessionProbe = arguments?.getString("sessionProbe") == "true"
        samsungParameter = arguments?.getString("samsungParameter")
        outputProfile = arguments?.getString("outputProfile")?.toLongOrNull() ?: DynamicRangeProfiles.HLG10
        recordProbe = arguments?.getString("recordProbe") == "true"
        streamUseCase = arguments?.getString("streamUseCase")?.toLongOrNull() ?: 0L
        recorderMode = arguments?.getString("recorderMode")?.toIntOrNull() ?: 27
        exposureNs = arguments?.getString("exposureNs")?.toLongOrNull() ?: 0L
        preparedProbe = arguments?.getString("preparedProbe") == "true"
        preparedUhd = arguments?.getString("preparedUhd") == "true"
        sceneProbe = arguments?.getString("sceneProbe") == "true"
        sceneIso = arguments?.getString("sceneIso")?.toIntOrNull() ?: 800
        sceneFocus = arguments?.getString("sceneFocus")?.toFloatOrNull() ?: 2.94117641f
        clipCount = arguments?.getString("clipCount")?.toIntOrNull() ?: 2
        clipMs = arguments?.getString("clipMs")?.toLongOrNull() ?: 0L
        recordAudio = arguments?.getString("recordAudio") != "false"
        sceneFhd = arguments?.getString("sceneFhd") == "true"
        suspendProbe = arguments?.getString("suspendProbe") == "true"
        controlsProbe = arguments?.getString("controlsProbe") == "true"
        stabilized = arguments?.getString("stabilized") != "false"
        start()
    }

    override fun onStart() {
        if (preparedProbe || sceneProbe) { probePreparedRecorder(); return }
        val result = Bundle()
        try {
            val manager = targetContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            val report = JSONObject()
                .put("package", targetContext.packageName)
                .put("uid", android.os.Process.myUid())
                .put("model", Build.MODEL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("publicCameraIds", JSONArray(ids.toList()))
            val cameras = JSONArray()
            // Camera 56 was observed in Samsung's native Pro Video session; query without opening.
            for (id in (ids.toList() + "56").distinct()) {
                val entry = JSONObject().put("id", id)
                try {
                    val c = manager.getCameraCharacteristics(id)
                    entry.put("physicalIds", JSONArray(c.physicalCameraIds.toList()))
                    entry.put("requestKeys", JSONArray(c.availableCaptureRequestKeys.map { it.name }))
                    entry.put("sessionKeys", JSONArray(c.availableSessionKeys.orEmpty().map { it.name }))
                    entry.put("resultKeys", JSONArray(c.availableCaptureResultKeys.map { it.name }))
                    if (Build.VERSION.SDK_INT >= 33) {
                        entry.put("dynamicRangeProfiles", JSONArray(
                            c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                                ?.supportedProfiles.orEmpty().toList(),
                        ))
                    }
                    val relevant = JSONObject()
                    for (key in c.keys) {
                        if (listOf("log", "dynamicrange", "client", "recordingdr").any {
                            key.name.contains(it, ignoreCase = true)
                        }) {
                            @Suppress("UNCHECKED_CAST")
                            val value = c.get(key as CameraCharacteristics.Key<Any>)
                            relevant.put(key.name, when (value) {
                                is IntArray -> JSONArray(value.toList())
                                is LongArray -> JSONArray(value.toList())
                                is ByteArray -> JSONArray(value.toList())
                                else -> value?.toString() ?: JSONObject.NULL
                            })
                        }
                    }
                    entry.put("relevantCharacteristics", relevant)
                } catch (error: Exception) {
                    entry.put("error", error.javaClass.name + ": " + error.message)
                }
                cameras.put(entry)
            }
            report.put("cameras", cameras)
            requestKey?.let { report.put("requestProbe", probeRequest(manager, it, requestValue)) }
            val file = File(targetContext.filesDir, "samsung-log-access.json")
            file.writeText(report.toString(2))
            result.putString("report", file.absolutePath)
            result.putString("publicCameraIds", ids.joinToString())
            finish(0, result)
        } catch (error: Exception) {
            result.putString("failure", android.util.Log.getStackTraceString(error))
            finish(1, result)
        }
    }

    private fun probePreparedRecorder() {
        val report = JSONObject().put("uid", android.os.Process.myUid())
        val done = CountDownLatch(1)
        var recorder: PreparedCamera2Recorder? = null
        var activity: NativeLogProbeActivity? = null
        try {
            activity = startActivitySync(android.content.Intent(targetContext, NativeLogProbeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeLogProbeActivity
            val host = activity
            runOnMainSync {
                val current = PreparedCamera2Recorder(host)
                recorder = current
                var completed = 0
                if (preparedUhd || sceneProbe) {
                    current.configureNativeLogRequest = { request ->
                        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        request.set(CaptureRequest.SENSOR_SENSITIVITY, if (sceneProbe) sceneIso else 100)
                        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, if (sceneProbe) exposureNs.takeIf { it > 0 } ?: 16_666_667L else 8_333_333L)
                        request.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                        if (sceneProbe) {
                            SamsungNativeLogPipeline.configureWhiteBalance(request, 3400)
                        }
                    }
                    current.onNativeLogCapture = { result ->
                        report.put("observedIso", result.get(CaptureResult.SENSOR_SENSITIVITY))
                        report.put("observedExposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                        report.put("gains", result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.toString())
                        report.put("transform", result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toString())
                        report.put("focus", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                        report.put("physicalCamera", result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
                        report.put("noiseReduction", result.get(CaptureResult.NOISE_REDUCTION_MODE))
                        report.put("edgeMode", result.get(CaptureResult.EDGE_MODE))
                        report.put("stabilization", result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))
                        if (result.get(CaptureResult.CONTROL_ZOOM_RATIO) == 2f) report.put("observedZoom2", true)
                    }
                }
                val size = if ((preparedUhd || sceneProbe) && !sceneFhd) android.util.Size(3840, 2160) else android.util.Size(1920, 1080)
                report.put("size", size.toString())
                current.prepare(host.preview, PreparedCamera2Recorder.Config("0", size,
                    codec = TimeLapseVideoCodec.HEVC, nativeLog = true, recordAudio = recordAudio, nativeStabilization = stabilized), PreparedCamera2Recorder.Controls(focus = if (sceneProbe) sceneFocus else null),
                    onReady = {
                        if (completed < clipCount) {
                            val clip = File(targetContext.filesDir, "native-prepared-${completed + 1}.mp4")
                            current.start(clip, onStarted = {
                                val handler = Handler(android.os.Looper.getMainLooper())
                                if (controlsProbe) {
                                    handler.postDelayed({ current.update(PreparedCamera2Recorder.Controls(zoom = 2f)) }, 1000L)
                                    handler.postDelayed({ current.update(PreparedCamera2Recorder.Controls(zoom = 1f)) }, 2000L)
                                }
                                handler.postDelayed({ if (suspendProbe) current.suspendPreview() else current.stop() }, clipMs.takeIf { it > 0 } ?: if (sceneProbe) 6000 else 2000)
                            }, onFinalized = { result ->
                                result.onSuccess {
                                    completed++
                                    report.put("completedClips", completed).put("lastDurationMs", it)
                                    val extractor = android.media.MediaExtractor()
                                    try {
                                        extractor.setDataSource(clip.absolutePath)
                                        report.put("tracks", JSONArray((0 until extractor.trackCount).map { index -> extractor.getTrackFormat(index).toString() }))
                                    } finally { extractor.release() }
                                    if (completed == clipCount) done.countDown()
                                }.onFailure { report.put("error", android.util.Log.getStackTraceString(it)); done.countDown() }
                            })
                        }
                    },
                    onError = { report.put("error", android.util.Log.getStackTraceString(it)); done.countDown() })
            }
            if (!done.await(35, TimeUnit.SECONDS)) report.put("error", "prepared recording timeout")
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally { runOnMainSync { recorder?.release(); activity?.finish() } }
        File(targetContext.filesDir, "samsung-log-prepared.json").writeText(report.toString(2))
        finish(if (report.has("error")) 1 else 0, Bundle().apply { putString("result", report.toString()) })
    }

    @Suppress("MissingPermission")
    private fun probeRequest(manager: CameraManager, keyName: String, value: Int): JSONObject {
        val report = JSONObject().put("cameraId", "0").put("key", keyName).put("value", value)
        val thread = HandlerThread("LogRequestProbe").apply { start() }
        val done = CountDownLatch(1)
        var opened: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var recorder: android.media.MediaRecorder? = null
        var recording = false
        try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opened = camera
                    try {
                        samsungParameter?.let { parameter ->
                            report.put("samsungParameter", parameter)
                            val loader = dalvik.system.PathClassLoader(
                                "/system/framework/scamera_sdk_util.jar",
                                android.util.Log::class.java.classLoader,
                            )
                            val helper = Class.forName(
                                "com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper",
                                true, loader,
                            )
                            try {
                                val returned = helper.getMethod(
                                    "setSamsungParameter", CameraDevice::class.java, String::class.java,
                                ).invoke(null, camera, parameter)
                                report.put("samsungParameterAccepted", true)
                                report.put("samsungParameterReturn", returned ?: JSONObject.NULL)
                            } catch (error: java.lang.reflect.InvocationTargetException) {
                                throw error.targetException
                            }
                        }
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        if (exposureNs > 0) {
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                            builder.set(CaptureRequest.SENSOR_SENSITIVITY, 100)
                            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 33333333L)
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                        }
                        val key = CaptureRequest.Key(keyName, Int::class.javaObjectType)
                        if (value >= 0) builder.set(key, value)
                        report.put("requestBuilderAccepted", true)
                        report.put("requestBuilderEcho", builder.get(key))
                        if (sessionProbe) {
                            val videoRecorder = if (recordProbe) android.media.MediaRecorder(targetContext).apply {
                                recorder = this
                                setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                                setVideoEncoder(android.media.MediaRecorder.VideoEncoder.HEVC)
                                setVideoEncodingProfileLevel(android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, 0)
                                setVideoSize(1920, 1080)
                                setVideoFrameRate(30)
                                setVideoEncodingBitRate(40_000_000)
                                if (recorderMode >= 0) javaClass.getMethod("semSetRecordingMode", Int::class.javaPrimitiveType).invoke(this, recorderMode)
                                val path = File(targetContext.filesDir, "native-log-probe.mp4")
                                setOutputFile(path.absolutePath)
                                report.put("videoPath", path.absolutePath)
                                prepare()
                            } else null
                            val outputReader = ImageReader.newInstance(
                                1280, 720, ImageFormat.PRIVATE, 3, HardwareBuffer.USAGE_VIDEO_ENCODE,
                            )
                            reader = outputReader
                            report.put("outputProfile", outputProfile)
                            outputReader.setOnImageAvailableListener({ source ->
                                source.acquireLatestImage()?.use { image ->
                                    report.put("imageFormat", image.format)
                                    report.put("imageDataSpace", image.dataSpace)
                                    image.hardwareBuffer?.use { buffer ->
                                        report.put("hardwareBufferFormat", buffer.format)
                                    }
                                }
                            }, Handler(thread.looper))
                            val target = videoRecorder?.surface ?: outputReader.surface
                            builder.addTarget(target)
                            val output = OutputConfiguration(target).apply {
                                dynamicRangeProfile = outputProfile
                                setStreamUseCase(this@SamsungLogAccessInstrumentation.streamUseCase)
                            }
                            val config = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(output),
                                java.util.concurrent.Executor { Handler(thread.looper).post(it) },
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(configured: CameraCaptureSession) {
                                        session = configured
                                        report.put("sessionConfigured", true)
                                        try {
                                            videoRecorder?.start()
                                            recording = videoRecorder != null
                                            var frames = 0
                                            configured.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                                    frames++
                                                    report.put("frames", frames)
                                                    report.put("resultMode", result.get(CaptureResult.Key(keyName, Int::class.javaObjectType)) ?: JSONObject.NULL)
                                                    report.put("physicalId", result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
                                                    report.put("exposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                                                    report.put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
                                                    if (frames >= if (recordProbe) 120 else 8) {
                                                        configured.stopRepeating()
                                                        done.countDown()
                                                    }
                                                }
                                                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                                                    report.put("captureFailure", failure.reason)
                                                    done.countDown()
                                                }
                                            }, Handler(thread.looper))
                                        } catch (error: Exception) {
                                            report.put("error", error.javaClass.name + ": " + error.message)
                                            done.countDown()
                                        }
                                    }
                                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                                        report.put("error", "session configuration failed")
                                        failed.close()
                                        done.countDown()
                                    }
                                },
                            )
                            config.sessionParameters = builder.build()
                            camera.createCaptureSession(config)
                        }
                    } catch (error: Exception) {
                        report.put("error", error.javaClass.name + ": " + error.message)
                    } finally {
                        if (!sessionProbe || report.has("error")) {
                            camera.close()
                            done.countDown()
                        }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    report.put("error", "disconnected")
                    done.countDown()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    report.put("error", "camera error $error")
                    done.countDown()
                }
            }, Handler(thread.looper))
            if (!done.await(12, TimeUnit.SECONDS)) report.put("error", "camera/session timeout")
        } catch (error: Exception) {
            report.put("error", error.javaClass.name + ": " + error.message)
        } finally {
            session?.close()
            opened?.close()
            if (recording) try { recorder?.stop() } catch (error: Exception) { report.put("stopError", error.toString()) }
            recorder?.release()
            if (recording) {
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(File(targetContext.filesDir, "native-log-probe.mp4").absolutePath)
                    report.put("encodedFormat", extractor.getTrackFormat(0).toString())
                } finally { extractor.release() }
            }
            reader?.close()
            thread.quitSafely()
            thread.join(2000)
        }
        return report
    }
}
