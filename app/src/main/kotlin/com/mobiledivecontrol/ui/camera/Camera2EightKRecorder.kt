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
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodecInfo
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

internal const val EIGHT_K_WIDTH = 7680
internal const val EIGHT_K_HEIGHT = 4320
internal val EIGHT_K_SIZE: Size
    get() = Size(EIGHT_K_WIDTH, EIGHT_K_HEIGHT)

/**
 * Samsung's availableVideoConfigurations is a sequence of six-int rows:
 * width, height, minimum fps, maximum fps, recording limit and vendor flags.
 * Keep only the exact 8K rates the native recording table claims.
 */
internal fun samsungVendorEightKFrameRates(configurations: IntArray?): List<Int> =
    configurations
        ?.asList()
        ?.chunked(6)
        .orEmpty()
        .filter { row -> row.size == 6 && row[0] == EIGHT_K_WIDTH && row[1] == EIGHT_K_HEIGHT }
        .flatMap { row -> listOf(24, 30).filter { fps -> fps in row[2]..row[3] } }
        .distinct()
        .sorted()

/**
 * Dedicated 8K Camera2 + HEVC recorder.
 *
 * CameraX's highest public quality is UHD on the S24 even though Samsung's vendor table exposes
 * 7680x4320. This class owns that vendor-advertised surface only while recording. It verifies the
 * muxed track before accepting the segment, so a HAL fallback to UHD can never be called 8K.
 */
