package com.mobiledivecontrol.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
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
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.max

/**
 * Direct Camera2 fallback for Samsung devices that publish constrained-high-speed streams but
 * omit the high-speed CamcorderProfile required by CameraX Recorder. It owns only the short
 * high-speed recording session; CameraX remains the normal/LOG acquisition pipeline.
 */
internal class Camera2HighSpeedRecorder(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DiveHighSpeed"
    }

    data class Request(
        val previewHost: PreviewView,
        val cameraId: String,
        val size: Size,
        val fps: Int,
        val outputFile: File,
        val audioEnabled: Boolean,
        val exposureCompensationIndex: Int?,
        val zoomRatio: Float,
        val torchEnabled: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var surfaceView: SurfaceView? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
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
        get() = if (startedAtElapsedMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtElapsedMs

    fun start(
        request: Request,
        onStarted: () -> Unit,
        onFinalized: (Result<Long>) -> Unit,
    ) {
        check(!isBusy) { "A high-speed recording is already active or starting" }
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
                if (this@Camera2HighSpeedRecorder.request !== request) return
                configureAndOpen(request, holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (this@Camera2HighSpeedRecorder.request === request && !finalizeDelivered) {
                    fail(IllegalStateException("High-speed preview surface was destroyed"))
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
            if (request.audioEnabled) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(request.size.width, request.size.height)
            recorder.setVideoFrameRate(request.fps)
            val bitrate = max(
                20_000_000,
                (request.size.width.toLong() * request.size.height * request.fps / 8L)
                    .coerceAtMost(100_000_000L)
                    .toInt(),
            )
            recorder.setVideoEncodingBitRate(bitrate)
            if (request.audioEnabled) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(48_000)
                recorder.setAudioEncodingBitRate(192_000)
            }
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
                        if (this@Camera2HighSpeedRecorder.request !== request) {
                            device.close()
                            return
                        }
                        cameraDevice = device
                        createSession(device, holder, recorder, request)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        fail(IllegalStateException("High-speed camera disconnected"))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        fail(IllegalStateException("High-speed camera open error $error"))
                    }
                },
                handler,
            )
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun createSession(
        device: CameraDevice,
        holder: SurfaceHolder,
        recorder: MediaRecorder,
        request: Request,
    ) {
        try {
            val previewSurface = holder.surface
            val recordingSurface = recorder.surface
            device.createConstrainedHighSpeedCaptureSession(
                listOf(previewSurface, recordingSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        val highSpeed = configured as? CameraConstrainedHighSpeedCaptureSession
                            ?: run {
                                fail(IllegalStateException("Camera did not create a constrained-high-speed session"))
                                return
                            }
                        if (this@Camera2HighSpeedRecorder.request !== request) {
                            highSpeed.close()
                            return
                        }
                        session = highSpeed
                        try {
                            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(previewSurface)
                                addTarget(recordingSurface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, supportedRange(request))
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
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
                            }
                            highSpeed.setRepeatingBurst(
                                highSpeed.createHighSpeedRequestList(builder.build()),
                                null,
                                handler,
                            )
                            recorder.start()
                            startedAtElapsedMs = SystemClock.elapsedRealtime()
                            Log.i(
                                TAG,
                                "Started ${request.size.width}x${request.size.height}@${request.fps} " +
                                    "camera=${request.cameraId} audio=${request.audioEnabled}",
                            )
                            onStarted?.invoke()
                            if (stopRequested) stop()
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail(IllegalStateException("Could not configure constrained-high-speed session"))
                    }
                },
                handler,
            )
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun supportedRange(request: Request): Range<Int> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val streamMap = manager.getCameraCharacteristics(request.cameraId).get(
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        ) ?: throw IllegalArgumentException("Camera has no stream configuration map")
        return streamMap.getHighSpeedVideoFpsRangesFor(request.size)
            .filter { it.upper == request.fps }
            .minWithOrNull(compareBy<Range<Int>>({ if (it.lower == it.upper) 0 else 1 }, { it.upper - it.lower }))
            ?: throw IllegalArgumentException(
                "${request.size.width}x${request.size.height} does not support ${request.fps}fps",
            )
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
                throw IllegalStateException("High-speed recorder produced no video")
            }
            inspectEncodedStream(file)
            finish(Result.success(duration))
        } catch (error: Throwable) {
            fail(error)
        }
    }

    /** Read the muxed timestamps, not the requested setting, so hardware verification is honest. */
    private fun inspectEncodedStream(file: File) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackMimes = (0 until extractor.trackCount).map { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                }
                val videoTrack = trackMimes.indexOfFirst { it.startsWith("video/") }
                    .takeIf { it >= 0 } ?: return
                val hasAudio = trackMimes.any { it.startsWith("audio/") }
                val format = extractor.getTrackFormat(videoTrack)
                extractor.selectTrack(videoTrack)
                var firstUs = -1L
                var lastUs = -1L
                var samples = 0
                while (samples < 480) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < 0L) break
                    if (firstUs < 0L) firstUs = timeUs
                    lastUs = timeUs
                    samples++
                    if (!extractor.advance()) break
                }
                val measuredFps = if (samples > 1 && lastUs > firstUs) {
                    (samples - 1) * 1_000_000.0 / (lastUs - firstUs)
                } else {
                    null
                }
                val declaredFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    null
                }
                Log.i(
                    TAG,
                    "Encoded stream verified: declaredFps=$declaredFps " +
                        "measuredFps=${measuredFps?.let { "%.2f".format(it) }} " +
                        "samples=$samples hasAudio=$hasAudio tracks=$trackMimes format=$format",
                )
            } finally {
                extractor.release()
            }
        }.onFailure { error -> Log.w(TAG, "Could not inspect encoded high-speed stream", error) }
    }

    fun release() {
        if (!isBusy) return
        request?.outputFile?.delete()
        cleanup()
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "High-speed recording failed", error)
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
