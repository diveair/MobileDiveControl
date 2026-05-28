package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraState
import kotlin.math.roundToInt

class CameraRuntimeController(
    private val context: Context,
) {
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var boundLensFacing: Int? = null
    private var boundResolution: String? = null
    private var latestState: CameraState = CameraState()
    private var latestWaterPressureKpa: Double? = null
    private var latestAtmosphericPressureKpa: Double? = null

    fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        initialState: CameraState,
        onReady: (Boolean) -> Unit,
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        latestState = initialState

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCamera(force = true)
                onReady(camera != null)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun detach() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        previewView = null
        lifecycleOwner = null
        boundLensFacing = null
        boundResolution = null
    }

    fun applyState(
        cameraState: CameraState,
        waterPressureKpa: Double?,
        atmosphericPressureKpa: Double?,
    ) {
        latestState = cameraState
        latestWaterPressureKpa = waterPressureKpa
        latestAtmosphericPressureKpa = atmosphericPressureKpa
        if (cameraProvider == null || previewView == null || lifecycleOwner == null) {
            return
        }

        val desiredLensFacing = desiredLensFacing(cameraState)
        val desiredResolution = desiredResolutionValue(cameraState)
        val needsRebind = desiredLensFacing != boundLensFacing || desiredResolution != boundResolution
        if (needsRebind) {
            bindCamera(force = true)
        } else {
            applySessionState(cameraState)
        }
    }

    fun execute(command: CameraCommand) {
        when (command) {
            CameraCommand.CapturePhoto -> capturePhoto()
            CameraCommand.OpenGallery -> openGallery()
            else -> Unit
        }
    }

    private fun bindCamera(force: Boolean = false) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val previewSurface = previewView ?: return
        val desiredLensFacing = desiredLensFacing(latestState)
        val desiredResolution = desiredResolutionValue(latestState)

        if (!force && desiredLensFacing == boundLensFacing && desiredResolution == boundResolution) {
            applySessionState(latestState)
            return
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(desiredLensFacing)
            .build()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewSurface.surfaceProvider) }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

        resolutionFor(desiredResolution)?.let { imageCaptureBuilder.setTargetResolution(it) }

        val capture = imageCaptureBuilder.build()

        provider.unbindAll()
        camera = provider.bindToLifecycle(owner, selector, preview, capture)
        imageCapture = capture
        boundLensFacing = desiredLensFacing
        boundResolution = desiredResolution
        applySessionState(latestState)
    }

    private fun applySessionState(cameraState: CameraState) {
        val boundCamera = camera ?: return
        applyFlash(cameraState)
        applyExposure(cameraState, boundCamera)
        applyZoom(cameraState, boundCamera)
        applyCamera2Options(cameraState, boundCamera)
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val name = "DiveControl_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Mobile DiveControl",
                )
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = Unit
                override fun onError(exception: ImageCaptureException) = Unit
            },
        )
    }

    private fun applyFlash(cameraState: CameraState) {
        val flashValue = currentValue(cameraState, ".flash") ?: return
        val capture = imageCapture ?: return
        capture.flashMode = when (flashValue) {
            "Auto" -> ImageCapture.FLASH_MODE_AUTO
            "On", "Torch" -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(flashValue == "On" || flashValue == "Torch")
    }

    private fun applyExposure(cameraState: CameraState, boundCamera: Camera) {
        val exposureValue = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
            ?.replace("+", "")
            ?.toDoubleOrNull()
            ?: return
        val exposureState = boundCamera.cameraInfo.exposureState
        val compensationStep = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 1f
        val requestedIndex = (exposureValue / compensationStep).roundToInt()
        val clamped = requestedIndex.coerceIn(
            exposureState.exposureCompensationRange.lower,
            exposureState.exposureCompensationRange.upper,
        )
        boundCamera.cameraControl.setExposureCompensationIndex(clamped)
    }

    private fun applyZoom(cameraState: CameraState, boundCamera: Camera) {
        val lensValue = currentValue(cameraState, ".lens") ?: "1x"
        val lensZoom = when (lensValue) {
            "0.6x" -> 0.6
            "1x" -> 1.0
            "2x" -> 2.0
            "3x" -> 3.0
            "5x" -> 5.0
            else -> 1.0
        }
        val requestedZoom = if (lensValue == "front") {
            1.0
        } else {
            lensZoom
        }
        val zoomState = boundCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio?.toDouble() ?: 1.0
        val maxZoom = zoomState?.maxZoomRatio?.toDouble() ?: 8.0
        val clamped = requestedZoom.coerceIn(minZoom, maxZoom)
        boundCamera.cameraControl.setZoomRatio(clamped.toFloat())
    }

    private fun applyCamera2Options(cameraState: CameraState, boundCamera: Camera) {
        val builder = CaptureRequestOptions.Builder()
        val filterProfile = underwaterFilterProfile(
            value = currentValue(cameraState, ".filters"),
            depthMeters = currentDepthMeters(),
        )
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        val focusValue = currentValue(cameraState, ".manual_focus")
        val minimumFocusDistance = Camera2CameraInfo.from(boundCamera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (focusValue == null || focusValue == "AF" || minimumFocusDistance <= 0f) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
        } else {
            val normalizedFocus = focusValue.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                (minimumFocusDistance * normalizedFocus).coerceIn(0f, minimumFocusDistance),
            )
        }

        when (resolvedHdrLogMode(cameraState)) {
            "HDR" -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_MODE,
                    CameraMetadata.CONTROL_MODE_USE_SCENE_MODE,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CameraMetadata.CONTROL_SCENE_MODE_HDR,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.TONEMAP_MODE,
                    CameraMetadata.TONEMAP_MODE_HIGH_QUALITY,
                )
            }
            "LOG" -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_MODE,
                    CameraMetadata.CONTROL_MODE_AUTO,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.TONEMAP_MODE,
                    CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.TONEMAP_CURVE,
                    flatLogCurve(),
                )
            }
            else -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_MODE,
                    CameraMetadata.CONTROL_MODE_AUTO,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.TONEMAP_MODE,
                    CameraMetadata.TONEMAP_MODE_FAST,
                )
            }
        }

        if (filterProfile == null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_FAST,
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_OFF,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                filterProfile,
            )
        }

        Camera2CameraControl.from(boundCamera.cameraControl)
            .setCaptureRequestOptions(builder.build())
    }

    private fun openGallery() {
        val intents = buildList {
            add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY))
            add(
                Intent(
                    Intent.ACTION_VIEW,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ),
            )
            add(
                Intent(
                    Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ),
            )
            add(
                Intent(
                    Intent.ACTION_VIEW,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ),
            )
        }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }

    private fun desiredLensFacing(cameraState: CameraState): Int {
        return if (currentValue(cameraState, ".lens") == "front") {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    private fun desiredResolutionValue(cameraState: CameraState): String? {
        return currentValue(cameraState, ".megapixels")
    }

    private fun resolvedHdrLogMode(cameraState: CameraState): String {
        currentValue(cameraState, ".hdr_log")?.let { return it }
        if (currentValue(cameraState, ".log") == "On") {
            return "LOG"
        }
        val hdr = currentValue(cameraState, ".hdr")
        return if (hdr == "On" || hdr == "HDR") "HDR" else "Off"
    }

    private fun currentValue(cameraState: CameraState, vararg suffixes: String): String? {
        val settings = CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
        val spec = settings.firstOrNull { setting -> suffixes.any { suffix -> setting.id.endsWith(suffix) } }
            ?: return null
        return CameraCatalog.currentValue(cameraState, spec)
    }

    private fun resolutionFor(value: String?): Size? = when (value) {
        "12MP" -> Size(4000, 3000)
        "24MP" -> Size(6000, 4000)
        "50MP" -> Size(8160, 6120)
        "200MP" -> Size(16320, 12240)
        else -> null
    }

    private fun underwaterFilterProfile(value: String?, depthMeters: Double?): RggbChannelVector? {
        val targetDepth = when {
            value == null || value == "Off" -> return null
            value == "Auto" -> depthMeters ?: 0.0
            value.endsWith("m") -> value.removeSuffix("m").toDoubleOrNull() ?: return null
            else -> return null
        }.coerceIn(0.0, 50.0)

        // Beer-Lambert attenuation model for clear seawater.
        val redAttenuation = 0.15
        val greenAttenuation = 0.07
        val blueAttenuation = 0.03

        val redGain = kotlin.math.exp((redAttenuation - blueAttenuation) * targetDepth).toFloat()
            .coerceIn(1.0f, 8.0f)
        val greenGain = kotlin.math.exp((greenAttenuation - blueAttenuation) * targetDepth).toFloat()
            .coerceIn(1.0f, 4.0f)
        val blueGain = 1.0f
        return RggbChannelVector(redGain, greenGain, greenGain, blueGain)
    }

    private fun currentDepthMeters(): Double? {
        val water = latestWaterPressureKpa
        val atmospheric = latestAtmosphericPressureKpa
        return if (water != null && atmospheric != null) {
            ((water - atmospheric).coerceAtLeast(0.0)) / 9.81
        } else {
            null
        }
    }

    private fun flatLogCurve(): TonemapCurve {
        val channel = floatArrayOf(
            0.00f, 0.00f,
            0.10f, 0.04f,
            0.25f, 0.14f,
            0.50f, 0.35f,
            0.75f, 0.64f,
            0.90f, 0.84f,
            1.00f, 1.00f,
        )
        return TonemapCurve(channel, channel, channel)
    }
}
