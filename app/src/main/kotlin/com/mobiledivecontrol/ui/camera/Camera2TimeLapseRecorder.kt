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

internal enum class TimeLapseVideoCodec {
    H264,
    HEVC,
}

/** Accept persisted aliases while exposing only the two physically distinct encoders. */
internal fun hyperlapseVideoCodec(value: String?): TimeLapseVideoCodec = when (value) {
    "HEVC", "H.265", "HEVC / H.265", "H.265 / HEVC" -> TimeLapseVideoCodec.HEVC
    else -> TimeLapseVideoCodec.H264
}

/**
 * Explicit rungs stay exact. The public recorder cannot change capture rate mid-recording, so
 * Auto uses Samsung's measured 15x normal/long-exposure cadence and switches to 45x when the
 * native vendor result (or the user's Night selection) requests the night cadence. On the S24,
 * a 72-second native Auto recording with suggestion=0 encoded 147 frames at 30 fps (4.9 seconds),
 * which is the expected start/stop-quantized result of a 2 fps / 15x capture cadence.
 */
internal fun hyperlapseSpeedFactor(
    value: String?,
    dayNight: String?,
    samsungSuggestedMotionSpeedMode: Int? = null,
): Int = when (value) {
    "Night 45x" -> 45
    "Night 15x" -> 15
    "5x" -> 5
    "10x" -> 10
    "15x" -> 15
    "30x" -> 30
    "60x" -> 60
    else -> if (
        dayNight == "Night" || hyperlapseAutoUsesNightCadence(samsungSuggestedMotionSpeedMode)
    ) 45 else 15
}

internal fun hyperlapseCaptureRateFps(speedFactor: Int): Double =
    TIME_LAPSE_PLAYBACK_FPS.toDouble() / speedFactor.coerceIn(1, 60)

internal fun hyperlapseFrameIntervalSeconds(speedFactor: Int): Double =
    1.0 / hyperlapseCaptureRateFps(speedFactor)

