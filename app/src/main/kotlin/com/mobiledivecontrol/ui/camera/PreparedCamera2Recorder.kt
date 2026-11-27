package com.mobiledivecontrol.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.PixelCopy
import android.graphics.Bitmap
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
        val slowMotion: Boolean = false,
        val nativeLog: Boolean = false,
        val recordAudio: Boolean = true,
        val nativeStabilization: Boolean = true,
        val hyperlapseAuto: Boolean = false,
        val physicalCameraId: String? = null,
        val uhdProfile: UhdCaptureProfile? = null,
        val regularUhd: Boolean = false,
        /** Viewfinder buffer for the UHD high-speed relay, oriented like the camera's display transform. */
        val relayViewfinder: Size = Size(1080, 1920),
    ) {
        val relay: Boolean get() = uhdProfile?.relay == true
        val previewSize: Size get() = when {
            relay -> relayViewfinder
            uhdProfile?.samsungSlowMotion == true -> SamsungUhdSlowMotion.previewSize
            regularUhd -> Size(1920, 1080)
            else -> size
        }
        val constrained: Boolean get() = uhdProfile?.constrained ?: ((highSpeedFps ?: 0) >= 100)
        val directPreview: Boolean get() = uhdProfile != null || constrained
    }
    data class Controls(
        val look: LookPlan? = null,
        val zoom: Float = 1f,
        val torch: Boolean = false,
        val focus: Float? = null,
        val fixedFocus: Boolean = false,
        val singleAf: Boolean = false,
        val focusAssist: Boolean = false,
        val exposureAssist: Int = 0,
    )

    private val main = Handler(Looper.getMainLooper())
    private val finalizer = Executors.newSingleThreadExecutor()
    private var generation = 0
    var config: Config? = null
        private set
    private var controls = Controls()
    private var view: SurfaceView? = null
    private var input: Surface? = null
    private var nightAccumulator: HyperlapseNightAccumulator? = null
    private var nightInput: Surface? = null
    private var previewInput: Surface? = null
    private var relay: UhdHighSpeedRelay? = null
    private var focusPreview: FocusPeakingSurfaceProcessor? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var nativePendingTracks = 0
    private var nativeTracksStartedAt = 0L
    private var repeatingSerial = 0
    private var recorderPreparing = false
    private var preparedFile: File? = null
    private var destination: File? = null
    private var startedAt = 0L
    private var stopping = false
    private var ready = false
    private var onReady: (() -> Unit)? = null
    private var onPresented: (() -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var onFinalized: ((Result<Long>) -> Unit)? = null
    private var sourceRange: Range<Int>? = null
    private var physicalFpsControl = false
    private var previewRange: Range<Int>? = null
    private var thermalPreviewRange: Range<Int>? = null
    private var reduceUhdPreview = false
    private var preparedPreviewRange: Range<Int>? = null
    private var availability: CameraManager.AvailabilityCallback? = null
    private var observedFocus: Float? = null
    private var releaseAfterStop = false
    private var deferredStop: Runnable? = null
    private var singleAfTriggered = false
    private var lastSlowMotionTelemetryAt = 0L
    private var nativeReadyTimeout: Runnable? = null
    var configureNativeLogRequest: ((CaptureRequest.Builder) -> Unit)? = null
    var onCaptureTelemetry: ((TotalCaptureResult) -> Unit)? = null
    var onHyperlapseCapture: ((TotalCaptureResult) -> Unit)? = null
    private var hyperlapseAutoKey: CaptureRequest.Key<Int>? = null
    val ownsCamera: Boolean get() = config != null
    val isReady: Boolean get() = ready && recorder != null && !recorderPreparing && !stopping
    val isBusy: Boolean get() = destination != null || stopping
    val elapsedDurationMs: Long get() = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    fun copyPreview(callback: (Bitmap?) -> Unit) {
        val source = view
        if (source == null || !source.holder.surface.isValid || source.width <= 0 || source.height <= 0) {
            callback(null)
            return
        }
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(source, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) callback(bitmap)
                else { bitmap.recycle(); callback(null) }
            }, main)
        } catch (_: IllegalArgumentException) { bitmap.recycle(); callback(null) }
    }

    fun prepare(host: PreviewView, requested: Config, values: Controls, onReady: () -> Unit,
        onPresented: () -> Unit, onError: (Throwable) -> Unit) {
        check(!ownsCamera)
        // The relay draws the camera's display transform itself; give the viewfinder buffer the
        // orientation SurfaceFlinger would otherwise produce from a 1920x1080 camera buffer.
        val selection = if (!requested.relay) requested else requested.copy(relayViewfinder =
            if ((runCatching { context.getSystemService(CameraManager::class.java).getCameraCharacteristics(requested.cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) }.getOrNull() ?: 90) % 180 == 0) Size(1920, 1080) else Size(1080, 1920))
        config = selection
        controls = values
        this.onReady = onReady
        this.onPresented = onPresented
        this.onError = onError
        val token = ++generation
        if (selection.nativeLog) {
            try {
                SamsungNativeLogPipeline.requireSupported(selection.cameraId, selection.size, selection.playbackFps)
                check(!selection.slowMotion && !selection.night && selection.highSpeedFps == null) {
                    "Native LOG cannot be combined with slow motion or night capture."
                }
            } catch (error: Throwable) { fail(error); return }
            armNativeReadyTimeout(token)
        }
        val surfaceView = SurfaceView(context).apply {
            setZOrderMediaOverlay(true)
            holder.setFixedSize(selection.previewSize.width, selection.previewSize.height)
        }
        view = surfaceView
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (token != generation) return
                if (selection.directPreview) {
                    // Constrained high-speed output topology stays camera -> display/encoder.
                    previewInput = holder.surface
                    open(selection, holder, token)
                    return
                }
                val rotation = when (host.display?.rotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val processor = FocusPeakingSurfaceProcessor({ action -> main.post(action); Unit })
                focusPreview = processor
                processor.reduceViewfinderLoad = reduceUhdPreview
                processor.peakingEnabled = controls.focusAssist
                processor.exposureAssistMode = controls.exposureAssist
                processor.attachDirectPreview(selection.previewSize, holder.surface, if (selection.night) 0 else rotation,
                    onReady = { displayInput ->
                        if (token != generation) return@attachDirectPreview
                        previewInput = displayInput
                        if (selection.night) {
                            val accumulator = HyperlapseNightAccumulator(selection.size.width, selection.size.height)
                            nightAccumulator = accumulator
                            accumulator.setMode(NightAccumulationMode.TRAILS)
                            accumulator.setOutputIntervalNs(HyperlapseNightMath.outputIntervalNs(selection.captureRate))
                            accumulator.setPreviewMaxFps(if (reduceUhdPreview) 15 else 30)
                            accumulator.setDisplayRotation(rotation)
                            accumulator.start(onInputSurfaceReady = { surface ->
                                if (token == generation) {
                                    nightInput = surface
                                    accumulator.setPreviewSurface(displayInput, host.width, host.height)
                                    open(selection, holder, token)
                                } else accumulator.release()
                            }, onError = { error -> if (token == generation) fail(error) })
                        } else open(selection, holder, token)
                    }, onPresented = {
                        surfaceView.postOnAnimation {
                            if (token == generation) this@PreparedCamera2Recorder.onPresented?.invoke()
                        }
                    }, onError = { error -> if (token == generation) fail(error) })
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
            physicalFpsControl = Build.VERSION.SDK_INT >= 28 && selection.regularUhd && selection.physicalCameraId != null &&
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE in manager.getCameraCharacteristics(selection.cameraId).availablePhysicalCameraRequestKeys.orEmpty()
            @Suppress("UNCHECKED_CAST")
            hyperlapseAutoKey = if (selection.hyperlapseAuto) manager.getCameraCharacteristics(selection.cameraId)
                .availableCaptureRequestKeys.firstOrNull {
                    it.name == "samsung.android.control.recordingMotionSpeedMode"
                } as? CaptureRequest.Key<Int> else null
            if (selection.constrained) {
                val map = checkNotNull(manager.getCameraCharacteristics(selection.cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                sourceRange = map.getHighSpeedVideoFpsRangesFor(selection.size)
                    .filter { it.lower == it.upper && it.upper == selection.highSpeedFps }
                    .minByOrNull { it.upper }
                    ?: error("No high-speed stream for ${selection.size} at ${selection.highSpeedFps} fps")
                previewRange = map.getHighSpeedVideoFpsRangesFor(selection.size)
                    .firstOrNull { it.lower == 30 && it.upper == sourceRange?.upper }
                    ?: sourceRange
                if (selection.slowMotion && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                    preparedPreviewRange = map.getHighSpeedVideoFpsRangesFor(selection.size)
                        .filter { it.lower == it.upper }.minByOrNull { it.upper }
                }
            } else if (selection.highSpeedFps != null) {
                val sourceFps = SlowMotionRecordingPolicy.sourceFrameRate(selection.highSpeedFps)
                sourceRange = Range(sourceFps, sourceFps)
                previewRange = if (selection.uhdProfile != null) {
                    val ranges = manager.getCameraCharacteristics(selection.cameraId)
                        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        .orEmpty()
                    val preferred = if (selection.uhdProfile.samsungSlowMotion)
                        SamsungUhdSlowMotion.THERMAL_VIEWFINDER_FPS else 15
                    thermalPreviewRange = ranges.filter { it.lower == it.upper && it.upper in preferred..30 }
                        .minByOrNull { it.upper }
                    ranges.firstOrNull { it.lower == 30 && it.upper == 30 } ?: sourceRange
                } else Range(30, 30)
            }
            // Keep an advertised preview-only fallback while the old recorder drains. Stable
            // Samsung high-speed preview uses both targets once a suspended recorder is ready.
            if (selection.slowMotion && selection.highSpeedFps!! < 100) previewRange = sourceRange
            if (selection.nativeLog) {
                sourceRange = Range(selection.playbackFps, selection.playbackFps)
                previewRange = sourceRange
            }
            input = MediaCodec.createPersistentInputSurface()
            prepareNextRecorder()
            if (selection.relay) relay = UhdHighSpeedRelay(selection.size.width, selection.size.height,
                checkNotNull(input), previewInput, boottimeSource = manager.getCameraCharacteristics(selection.cameraId)
                    .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
                .apply { recording = false }
            val deviceCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (token != generation) { camera.close(); return }
                    device = camera
                    try {
                        if (selection.nativeLog) SamsungNativeLogPipeline.configureCamera(camera, selection.nativeStabilization)
                        if (selection.uhdProfile?.samsungSlowMotion == true) SamsungUhdSlowMotion.configureCamera(camera)
                        createSession(camera, holder, token)
                    } catch (error: Throwable) { fail(error) }
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
        recorder = createRecorder(selection, file, input, sourceRange?.upper ?: selection.playbackFps)
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(selection: Config, file: File, surface: Surface?, cadence: Int): MediaRecorder {
        val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            if (selection.nativeLog) SamsungNativeLogPipeline.initializeRecorder(next)
            val audio = (selection.nativeLog || selection.highSpeedFps != null) && selection.recordAudio && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (selection.nativeLog) {
                nativePendingTracks = if (audio) 2 else 1
                nativeTracksStartedAt = 0L
                next.setOnInfoListener { source, what, extra ->
                    if (source === recorder && what == 905 && nativePendingTracks > 0) {
                        // Samsung SEM_MEDIA_RECORDER_TRACK_INFO_STARTED, once per configured track.
                        nativePendingTracks--
                        if (nativePendingTracks == 0) nativeTracksStartedAt = SystemClock.elapsedRealtime()
                        Log.i("DivePrepared", "Native track started: $extra, pending=$nativePendingTracks")
                    }
                }
            }
            if (audio) next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setVideoEncoder(if (selection.nativeLog || selection.codec == TimeLapseVideoCodec.HEVC) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (audio) {
                next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                next.setAudioSamplingRate(48_000)
                next.setAudioEncodingBitRate(192_000)
            }
            if (selection.nativeLog) SamsungNativeLogPipeline.configureRecorder(next)
            next.setVideoSize(selection.size.width, selection.size.height)
            if (selection.nativeLog) {
                val sensorOrientation = context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(selection.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val rotation = runCatching { context.display?.rotation }.getOrNull() ?: Surface.ROTATION_0
                val displayDegrees = when (rotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                next.setOrientationHint((sensorOrientation - displayDegrees + 360) % 360)
            }
            if (selection.highSpeedFps == null && !selection.nativeLog) next.setCaptureRate(selection.captureRate)
            // Capture at the sensor clock. MediaRecorder.setCaptureRate disables microphone audio.
            next.setVideoFrameRate(if (selection.highSpeedFps != null) cadence else selection.playbackFps)
            val bitrate = if (selection.nativeLog) SamsungNativeLogPipeline.BITRATE else if (selection.slowMotion || selection.uhdProfile != null) {
                val mime = if (selection.codec == TimeLapseVideoCodec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC
                    else MediaFormat.MIMETYPE_VIDEO_AVC
                val encoderRange = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder && mime in it.supportedTypes }
                    .mapNotNull { codec -> runCatching { codec.getCapabilitiesForType(mime).videoCapabilities }
                        .getOrNull()?.takeIf { it.areSizeAndRateSupported(selection.size.width, selection.size.height, cadence.toDouble()) } }
                    .firstOrNull()?.bitrateRange ?: error("No encoder for ${selection.size} at $cadence fps")
                val target = if (selection.uhdProfile?.samsungSlowMotion == true) SamsungUhdSlowMotion.HEVC_BITRATE
                    else SlowMotionRecordingPolicy.bitrate(selection.size.width, selection.size.height, cadence.toDouble(),
                        selection.codec == TimeLapseVideoCodec.HEVC)
                encoderRange.clamp(target)
            } else (selection.size.width.toLong() * selection.size.height * cadence / 8)
                .coerceIn(20_000_000L, 100_000_000L).toInt()
            next.setVideoEncodingBitRate(bitrate)
            next.setInputSurface(checkNotNull(surface))
            next.setOutputFile(file.absolutePath)
            next.prepare()
            return next
        } catch (error: Throwable) {
            next.release()
            file.delete()
            throw error
        }
    }

    private fun prepareSlowMotionAfterStop(token: Int) {
        val selection = checkNotNull(config)
        val surface = checkNotNull(input)
        val fps = checkNotNull(sourceRange).upper
        val file = File(context.noBackupFilesDir, "prepared-recordings/${System.nanoTime()}.mp4")
        recorderPreparing = true
        finalizer.execute {
            val result = runCatching { createRecorder(selection, file, surface, fps) }
            main.post {
                if (token != generation) {
                    result.getOrNull()?.release()
                    file.delete()
                    return@post
                }
                recorderPreparing = false
                result.onSuccess { next ->
                    recorder = next
                    preparedFile = file
                    if (preparedPreviewRange != null) {
                        try { repeat(recording = false) } catch (error: Throwable) { fail(error); return@onSuccess }
                    }
                    if (ready) onReady?.invoke()
                }.onFailure { error -> file.delete(); fail(error) }
            }
        }
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
                if (token == generation) fail(IllegalStateException(if (config?.nativeLog == true)
                    "Samsung native LOG output configuration was rejected; no fallback recording was started."
                    else "Prepared camera session rejected"))
            }
        }
        try {
            val surfaces = (relay?.cameraSurface ?: nightInput)?.let { listOf(it) }
                ?: listOf(checkNotNull(previewInput), checkNotNull(input))
            if (config?.nativeLog == true) {
                val outputs = listOf(SamsungNativeLogPipeline.output(checkNotNull(previewInput), false),
                    SamsungNativeLogPipeline.output(checkNotNull(input), true))
                val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outputs, { command -> main.post(command); Unit }, callback)
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                configureNativeLogRequest?.invoke(request)
                SamsungNativeLogPipeline.configureRequest(request, checkNotNull(config).nativeStabilization)
                configuration.setSessionParameters(request.build())
                camera.createCaptureSession(configuration)
            } else if (Build.VERSION.SDK_INT >= 28 && (config?.physicalCameraId != null || config?.regularUhd == true)) {
                val selection = checkNotNull(config)
                val useCases = if (Build.VERSION.SDK_INT >= 33 && selection.regularUhd)
                    context.getSystemService(CameraManager::class.java).getCameraCharacteristics(camera.id)
                        .get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES) ?: longArrayOf() else longArrayOf()
                val outputs = surfaces.map { surface ->
                    OutputConfiguration(surface).apply {
                        selection.physicalCameraId?.let(::setPhysicalCameraId)
                        if (Build.VERSION.SDK_INT >= 33 && useCases.isNotEmpty()) {
                            val use = if (surface === input) CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD
                                else CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW
                            if (use.toLong() in useCases) streamUseCase = use.toLong()
                        }
                    }
                }
                val sessionType = if (config?.constrained == true) SessionConfiguration.SESSION_HIGH_SPEED
                    else SessionConfiguration.SESSION_REGULAR
                val configuration = SessionConfiguration(sessionType, outputs, { command -> main.post(command); Unit }, callback)
                if (selection.regularUhd) {
                    val request = recordRequest(camera)
                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, sourceRange)
                    if (selection.uhdProfile?.samsungSlowMotion == true) {
                        request.set(CaptureRequest.CONTROL_ZOOM_RATIO, selection.uhdProfile.zoom)
                        SamsungUhdSlowMotion.configureRequest(request)
                    }
                    controls.look?.stabilizationMode?.let { request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
                    if (physicalFpsControl) request.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        checkNotNull(sourceRange), checkNotNull(selection.physicalCameraId))
                    configuration.setSessionParameters(request.build())
                }
                camera.createCaptureSession(configuration)
            }
            else if (config?.constrained == true) camera.createConstrainedHighSpeedCaptureSession(surfaces, callback, main)
            else camera.createCaptureSession(surfaces, callback, main)
        } catch (error: Throwable) { fail(error) }
    }

    fun update(values: Controls) {
        focusPreview?.peakingEnabled = values.focusAssist
        focusPreview?.exposureAssistMode = values.exposureAssist
        if (values == controls) return
        if (values.singleAf != controls.singleAf) singleAfTriggered = false
        controls = values
        if (session != null && !stopping) {
            try { repeat(startedAt > 0L) } catch (error: Throwable) { fail(error) }
        }
    }

    fun reduceUhdViewfinderLoad(reduce: Boolean) {
        if (reduceUhdPreview == reduce) return
        reduceUhdPreview = reduce
        focusPreview?.reduceViewfinderLoad = reduce
        nightAccumulator?.setPreviewMaxFps(if (reduce) 15 else 30)
        val profile = config?.uhdProfile ?: return
        if (!profile.constrained && !profile.relay && session != null && !stopping) {
            // The previous repeating request stays active if the HAL rejects its replacement.
            // An optional viewfinder change must never discard an in-progress recording.
            try { repeat(startedAt > 0L) } catch (error: Exception) {
                Log.w("DivePrepared", "Could not change thermal viewfinder cadence", error)
            }
        }
    }

    private fun recordRequest(camera: CameraDevice): CaptureRequest.Builder {
        val physical = config?.physicalCameraId
        return if (Build.VERSION.SDK_INT >= 28 && config?.regularUhd == true && physical != null)
            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, setOf(physical))
        else camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
    }

    private fun repeat(recording: Boolean, stopNativeRecording: Boolean = false) {
        val serial = ++repeatingSerial
        val selection = checkNotNull(config)
        val currentSession = checkNotNull(session)
        val builder = recordRequest(checkNotNull(device))
        // A prepared MediaRecorder consumes and discards surface buffers until start(). Keep
        // both high-speed targets active at an advertised fixed rate while it waits: Samsung's
        // variable preview-only route can deliver just 7.5 fps in dim light after a 240 fps take.
        // Remove the encoder target while its old recorder drains, then restore it after prepare.
        val preparedPreview = !recording && preparedPreviewRange != null && recorder != null && !stopping
        val accumulatedInput = nightInput
        val relayOutput = relay
        if (relayOutput != null) {
            builder.addTarget(relayOutput.cameraSurface)
        } else if (accumulatedInput != null) {
            builder.addTarget(accumulatedInput)
            nightAccumulator?.setEncoderSurface(if (recording) input else null)
        } else {
            builder.addTarget(checkNotNull(previewInput))
            if (recording || preparedPreview || (selection.nativeLog && recorder != null && !stopping)) builder.addTarget(checkNotNull(input))
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        (if (recording) sourceRange else if (preparedPreview) preparedPreviewRange
            else if (reduceUhdPreview) thermalPreviewRange ?: previewRange else previewRange)
            ?.let { range ->
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                if (Build.VERSION.SDK_INT >= 28 && physicalFpsControl)
                    builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range, checkNotNull(selection.physicalCameraId))
            }
        builder.set(CaptureRequest.CONTROL_AF_MODE, if (controls.fixedFocus || controls.focus != null)
            CameraMetadata.CONTROL_AF_MODE_OFF else if (controls.singleAf) CameraMetadata.CONTROL_AF_MODE_AUTO
            else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        controls.focus?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        controls.look?.let { look ->
            // Constrained sessions own 3A and cadence. Regular sessions use the same controls as CameraX.
            CameraControls.writeLook(Camera2RequestSink(builder), if (selection.directPreview) look.copy(
                exposure = ExposurePlan.Auto((look.exposure as? ExposurePlan.Auto)?.evIndex, null),
                whitePoint = WhitePointPlan.HalAuto(false), fpsRange = null,
            ) else look)
        }
        if (Build.VERSION.SDK_INT >= 30) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, controls.zoom)
        else if (selection.slowMotion) {
            val active = context.getSystemService(CameraManager::class.java)
                .getCameraCharacteristics(selection.cameraId).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            active?.let {
                val halfWidth = (it.width() / (2f * controls.zoom.coerceAtLeast(1f))).toInt()
                val halfHeight = (it.height() / (2f * controls.zoom.coerceAtLeast(1f))).toInt()
                builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(
                    it.centerX() - halfWidth, it.centerY() - halfHeight,
                    it.centerX() + halfWidth, it.centerY() + halfHeight))
            }
        }
        builder.set(CaptureRequest.FLASH_MODE, if (controls.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        if (!selection.regularUhd || controls.look?.stabilizationMode == null)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (selection.slowMotion) {
            val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(selection.cameraId)
            if (chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                    ?.contains(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO) == true) {
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            }
            // Optical stabilization avoids the crop/resampling of electronic stabilization.
            if (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
        }
        if (selection.night && selection.highSpeedFps == null) {
            val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(selection.cameraId)
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.lower in 1..15 && it.upper <= 30 }?.minByOrNull { it.lower }
                ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) == true)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        }

        if (selection.nativeLog) {
            configureNativeLogRequest?.invoke(builder)
            SamsungNativeLogPipeline.configureRequest(builder, selection.nativeStabilization)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(selection.playbackFps, selection.playbackFps))
        }
        if (selection.uhdProfile?.samsungSlowMotion == true) SamsungUhdSlowMotion.configureRequest(builder)
        hyperlapseAutoKey?.let { builder.set(it, 10) }
        val token = generation
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (token != generation) return
                CameraControlTrace.record(when {
                    selection.nativeLog -> "NativeLog"
                    selection.regularUhd -> "RegularUhd"
                    selection.highSpeedFps != null -> "HighSpeed"
                    else -> "Hyperlapse"
                }, request, result, context, s)
                if (selection.nativeLog) {
                    // A persistent session can deliver a previous take's queued results after
                    // rearm. Only the current request may mark the next recorder ready.
                    if (s !== session || serial != repeatingSerial) return
                    try {
                        if (!stopping && !SamsungNativeLogPipeline.isNativeResult(result)) {
                            if (ready) fail(IllegalStateException("Samsung native LOG mode was lost: result mode=${SamsungNativeLogPipeline.captureMode(result)}."))
                            return
                        }
                    } catch (error: Throwable) { fail(error); return }
                }
                relay?.failure?.let { error -> fail(IllegalStateException("UHD high-speed relay stopped", error)); return }
                onCaptureTelemetry?.invoke(result)
                if (selection.hyperlapseAuto) onHyperlapseCapture?.invoke(result)
                observedFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                val now = SystemClock.elapsedRealtime()
                if (selection.slowMotion && recording && now - lastSlowMotionTelemetryAt >= 1000) {
                    lastSlowMotionTelemetryAt = now
                    val actualZoom = if (Build.VERSION.SDK_INT >= 30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
                    val physical = if (Build.VERSION.SDK_INT >= 28)
                        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
                    Log.i("DiveSlowMotion", "Capture fps=${sourceRange?.upper} " +
                        "sensorDurationNs=${result.get(CaptureResult.SENSOR_FRAME_DURATION)} " +
                        "exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
                        "iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                        "af=${result.get(CaptureResult.CONTROL_AF_MODE)} focus=$observedFocus " +
                        "antibanding=${result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE)} " +
                        "ev=${result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)} " +
                        "flash=${result.get(CaptureResult.FLASH_MODE)} " +
                        "ois=${result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)} " +
                        "zoom=$actualZoom physical=$physical")
                }
                // High-speed callbacks expose the wrapped regular session, not the public
                // CameraConstrainedHighSpeedCaptureSession object. Generation owns identity.
                if (!ready) {
                    nativeReadyTimeout?.let(main::removeCallbacks)
                    nativeReadyTimeout = null
                    ready = true
                    Log.i("DivePrepared", "Preview ready $config; camera session prepared before shutter")
                    onReady?.invoke()
                    if (config?.directPreview == true) view?.postOnAnimation {
                        if (token == generation) onPresented?.invoke()
                    }
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
        if (stopNativeRecording) {
            SamsungNativeLogPipeline.setRecordingStopTrigger(builder, true)
            currentSession.capture(builder.build(), null, main)
            SamsungNativeLogPipeline.setRecordingStopTrigger(builder, false)
        }
        if (currentSession is CameraConstrainedHighSpeedCaptureSession)
            currentSession.setRepeatingBurst(currentSession.createHighSpeedRequestList(builder.build()), callback, main)
        else if (recording && selection.uhdProfile != null && !selection.relay &&
            (selection.uhdProfile.samsungSlowMotion || reduceUhdPreview)) {
            // Every request feeds the UHD encoder. Only the first also updates the display.
            val withPreview = builder.build()
            builder.removeTarget(checkNotNull(previewInput))
            val encoderOnly = builder.build()
            val viewfinderFps = if (reduceUhdPreview && selection.uhdProfile.samsungSlowMotion)
                SamsungUhdSlowMotion.THERMAL_VIEWFINDER_FPS else 30
            val burst = List(selection.uhdProfile.fps / viewfinderFps) { if (it == 0) withPreview else encoderOnly }
            try {
                currentSession.setRepeatingBurst(burst, callback, main)
            } catch (error: IllegalArgumentException) {
                // Some regular sessions accept only a single target combination.
                Log.w("DivePrepared", "Using full preview cadence for this session", error)
                currentSession.setRepeatingRequest(withPreview, callback, main)
            }
        } else currentSession.setRepeatingRequest(builder.build(), callback, main)
        relayOutput?.recording = recording
    }

    fun start(output: File, onStarted: () -> Unit, onFinalized: (Result<Long>) -> Unit) {
        check(isReady && !isBusy) { "Recording session is not ready" }
        destination = output
        this.onFinalized = onFinalized
        try {
            if (config?.highSpeedFps == null && config?.nativeLog != true && controls.focus == null) {
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
        if (config?.nativeLog == true &&
            (nativePendingTracks > 0 || SystemClock.elapsedRealtime() - nativeTracksStartedAt < 150L)) {
            if (elapsedDurationMs >= SamsungNativeLogPipeline.READY_TIMEOUT_MS) {
                fail(IllegalStateException("Native LOG encoder did not start every recording track."))
                return
            }
            deferredStop?.let(main::removeCallbacks)
            deferredStop = Runnable { deferredStop = null; stop() }.also { main.postDelayed(it, 100L) }
            return
        }
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
        val slowMotion = config?.slowMotion == true
        val nativeLog = config?.nativeLog == true
        val completedFile = checkNotNull(preparedFile)
        val output = checkNotNull(destination)
        val callback = onFinalized
        // Samsung stops the recorder BEFORE removing its camera target. Stabilized frames arrive
        // late; removing the target here truncates video while audio continues (~670 ms on S24).
        // Its persistent input surface survives stop/rearm, matching Samsung's session lifecycle.
        if (!nativeLog) try { repeat(recording = false) } catch (error: Throwable) { fail(error); return }
        recorder = null
        preparedFile = null
        finalizer.execute {
            val result = runCatching {
                try { completedRecorder.stop() } finally { completedRecorder.release() }
                check(completedFile.length() > 0L) { "Recorder produced no frames" }
                val encodedDuration = if (nativeLog) SamsungNativeLogPipeline.validateOutput(completedFile) else duration
                output.parentFile?.mkdirs()
                check(completedFile.renameTo(output)) { "Could not retain recording segment" }
                encodedDuration
            }
            completedFile.delete()
            main.post {
                if (token != generation) { output.delete(); return@post }
                if (nativeLog && !releaseAfterStop) {
                    try { repeat(recording = false, stopNativeRecording = true) } catch (error: Throwable) { fail(error); return@post }
                }
                destination = null
                startedAt = 0L
                stopping = false
                onFinalized = null
                val rearmSlowMotion = slowMotion && !releaseAfterStop
                if (releaseAfterStop) release()
                else if (!rearmSlowMotion) try {
                    if (nativeLog) {
                        ready = false
                    }
                    prepareNextRecorder()
                    if (nativeLog) {
                        armNativeReadyTimeout(token)
                        repeat(recording = false)
                    }
                } catch (error: Throwable) { fail(error) }
                Log.i("DivePrepared", "Pause finalized in ${SystemClock.elapsedRealtime() - pauseAt}ms; preview session retained=$ownsCamera")
                callback?.invoke(result)
                if (rearmSlowMotion && token == generation) prepareSlowMotionAfterStop(token)
            }
        }
    }

    fun suspendPreview() {
        if (isBusy) {
            releaseAfterStop = true
            stop()
        } else release()
    }

    fun dispose() {
        release()
        finalizer.shutdown()
    }

    private fun fail(error: Throwable) {
        Log.e("DivePrepared", "Prepared recording failed", error)
        val callback = onFinalized
        val report = onError
        release()
        if (callback != null) callback(Result.failure(error)) else report?.invoke(error)
    }

    private fun armNativeReadyTimeout(token: Int) {
        nativeReadyTimeout?.let(main::removeCallbacks)
        nativeReadyTimeout = Runnable {
            if (token == generation && !ready) fail(IllegalStateException(
                "Samsung native LOG did not confirm capture mode 6 within 8 seconds. Recording is unavailable."))
        }.also { main.postDelayed(it, SamsungNativeLogPipeline.READY_TIMEOUT_MS) }
    }

    fun release() {
        generation++
        nativeReadyTimeout?.let(main::removeCallbacks)
        nativeReadyTimeout = null
        availability?.let { context.getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(it) }
        availability = null
        deferredStop?.let(main::removeCallbacks)
        deferredStop = null
        ready = false
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { relay?.release() }
        relay = null
        nightAccumulator?.release()
        nightAccumulator = null
        nightInput = null
        focusPreview?.release()
        focusPreview = null
        previewInput = null
        runCatching { recorder?.release() }
        runCatching { input?.release() }
        session = null
        device = null
        recorder = null
        recorderPreparing = false
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
        thermalPreviewRange = null
        preparedPreviewRange = null
        observedFocus = null
        releaseAfterStop = false
        singleAfTriggered = false
        lastSlowMotionTelemetryAt = 0L
        onReady = null
        onPresented = null
        onError = null
        onFinalized = null
    }

}
