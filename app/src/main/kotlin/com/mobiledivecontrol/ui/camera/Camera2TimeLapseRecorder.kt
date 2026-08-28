package com.mobiledivecontrol.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.max

internal const val TIME_LAPSE_PLAYBACK_FPS = 30

/** The initial Samsung Auto choice; explicit speed rungs remain exact. */
internal fun hyperlapseSpeedFactor(value: String?, dayNight: String?): Int = when (value) {
    "Night 45x" -> 45
    "Night 15x" -> 15
    "5x" -> 5
    "10x" -> 10
    "15x" -> 15
    "30x" -> 30
    "60x" -> 60
    else -> if (dayNight == "Night") 15 else 10
}

internal fun hyperlapseCaptureRateFps(speedFactor: Int): Double =
    TIME_LAPSE_PLAYBACK_FPS.toDouble() / speedFactor.coerceIn(1, 60)

/**
 * Direct Camera2 + MediaRecorder time-lapse session.
 *
 * CameraX Recorder has no time-lapse capture-rate API; changing only its UI timer still produces
 * an ordinary video. MediaRecorder's capture rate is the platform time-lapse contract: the camera
 * continues to preview normally while the encoder samples at 30/speed frames per real second and
 * writes those samples for 30 fps playback.
 */
internal class Camera2TimeLapseRecorder(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DiveTimeLapse"
    }

    data class Request(
        val previewHost: PreviewView,
        val cameraId: String,
        val size: Size,
        val captureRateFps: Double,
        val playbackFps: Int,
        val cameraFrameRate: Int?,
        val modeLabel: String,
        val outputFile: File,
        val exposureCompensationIndex: Int?,
        val zoomRatio: Float,
        val torchEnabled: Boolean,
        val focusDiopters: Float?,
        val nightMode: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var surfaceView: SurfaceView? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var request: Request? = null
    private var onStarted: (() -> Unit)? = null
    private var onFinalized: ((Result<Long>) -> Unit)? = null
    private var startedAtElapsedMs = 0L
    private var finalizeDelivered = false
    private var stopRequested = false

    val isBusy: Boolean
        get() = request != null

    val elapsedDurationMs: Long
        get() = if (startedAtElapsedMs == 0L) 0L
        else SystemClock.elapsedRealtime() - startedAtElapsedMs

    fun start(
        request: Request,
        onStarted: () -> Unit,
        onFinalized: (Result<Long>) -> Unit,
    ) {
        check(!isBusy) { "A time-lapse recording is already active or starting" }
        this.request = request
        this.onStarted = onStarted
        this.onFinalized = onFinalized
        finalizeDelivered = false
        stopRequested = false
        startedAtElapsedMs = 0L
        request.outputFile.parentFile?.mkdirs()

        val surface = SurfaceView(context).apply {
            visibility = View.VISIBLE
            setZOrderMediaOverlay(true)
            holder.setFixedSize(request.size.width, request.size.height)
        }
        surfaceView = surface
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (this@Camera2TimeLapseRecorder.request !== request) return
                configureAndOpen(request, holder)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (this@Camera2TimeLapseRecorder.request === request && !finalizeDelivered) {
                    fail(IllegalStateException("Time-lapse preview surface was destroyed"))
                }
            }
        })
        request.previewHost.addView(
            surface,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun configureAndOpen(request: Request, holder: SurfaceHolder) {
        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            mediaRecorder = recorder
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(request.size.width, request.size.height)
            recorder.setCaptureRate(request.captureRateFps)
            recorder.setVideoFrameRate(request.playbackFps)
            recorder.setVideoEncodingBitRate(
                max(
                    20_000_000,
                    (request.size.width.toLong() * request.size.height * request.playbackFps / 8L)
                        .coerceAtMost(60_000_000L)
                        .toInt(),
                ),
            )
            recorder.setOutputFile(request.outputFile.absolutePath)
            recorder.prepare()

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permission is missing")
            }
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(
                request.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (this@Camera2TimeLapseRecorder.request !== request) {
                            device.close()
                            return
                        }
                        cameraDevice = device
                        createSession(device, holder, recorder, request)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        fail(IllegalStateException("Time-lapse camera disconnected"))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        fail(IllegalStateException("Time-lapse camera open error $error"))
                    }
                },
                handler,
            )
        } catch (error: Throwable) {
            fail(error)
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(
        device: CameraDevice,
        holder: SurfaceHolder,
        recorder: MediaRecorder,
        request: Request,
    ) {
        try {
            val previewSurface = holder.surface
            val recordingSurface = recorder.surface
            device.createCaptureSession(
                listOf(previewSurface, recordingSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (this@Camera2TimeLapseRecorder.request !== request) {
                            configured.close()
                            return
                        }
                        session = configured
                        try {
                            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(previewSurface)
                                addTarget(recordingSurface)
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                                request.cameraFrameRate?.let { fps ->
                                    set(
                                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        android.util.Range(fps, fps),
                                    )
                                }
                                if (request.focusDiopters != null) {
                                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                                    set(CaptureRequest.LENS_FOCUS_DISTANCE, request.focusDiopters)
                                } else {
                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                    )
                                }
                                request.exposureCompensationIndex?.let {
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    set(CaptureRequest.CONTROL_ZOOM_RATIO, request.zoomRatio)
                                }
                                set(
                                    CaptureRequest.FLASH_MODE,
                                    if (request.torchEnabled) CameraMetadata.FLASH_MODE_TORCH
                                    else CameraMetadata.FLASH_MODE_OFF,
                                )
                                if (request.nightMode && supportsNightScene(request.cameraId)) {
                                    set(
                                        CaptureRequest.CONTROL_MODE,
                                        CameraMetadata.CONTROL_MODE_USE_SCENE_MODE,
                                    )
                                    set(
                                        CaptureRequest.CONTROL_SCENE_MODE,
                                        CameraMetadata.CONTROL_SCENE_MODE_NIGHT,
                                    )
                                }
                            }
                            configured.setRepeatingRequest(builder.build(), null, handler)
                            recorder.start()
                            startedAtElapsedMs = SystemClock.elapsedRealtime()
                            Log.i(
                                TAG,
                                "Started ${request.size.width}x${request.size.height} " +
                                    "mode=${request.modeLabel} " +
                                    "capture=${"%.3f".format(request.captureRateFps)}fps " +
                                    "playback=${request.playbackFps}fps camera=${request.cameraId} " +
                                    "night=${request.nightMode}",
                            )
                            onStarted?.invoke()
                            if (stopRequested) stop()
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail(IllegalStateException("Could not configure time-lapse session"))
                    }
                },
                handler,
            )
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun supportsNightScene(cameraId: String): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            .let { it ?: intArrayOf() }
            .contains(CameraMetadata.CONTROL_SCENE_MODE_NIGHT)
    }

    fun stop() {
        if (!isBusy) return
        if (startedAtElapsedMs == 0L) {
            stopRequested = true
            return
        }
        val duration = elapsedDurationMs
        try {
            session?.stopRepeating()
            mediaRecorder?.stop()
            val file = request?.outputFile
            if (file == null || !file.isFile || file.length() <= 0L) {
                throw IllegalStateException("Time-lapse recorder produced no video")
            }
            inspectEncodedStream(file, duration)
            finish(Result.success(duration))
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun inspectEncodedStream(file: File, recordedDurationMs: Long) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: return
                val format = extractor.getTrackFormat(videoTrack)
                val outputDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    -1L
                }
                val declaredFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    null
                }
                Log.i(
                    TAG,
                    "Encoded stream verified: declaredFps=$declaredFps " +
                        "recordedDurationMs=$recordedDurationMs outputDurationUs=$outputDurationUs " +
                        "format=$format",
                )
            } finally {
                extractor.release()
            }
        }.onFailure { error -> Log.w(TAG, "Could not inspect encoded time-lapse stream", error) }
    }

    fun release() {
        if (!isBusy) return
        request?.outputFile?.delete()
        cleanup()
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "Time-lapse recording failed", error)
        request?.outputFile?.delete()
        finish(Result.failure(error))
    }

    private fun finish(result: Result<Long>) {
        if (finalizeDelivered) return
        finalizeDelivered = true
        val callback = onFinalized
        cleanup()
        callback?.invoke(result)
    }

    private fun cleanup() {
        runCatching { session?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        val view = surfaceView
        runCatching { (view?.parent as? ViewGroup)?.removeView(view) }
        surfaceView = null
        cameraDevice = null
        session = null
        mediaRecorder = null
        request = null
        onStarted = null
        onFinalized = null
        startedAtElapsedMs = 0L
        stopRequested = false
    }
}