internal fun hyperlapsePlaybackDurationMs(elapsedDurationMs: Long, speedFactor: Int): Long =
    elapsedDurationMs.coerceAtLeast(0L) / speedFactor.coerceAtLeast(1)

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
        val videoCodec: TimeLapseVideoCodec,
        val cameraFrameRate: Int?,
        val modeLabel: String,
        val outputFile: File,
        val exposureCompensationIndex: Int?,
        val zoomRatio: Float,
        val torchEnabled: Boolean,
        val focusDiopters: Float?,
        val fixedFocusLens: Boolean,
        val nightMode: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var surfaceView: SurfaceView? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var request: Request? = null
    private var onStarted: (() -> Unit)? = null
    private var onPreviewPresented: (() -> Unit)? = null
    private var onFinalized: ((Result<Long>) -> Unit)? = null
    private var startedAtElapsedMs = 0L
    private var finalizeDelivered = false
    private var stopRequested = false
    private var terminating = false
    private val deferredStopRunnable = Runnable {
        if (isBusy && !terminating && stopRequested) stop()
    }

    val isBusy: Boolean
        get() = request != null

    val elapsedDurationMs: Long
        get() = if (startedAtElapsedMs == 0L) 0L
        else SystemClock.elapsedRealtime() - startedAtElapsedMs

    fun start(
        request: Request,
        onStarted: () -> Unit,
        onPreviewPresented: () -> Unit,
        onFinalized: (Result<Long>) -> Unit,
    ) {
        check(!isBusy) { "A time-lapse recording is already active or starting" }
        this.request = request
        this.onStarted = onStarted
        this.onPreviewPresented = onPreviewPresented
        this.onFinalized = onFinalized
        finalizeDelivered = false
        stopRequested = false
        terminating = false
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
                if (this@Camera2TimeLapseRecorder.request === request && !terminating) {
                    // Android destroys this overlay when the activity loses the foreground.
                    // A started recording is still a valid clip, so close its MP4 index instead
                    // of deleting it as a failure. The identity/terminal guards also prevent the
                    // surface and CameraDevice callbacks from finalising the same segment twice.
                    if (startedAtElapsedMs > 0L) {
                        stop()
                    } else {
                        fail(IllegalStateException("Time-lapse preview surface was destroyed before start"))
                    }
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
            recorder.setVideoEncoder(
                when (request.videoCodec) {
                    TimeLapseVideoCodec.H264 -> MediaRecorder.VideoEncoder.H264
                    TimeLapseVideoCodec.HEVC -> MediaRecorder.VideoEncoder.HEVC
                },
            )
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
                        if (this@Camera2TimeLapseRecorder.request === request && !terminating) {
                            fail(IllegalStateException("Time-lapse camera disconnected"))
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (this@Camera2TimeLapseRecorder.request === request && !terminating) {
                            fail(IllegalStateException("Time-lapse camera open error $error"))
                        }
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
                                if (request.fixedFocusLens) {
                                    // A fixed-focus module has no actuator. Do not leave AF in a
                                    // continuous mode: Samsung may still apply AF-coupled digital
                                    // geometry/lens-correction updates even though the glass cannot
                                    // move, which looks like focus breathing in a sparse time-lapse.
                                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                                } else if (request.focusDiopters != null) {
                                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                                    set(CaptureRequest.LENS_FOCUS_DISTANCE, request.focusDiopters)
                                } else {
                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                    )
                                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                                }
                                // Hyperlapse samples sparse points from a real-time stream. Dynamic
                                // EIS changes its crop between those points and is perceived as the
                                // lens pumping in and out. The CameraX preview contract for this mode
                                // is stabilization Off; carry that contract into the direct session
                                // instead of inheriting TEMPLATE_RECORD's device-dependent default.
                                set(
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                                )
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
                                if (request.nightMode) {
                                    applyNightTuning(this, request.cameraId)
                                }
                            }
                            var previewPresentationDelivered = false
                            configured.setRepeatingRequest(
                                builder.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        captureRequest: CaptureRequest,
                                        result: android.hardware.camera2.TotalCaptureResult,
                                    ) {
                                        if (previewPresentationDelivered ||
                                            this@Camera2TimeLapseRecorder.request !== request ||
                                            terminating
                                        ) return
                                        previewPresentationDelivered = true
                                        // A capture result proves the direct session is producing,
                                        // but its SurfaceView buffer is committed asynchronously.
                                        // Cross two display transactions before allowing Compose
                                        // to uncover it, matching the CameraX replacement contract.
                                        surfaceView?.postOnAnimation {
                                            surfaceView?.postOnAnimation {
                                                if (this@Camera2TimeLapseRecorder.request === request &&
                                                    !terminating
                                                ) {
                                                    Log.i(TAG, "First direct preview frame presented")
                                                    onPreviewPresented?.invoke()
                                                    this@Camera2TimeLapseRecorder.onPreviewPresented = null
                                                }
                                            }
                                        }
                                    }
                                },
                                handler,
                            )
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
                        if (this@Camera2TimeLapseRecorder.request === request && !terminating) {
                            fail(IllegalStateException("Could not configure time-lapse session"))
                        }
                    }
                },
                handler,
            )
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun applyNightTuning(builder: CaptureRequest.Builder, cameraId: String) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sceneModes = characteristics
            .get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            ?: intArrayOf()
        if (sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_NIGHT)) {
            builder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_USE_SCENE_MODE,
            )
            builder.set(
                CaptureRequest.CONTROL_SCENE_MODE,
                CameraMetadata.CONTROL_SCENE_MODE_NIGHT,
            )
            return
        }

        // Samsung does not expose its vendor Night Hyperlapse processor to third-party apps on
        // every physical camera. Keep Day/Night functional on those IDs with public Camera2:
        // lower AE's permitted cadence and request the best available temporal noise reduction.
        val aeRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
        aeRanges
            .filter { range -> range.lower in 1..15 && range.upper <= 30 }
            .minWithOrNull(
                compareBy<android.util.Range<Int>> { range -> range.lower }
                    .thenByDescending { range -> range.upper },
            )
            ?.let { range ->
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
        val noiseModes = characteristics
            .get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?: intArrayOf()
        if (noiseModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            )
        }
    }

    fun stop() {
        if (!isBusy || terminating) return
        if (startedAtElapsedMs == 0L) {
            stopRequested = true
            return
        }
        val captureFps = request?.captureRateFps?.coerceAtLeast(0.01) ?: 1.0
        // MediaRecorder throws -1007 when stop() arrives before a time-lapse encoder has received
        // enough sparse samples to construct a valid MP4. This is easy to hit by tapping Pause or
        // Stop immediately after Resume: at 2 fps, 431 ms still contains no complete output GOP.
        // Enter the logical stopping state now, but keep the producer alive until two samples plus
        // a small muxer margin are guaranteed. Repeated requests coalesce onto this same runnable.
        val minimumSafeDurationMs = kotlin.math.ceil(2_000.0 / captureFps).toLong() + 250L
        val remainingMs = minimumSafeDurationMs - elapsedDurationMs
        if (remainingMs > 0L) {
            stopRequested = true
            handler.removeCallbacks(deferredStopRunnable)
            handler.postDelayed(deferredStopRunnable, remainingMs)
            Log.i(TAG, "Deferring time-lapse stop ${remainingMs}ms for encoder samples")
            return
        }
        stopRequested = false
        handler.removeCallbacks(deferredStopRunnable)
        terminating = true
        val duration = elapsedDurationMs
        try {
            session?.stopRepeating()
            mediaRecorder?.stop()
            val file = request?.outputFile
            if (file == null || !file.isFile || file.length() <= 0L) {
                throw IllegalStateException("Time-lapse recorder produced no video")
            }
            inspectEncodedStream(file, duration)
            deliverFinalResult(Result.success(duration))
        } catch (error: Throwable) {
            Log.e(TAG, "Time-lapse recording failed while stopping", error)
            request?.outputFile?.delete()
            deliverFinalResult(Result.failure(error))
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
        terminating = true
        finalizeDelivered = true
        request?.outputFile?.delete()
        cleanup()
    }

    private fun fail(error: Throwable) {
        if (!isBusy || terminating || finalizeDelivered) return
        terminating = true
        Log.e(TAG, "Time-lapse recording failed", error)
        request?.outputFile?.delete()
        deliverFinalResult(Result.failure(error))
    }

    private fun deliverFinalResult(result: Result<Long>) {
        if (finalizeDelivered) return
        finalizeDelivered = true
        val callback = onFinalized
        cleanup()
        callback?.invoke(result)
    }

    private fun cleanup() {
        handler.removeCallbacks(deferredStopRunnable)
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
        onPreviewPresented = null
        onFinalized = null
        startedAtElapsedMs = 0L
        stopRequested = false
    }
}