internal class Camera2EightKRecorder(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DiveEightK"
        private val SAFE_PREVIEW_SIZE = Size(1920, 1080)
        private const val VIDEO_BITRATE_BPS = 100_000_000
    }

    data class Request(
        val previewHost: PreviewView,
        val cameraId: String,
        val fps: Int,
        val outputFile: File,
        val audioEnabled: Boolean,
        val exposureCompensationIndex: Int?,
        val zoomRatio: Float,
        val torchEnabled: Boolean,
        val focusDiopters: Float?,
        /** LOG in this app is the honest public 10-bit BT.2020 HLG acquisition contract. */
        val hdrLogMode: String,
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
        get() = if (startedAtElapsedMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtElapsedMs

    fun start(
        request: Request,
        onStarted: () -> Unit,
        onFinalized: (Result<Long>) -> Unit,
    ) {
        check(!isBusy) { "An 8K recording is already active or starting" }
        require(request.fps == 24 || request.fps == 30) { "8K requires 24 or 30 fps" }
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
            // Do not ask SurfaceFlinger for an 8K display buffer. The encoder alone owns 8K.
            holder.setFixedSize(SAFE_PREVIEW_SIZE.width, SAFE_PREVIEW_SIZE.height)
        }
        surfaceView = surface
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (this@Camera2EightKRecorder.request !== request) return
                configureAndOpen(request, holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (this@Camera2EightKRecorder.request === request && !finalizeDelivered) {
                    fail(IllegalStateException("8K preview surface was destroyed"))
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
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            recorder.setVideoSize(EIGHT_K_SIZE.width, EIGHT_K_SIZE.height)
            recorder.setVideoFrameRate(request.fps)
            recorder.setVideoEncodingBitRate(VIDEO_BITRATE_BPS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recorder.setVideoEncodingProfileLevel(
                    if (request.hdrLogMode == "LOG") {
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    } else {
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    },
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6,
                )
            }
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
                        if (this@Camera2EightKRecorder.request !== request) {
                            device.close()
                            return
                        }
                        cameraDevice = device
                        createSession(device, holder, recorder, request)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        fail(IllegalStateException("8K camera disconnected"))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        fail(IllegalStateException("8K camera open error $error"))
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
            val sessionParameters = buildRequest(
                device = device,
                request = request,
                previewSurface = previewSurface,
                recordingSurface = recordingSurface,
                addTargets = false,
            )
            val outputs = listOf(
                OutputConfiguration(previewSurface),
                OutputConfiguration(recordingSurface).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && request.hdrLogMode == "LOG") {
                        setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                    }
                },
            )
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                ContextCompat.getMainExecutor(context),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (this@Camera2EightKRecorder.request !== request) {
                            configured.close()
                            return
                        }
                        session = configured
                        try {
                            val repeating = buildRequest(
                                device = device,
                                request = request,
                                previewSurface = previewSurface,
                                recordingSurface = recordingSurface,
                                addTargets = true,
                            )
                            configured.setRepeatingRequest(repeating, null, handler)
                            recorder.start()
                            startedAtElapsedMs = SystemClock.elapsedRealtime()
                            Log.i(
                                TAG,
                                "Started verified-target 7680x4320 HEVC ${request.fps}fps " +
                                    "camera=${request.cameraId} dynamicRange=${request.hdrLogMode} " +
                                    "audio=${request.audioEnabled}",
                            )
                            onStarted?.invoke()
                            if (stopRequested) stop()
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        fail(
                            IllegalStateException(
                                "Samsung HAL rejected the 7680x4320 HEVC stream combination",
                            ),
                        )
                    }
                },
            ).apply {
                setSessionParameters(sessionParameters)
            }
            device.createCaptureSession(configuration)
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun buildRequest(
        device: CameraDevice,
        request: Request,
        previewSurface: android.view.Surface,
        recordingSurface: android.view.Surface,
        addTargets: Boolean,
    ): CaptureRequest {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            if (addTargets) {
                addTarget(previewSurface)
                addTarget(recordingSurface)
            }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(request.fps, request.fps))
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            if (request.focusDiopters != null) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, request.focusDiopters)
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
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
            if (request.hdrLogMode == "HDR") {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
            } else {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }
            // These are session parameters in Samsung Camera's own Pro Video configuration.
            // Unsupported vendor tags throw at set(); each one is independently optional.
            trySetVendor("samsung.android.control.cameraClient", 0)
            trySetVendor("samsung.android.control.shootingMode", 35)
            if (request.hdrLogMode == "LOG") {
                // MODE_BT2020_VIDEO in Samsung's DeviceConfiguration.Parameters.ColorSpaceMode.
                trySetVendor("samsung.android.control.colorSpaceMode", 3)
            }
        }.build()
    }

    private fun CaptureRequest.Builder.trySetVendor(name: String, value: Int) {
        runCatching {
            set(CaptureRequest.Key(name, Int::class.javaObjectType), value)
        }.onFailure { error -> Log.w(TAG, "Vendor session parameter unavailable: $name", error) }
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
            val activeRequest = request ?: throw IllegalStateException("8K request disappeared")
            val file = activeRequest.outputFile
            if (!file.isFile || file.length() <= 0L) {
                throw IllegalStateException("8K recorder produced no video")
            }
            verifyEncodedStream(file, activeRequest)
            finish(Result.success(duration))
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun verifyEncodedStream(file: File, request: Request) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("8K output has no video track")
            val format = extractor.getTrackFormat(videoTrack)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (width != EIGHT_K_SIZE.width || height != EIGHT_K_SIZE.height) {
                throw IllegalStateException("HAL returned ${width}x$height instead of 7680x4320")
            }
            if (mime != MediaFormat.MIMETYPE_VIDEO_HEVC) {
                throw IllegalStateException("8K output is $mime instead of HEVC")
            }
            if (request.hdrLogMode == "LOG") {
                val standard = format.integerOrNull(MediaFormat.KEY_COLOR_STANDARD)
                val transfer = format.integerOrNull(MediaFormat.KEY_COLOR_TRANSFER)
                if (standard != MediaFormat.COLOR_STANDARD_BT2020 || transfer != MediaFormat.COLOR_TRANSFER_HLG) {
                    throw IllegalStateException(
                        "8K Log-grade output is not tagged BT.2020 HLG " +
                            "(standard=$standard transfer=$transfer)",
                    )
                }
            }
            Log.i(TAG, "Encoded stream verified as genuine 8K HEVC: $format")
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    fun release() {
        if (!isBusy) return
        request?.outputFile?.delete()
        cleanup()
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "8K recording failed", error)
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
