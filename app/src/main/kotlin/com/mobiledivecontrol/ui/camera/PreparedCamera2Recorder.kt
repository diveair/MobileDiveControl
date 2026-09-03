package com.mobiledivecontrol.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import java.io.File
import java.util.concurrent.Executors

/** One camera session from mode entry through Record/Pause/Resume. Only its targets change. */
internal class PreparedCamera2Recorder(private val context: Context) {
    data class Config(
        val cameraId: String,
        val size: Size,
        val highSpeedFps: Int? = null,
        val captureRate: Double = 30.0,
        val playbackFps: Int = 30,
        val codec: TimeLapseVideoCodec = TimeLapseVideoCodec.H264,
        val night: Boolean = false,
    )
    data class Controls(
        val ev: Int? = null,
        val zoom: Float = 1f,
        val torch: Boolean = false,
        val focus: Float? = null,
        val fixedFocus: Boolean = false,
        val singleAf: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val finalizer = Executors.newSingleThreadExecutor()
    private var generation = 0
    var config: Config? = null
        private set
    private var controls = Controls()
    private var view: SurfaceView? = null
    private var input: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var preparedFile: File? = null
    private var destination: File? = null
    private var startedAt = 0L
    private var stopping = false
    private var ready = false
    private var onReady: (() -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var onFinalized: ((Result<Long>) -> Unit)? = null
    private var sourceRange: Range<Int>? = null
    private var previewRange: Range<Int>? = null
    private var availability: CameraManager.AvailabilityCallback? = null
    private var observedFocus: Float? = null
    private var releaseAfterStop = false
    private var deferredStop: Runnable? = null
    private var singleAfTriggered = false
    val ownsCamera: Boolean get() = config != null
    val isReady: Boolean get() = ready && recorder != null && !stopping
    val isBusy: Boolean get() = destination != null || stopping
    val elapsedDurationMs: Long get() = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    fun prepare(host: PreviewView, selection: Config, values: Controls, onReady: () -> Unit, onError: (Throwable) -> Unit) {
        check(!ownsCamera)
        config = selection
        controls = values
        this.onReady = onReady
        this.onError = onError
        val token = ++generation
        val surfaceView = SurfaceView(context).apply {
            setZOrderMediaOverlay(true)
            holder.setFixedSize(selection.size.width, selection.size.height)
        }
        view = surfaceView
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (token == generation) open(selection, holder, token)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (token != generation) return
                if (stopping) {
                    releaseAfterStop = true
                    runCatching { session?.close() }
                    runCatching { device?.close() }
                } else if (isBusy) {
                    releaseAfterStop = true
                    stop()
                } else release()
            }
        })
        host.addView(surfaceView, ViewGroup.LayoutParams(-1, -1))
    }

    @SuppressLint("MissingPermission")
    private fun open(selection: Config, holder: SurfaceHolder, token: Int) {
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            if (selection.highSpeedFps != null && selection.highSpeedFps >= 120) {
                val map = checkNotNull(manager.getCameraCharacteristics(selection.cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                sourceRange = map.getHighSpeedVideoFpsRangesFor(selection.size)
                    .filter { it.lower == it.upper && it.upper >= selection.highSpeedFps.coerceAtLeast(120) }
                    .minByOrNull { it.upper }
                    ?: error("No high-speed stream for ${selection.size} at ${selection.highSpeedFps} fps")
                previewRange = map.getHighSpeedVideoFpsRangesFor(selection.size)
                    .firstOrNull { it.lower == 30 && it.upper == sourceRange?.upper }
                    ?: sourceRange
            } else if (selection.highSpeedFps != null) {
                sourceRange = Range(60, 60)
                previewRange = Range(30, 30)
            }
            input = MediaCodec.createPersistentInputSurface()
            prepareNextRecorder()
            val deviceCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (token != generation) { camera.close(); return }
                    device = camera
                    createSession(camera, holder, token)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (token == generation) fail(IllegalStateException("Recording camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (token == generation) fail(IllegalStateException("Recording camera error $error"))
                }
            }
            val availableCallback = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    if (cameraId != selection.cameraId || token != generation || availability !== this) return
                    manager.unregisterAvailabilityCallback(this)
                    availability = null
                    try { manager.openCamera(selection.cameraId, deviceCallback, main) }
                    catch (error: Throwable) { fail(error) }
                }
            }
            availability = availableCallback
            manager.registerAvailabilityCallback(availableCallback, main)
        } catch (error: Throwable) { fail(error) }
    }

    @Suppress("DEPRECATION")
    private fun prepareNextRecorder() {
        val selection = checkNotNull(config)
        val file = File(context.noBackupFilesDir, "prepared-recordings/${System.nanoTime()}.mp4")
        file.parentFile?.mkdirs()
        preparedFile = file
        val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = next
        next.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        next.setVideoEncoder(if (selection.codec == TimeLapseVideoCodec.HEVC) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        next.setVideoSize(selection.size.width, selection.size.height)
        if (selection.highSpeedFps == null) next.setCaptureRate(selection.captureRate)
        next.setVideoFrameRate(selection.playbackFps)
        val cadence = sourceRange?.upper ?: selection.playbackFps
        next.setVideoEncodingBitRate((selection.size.width.toLong() * selection.size.height * cadence / 8)
            .coerceIn(20_000_000L, 100_000_000L).toInt())
        next.setInputSurface(checkNotNull(input))
        next.setOutputFile(file.absolutePath)
        next.prepare()
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, holder: SurfaceHolder, token: Int) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (token != generation) { configured.close(); return }
                session = configured
                try {
                    repeat(recording = false)
                } catch (error: Throwable) { fail(error) }
            }
            override fun onConfigureFailed(failed: CameraCaptureSession) {
                if (token == generation) fail(IllegalStateException("Prepared camera session rejected"))
            }
        }
        try {
            val surfaces = listOf(holder.surface, checkNotNull(input))
            if ((config?.highSpeedFps ?: 0) >= 120) camera.createConstrainedHighSpeedCaptureSession(surfaces, callback, main)
            else camera.createCaptureSession(surfaces, callback, main)
        } catch (error: Throwable) { fail(error) }
    }

    fun update(values: Controls) {
        if (values == controls) return
        if (values.singleAf != controls.singleAf) singleAfTriggered = false
        controls = values
        if (session != null && !stopping) {
            try { repeat(startedAt > 0L) } catch (error: Throwable) { fail(error) }
        }
    }

    private fun repeat(recording: Boolean) {
        val selection = checkNotNull(config)
        val currentSession = checkNotNull(session)
        val builder = checkNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(checkNotNull(view).holder.surface)
        if (recording) builder.addTarget(checkNotNull(input))
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        (if (recording) sourceRange else previewRange)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        builder.set(CaptureRequest.CONTROL_AF_MODE, if (controls.fixedFocus || controls.focus != null)
            CameraMetadata.CONTROL_AF_MODE_OFF else if (controls.singleAf) CameraMetadata.CONTROL_AF_MODE_AUTO
            else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        controls.focus?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        controls.ev?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        if (Build.VERSION.SDK_INT >= 30) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, controls.zoom)
        builder.set(CaptureRequest.FLASH_MODE, if (controls.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (selection.night && selection.highSpeedFps == null) {
            val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(selection.cameraId)
            if (chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.contains(CameraMetadata.CONTROL_SCENE_MODE_NIGHT) == true) {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_NIGHT)
            } else {
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.lower in 1..15 && it.upper <= 30 }?.minByOrNull { it.lower }
                    ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                        ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true)
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
        }
        val token = generation
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (token != generation) return
                observedFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                // High-speed callbacks expose the wrapped regular session, not the public
                // CameraConstrainedHighSpeedCaptureSession object. Generation owns identity.
                if (!ready) {
                    ready = true
                    Log.i("DivePrepared", "Preview ready $config; camera session prepared before shutter")
                    onReady?.invoke()
                }
            }
        }
        if (controls.singleAf && !singleAfTriggered && controls.focus == null && !controls.fixedFocus) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            if (currentSession is CameraConstrainedHighSpeedCaptureSession)
                currentSession.captureBurst(currentSession.createHighSpeedRequestList(builder.build()), null, main)
            else currentSession.capture(builder.build(), null, main)
            singleAfTriggered = true
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        if (currentSession is CameraConstrainedHighSpeedCaptureSession)
            currentSession.setRepeatingBurst(currentSession.createHighSpeedRequestList(builder.build()), callback, main)
        else currentSession.setRepeatingRequest(builder.build(), callback, main)
    }

    fun start(output: File, onStarted: () -> Unit, onFinalized: (Result<Long>) -> Unit) {
        check(isReady && !isBusy) { "Recording session is not ready" }
        destination = output
        this.onFinalized = onFinalized
        try {
            if (config?.highSpeedFps == null && controls.focus == null) {
                controls = controls.copy(focus = observedFocus)
            }
            checkNotNull(recorder).start()
            startedAt = SystemClock.elapsedRealtime()
            repeat(recording = true)
            Log.i("DivePrepared", "Shutter: encoder attached to existing session at $startedAt")
            onStarted()
        } catch (error: Throwable) { fail(error) }
    }

    fun stop() {
        if (!isBusy || stopping) return
        if (!releaseAfterStop) {
            val minimumMs = if (config?.highSpeedFps == null)
                kotlin.math.ceil(2_000.0 / checkNotNull(config).captureRate).toLong() + 250L else 150L
            val remaining = minimumMs - elapsedDurationMs
            if (remaining > 0) {
                deferredStop?.let(main::removeCallbacks)
                deferredStop = Runnable { deferredStop = null; stop() }.also { main.postDelayed(it, remaining) }
                return
            }
        }
        stopping = true
        deferredStop?.let(main::removeCallbacks)
        deferredStop = null
        val pauseAt = SystemClock.elapsedRealtime()
        val duration = elapsedDurationMs
        val token = generation
        val completedRecorder = checkNotNull(recorder)
        val completedFile = checkNotNull(preparedFile)
        val output = checkNotNull(destination)
        val callback = onFinalized
        // Keep preview repeating while MediaRecorder writes its MP4 index on a worker thread.
        try { repeat(recording = false) } catch (error: Throwable) { fail(error); return }
        recorder = null
        preparedFile = null
        finalizer.execute {
            val result = runCatching {
                try { completedRecorder.stop() } finally { completedRecorder.release() }
                check(completedFile.length() > 0L) { "Recorder produced no frames" }
                output.parentFile?.mkdirs()
                check(completedFile.renameTo(output)) { "Could not retain recording segment" }
                duration
            }
            completedFile.delete()
            main.post {
                if (token != generation) { output.delete(); return@post }
                destination = null
                startedAt = 0L
                stopping = false
                onFinalized = null
                if (releaseAfterStop) release()
                else try { prepareNextRecorder() } catch (error: Throwable) { fail(error) }
                Log.i("DivePrepared", "Pause finalized in ${SystemClock.elapsedRealtime() - pauseAt}ms; preview session retained=$ownsCamera")
                callback?.invoke(result)
            }
        }
    }

    fun suspendPreview() {
        if (isBusy) {
            releaseAfterStop = true
            stop()
        } else release()
    }

    private fun fail(error: Throwable) {
        Log.e("DivePrepared", "Prepared recording failed", error)
        val callback = onFinalized
        val report = onError
        release()
        if (callback != null) callback(Result.failure(error)) else report?.invoke(error)
    }

    fun release() {
        generation++
        availability?.let { context.getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(it) }
        availability = null
        deferredStop?.let(main::removeCallbacks)
        deferredStop = null
        ready = false
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { recorder?.release() }
        runCatching { input?.release() }
        session = null
        device = null
        recorder = null
        input = null
        config = null
        runCatching { (view?.parent as? ViewGroup)?.removeView(view) }
        view = null
        preparedFile?.delete()
        preparedFile = null
        destination = null
        startedAt = 0L
        stopping = false
        sourceRange = null
        previewRange = null
        observedFocus = null
        releaseAfterStop = false
        singleAfTriggered = false
        onReady = null
        onError = null
        onFinalized = null
    }
}
