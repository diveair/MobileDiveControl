package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
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
    companion object {
        private const val TAG = "DiveCameraCtrl"
    }

    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var boundLensFacing: Int? = null
    private var boundResolution: String? = null
    private var boundHdrExtension: Boolean = false
    private var boundFocusMode: Boolean = false // true = manual focus, false = AF
    private var latestState: CameraState = CameraState()
    private var latestWaterPressureKpa: Double? = null
    private var latestAtmosphericPressureKpa: Double? = null

    // Device capabilities detected once at attach time via CameraManager
    private var deviceMinFocusDistance: Float = 10f // Safe default — most phones have ~10 diopters
    private var deviceHasVendorHdr: Boolean = false
    private var deviceMaxSupportedResolution: Size? = null
    private var capabilitiesDetected: Boolean = false

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
                // Detect capabilities using CameraManager (works regardless of extensions)
                if (!capabilitiesDetected) {
                    detectDeviceCapabilitiesViaCameraManager()
                    capabilitiesDetected = true
                }
                // Initialize extensions manager for vendor HDR
                initExtensions {
                    bindCamera(force = true)
                    onReady(camera != null)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun initExtensions(onDone: () -> Unit) {
        val provider = cameraProvider ?: run { onDone(); return }
        val future = ExtensionsManager.getInstanceAsync(context, provider)
        future.addListener(
            {
                try {
                    extensionsManager = future.get()
                    val backSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()
                    deviceHasVendorHdr = extensionsManager?.isExtensionAvailable(
                        backSelector,
                        ExtensionMode.HDR,
                    ) ?: false
                } catch (_: Exception) {
                    deviceHasVendorHdr = false
                }
                onDone()
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
        boundHdrExtension = false
    }

    fun applyState(
        cameraState: CameraState,
        waterPressureKpa: Double?,
        atmosphericPressureKpa: Double?,
    ) {
        val focusVal = cameraState.settingValues["photo.manual_focus"]
        Log.d(TAG, "applyState called: focus=$focusVal")
        latestState = cameraState
        latestWaterPressureKpa = waterPressureKpa
        latestAtmosphericPressureKpa = atmosphericPressureKpa
        if (cameraProvider == null || previewView == null || lifecycleOwner == null) {
            Log.d(TAG, "applyState: early return (provider/preview/lifecycle null)")
            return
        }

        val desiredLensFacing = desiredLensFacing(cameraState)
        val desiredResolution = desiredResolutionValue(cameraState)
        // Only rebind when switching between AF and manual focus (not every distance change)
        val desiredManualFocus = currentValue(cameraState, ".manual_focus").let {
            it != null && it != "AF" && deviceMinFocusDistance > 0f
        }
        val needsRebind = desiredLensFacing != boundLensFacing ||
                desiredResolution != boundResolution ||
                desiredManualFocus != boundFocusMode
        if (needsRebind) {
            Log.d(TAG, "applyState: rebinding camera")
            bindCamera(force = true)
        } else {
            Log.d(TAG, "applyState: applying session state")
            applySessionState(cameraState)
        }
    }

    fun execute(command: CameraCommand) {
        when (command) {
            CameraCommand.CapturePhoto -> capturePhoto()
            CameraCommand.OpenGallery -> openGallery()
            is CameraCommand.SetManualFocus,
            is CameraCommand.SetIso,
            is CameraCommand.SetShutterSpeedNs,
            is CameraCommand.SetWhiteBalanceKelvin,
            is CameraCommand.SetExposureCompensation,
            is CameraCommand.SetFlashMode,
            is CameraCommand.SetHdrLogMode,
            is CameraCommand.SetFilter -> applySessionState(latestState)
            else -> Unit
        }
    }

    private fun bindCamera(force: Boolean = false) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val previewSurface = previewView ?: return
        val desiredLensFacing = desiredLensFacing(latestState)
        val desiredResolution = desiredResolutionValue(latestState)

        if (!force && desiredLensFacing == boundLensFacing &&
            desiredResolution == boundResolution) {
            applySessionState(latestState)
            return
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(desiredLensFacing)
            .build()
        // Note: vendor HDR extension (ExtensionMode.HDR) is intentionally NOT used.
        // It blocks Camera2 interop, preventing manual focus/ISO/shutter from working.
        // HDR is applied via Camera2 SCENE_MODE_HDR in applyCamera2Options instead.

        // Check if manual focus is active — if so, disable AF at bind time via Camera2Interop
        val focusValue = currentValue(latestState, ".manual_focus")
        val isManualFocus = focusValue != null && focusValue != "AF" && deviceMinFocusDistance > 0f

        val previewBuilder = Preview.Builder()
        if (isManualFocus) {
            // Set AF_MODE_OFF in the repeating request template at bind time.
            // This is the only reliable way to disable Samsung's AF pipeline —
            // runtime Camera2 interop is accepted but Samsung HAL ignores AF_MODE changes.
            val normalizedFocus = focusValue!!.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            val focusDistance = (deviceMinFocusDistance * normalizedFocus).coerceIn(0f, deviceMinFocusDistance)
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            Log.d(TAG, "bindCamera: AF_MODE_OFF + focus=$focusDistance set at bind time via Extender")
        }

        val preview = previewBuilder
            .build()
            .also { it.setSurfaceProvider(previewSurface.surfaceProvider) }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

        // Use ResolutionSelector for better resolution support including high-res modes
        val targetSize = resolutionFor(desiredResolution)
        if (targetSize != null) {
            try {
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                imageCaptureBuilder.setResolutionSelector(resolutionSelector)
            } catch (_: Exception) {
                // Fall back to target resolution if ResolutionSelector not supported
                @Suppress("DEPRECATION")
                imageCaptureBuilder.setTargetResolution(targetSize)
            }
        }

        val capture = imageCaptureBuilder.build()

        provider.unbindAll()
        camera = provider.bindToLifecycle(owner, selector, preview, capture)
        imageCapture = capture
        boundLensFacing = desiredLensFacing
        boundResolution = desiredResolution
        boundFocusMode = isManualFocus

        // Detect device capabilities from the bound camera
        applySessionState(latestState)
    }

    private fun detectDeviceCapabilitiesViaCameraManager() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Find the back-facing camera for capabilities
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    deviceMinFocusDistance = chars.get(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                    ) ?: 10f

                    val streamConfigMap = chars.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                    )
                    if (streamConfigMap != null) {
                        val outputSizes = streamConfigMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
                        deviceMaxSupportedResolution = outputSizes?.maxByOrNull { it.width * it.height }
                    }
                    break
                }
            }
        } catch (_: Exception) {
            // Keep safe defaults
        }
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
        val compensationStep = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 0.1f
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

        // --- Manual Focus ---
        val focusValue = currentValue(cameraState, ".manual_focus")
        val minimumFocusDistance = deviceMinFocusDistance
        val isManualFocus = focusValue != null && focusValue != "AF" && minimumFocusDistance > 0f

        Log.d(TAG, "applyCamera2Options: focus=$focusValue, minDist=$minimumFocusDistance, manual=$isManualFocus, hdrExt=$boundHdrExtension")

        if (isManualFocus) {
            // Cancel CameraX autofocus BEFORE setting Camera2 options
            try {
                boundCamera.cameraControl.cancelFocusAndMetering()
            } catch (e: Exception) {
                Log.w(TAG, "cancelFocusAndMetering failed", e)
            }

            val normalizedFocus = focusValue!!.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            val focusDistance = (minimumFocusDistance * normalizedFocus).coerceIn(0f, minimumFocusDistance)
            Log.d(TAG, "Manual focus: normalized=$normalizedFocus, distance=${focusDistance} diopters")

            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistance,
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
        }

        // --- Effect mode ---
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // --- HDR / LOG / Off ---
        if (!boundHdrExtension) {
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
                }
            }
        }

        // --- White Balance & Color Correction ---
        val filterProfile = underwaterFilterProfile(
            value = currentValue(cameraState, ".filters"),
            depthMeters = currentDepthMeters(),
        )
        if (filterProfile == null) {
            val wbValue = currentValue(cameraState, ".white_balance")
            if (wbValue != null && wbValue != "Auto") {
                val kelvin = wbValue.removeSuffix("K").toIntOrNull()
                if (kelvin != null) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE,
                        CameraMetadata.CONTROL_AWB_MODE_OFF,
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE,
                        CameraMetadata.CONTROL_AWB_MODE_AUTO,
                    )
                }
            } else {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_AUTO,
                )
            }
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

        // --- ISO (manual sensitivity) ---
        val isoValue = currentValue(cameraState, ".iso")?.toIntOrNull()
        if (isoValue != null) {
            try {
                val isoRange = Camera2CameraInfo.from(boundCamera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (isoRange != null) {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,
                        isoValue.coerceIn(isoRange.lower, isoRange.upper))
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_OFF)
                }
            } catch (_: Exception) { }
        }

        // --- Shutter Speed ---
        val shutterNs = currentValue(cameraState, ".shutter_speed")?.let { parseShutterNs(it) }
        if (shutterNs != null) {
            try {
                val exposureRange = Camera2CameraInfo.from(boundCamera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (exposureRange != null) {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME,
                        shutterNs.coerceIn(exposureRange.lower, exposureRange.upper))
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_OFF)
                }
            } catch (_: Exception) { }
        }

        // Clear previous options first, then apply new ones atomically
        try {
            val cam2Control = Camera2CameraControl.from(boundCamera.cameraControl)
            cam2Control.clearCaptureRequestOptions()
            val future = cam2Control.addCaptureRequestOptions(builder.build())
            // Check the future result on a background thread
            future.addListener({
                try {
                    future.get()
                    Log.d(TAG, "Camera2 options CONFIRMED by HAL")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera2 options REJECTED by HAL: ${e.message}")
                }
            }, { it.run() })
            Log.d(TAG, "Camera2 options submitted")
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 options FAILED to submit", e)
        }
    }

    private fun parseShutterNs(value: String): Long? {
        if (value == "Auto") return null
        return if (value.endsWith("\"")) {
            value.removeSuffix("\"").toDoubleOrNull()?.let { (it * 1_000_000_000L).toLong() }
        } else {
            val parts = value.split("/")
            if (parts.size == 2) {
                val num = parts[0].toDoubleOrNull() ?: return null
                val den = parts[1].toDoubleOrNull() ?: return null
                if (den == 0.0) return null
                ((num / den) * 1_000_000_000L).toLong()
            } else {
                null
            }
        }
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
        "200MP" -> {
            // Use detected max resolution if it's >= 200MP equivalent, otherwise
            // request the largest known 200MP size and let CameraX fall back
            val maxRes = deviceMaxSupportedResolution
            if (maxRes != null && maxRes.width.toLong() * maxRes.height >= 100_000_000L) {
                maxRes // Use the actual max resolution reported by the device
            } else {
                Size(16320, 12240) // Standard 200MP 4:3
            }
        }
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
