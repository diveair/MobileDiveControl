package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.concurrent.Executors

class CameraRuntimeController(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DiveCameraCtrl"
    }

    private data class SessionSignature(
        val flash: String?,
        val exposure: String?,
        val lens: String?,
        val hdrLog: String?,
        val whiteBalance: String?,
        val filter: String?,
        val manualFocus: String?,
        val iso: String?,
        val shutter: String?,
        val resolution: String?,
        val waterPressureKpa: Double?,
        val atmosphericPressureKpa: Double?,
    )

    private data class PhysicalLensProfile(
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val facing: Int,
        val focalLengthMm: Float,
        val minFocusDistance: Float,
    ) {
        val supportsManualFocus: Boolean
            get() = minFocusDistance > 0f
    }

    private data class BackCameraProfile(
        val logicalCameraId: String,
        val logicalFocalLengthMm: Float,
        val physicalLenses: List<PhysicalLensProfile>,
        val maxSupportedResolution: Size?,
    )

    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var boundLensFacing: Int? = null
    private var boundResolution: String? = null
    private var boundHdrExtension: Boolean = false
    private var boundFocusMode: Boolean = false // true = manual focus, false = AF
    private var latestState: CameraState = CameraState()
    private var latestWaterPressureKpa: Double? = null
    private var latestAtmosphericPressureKpa: Double? = null
    private var frontCameraId: String? = null
    private var backCameraProfile: BackCameraProfile? = null
    private var backLensAssignments: Map<String, PhysicalLensProfile> = emptyMap()
    private var activeLensProfile: PhysicalLensProfile? = null
    private var allowDirectPhysicalCameraBinding: Boolean = true

    // Device capabilities detected once at attach time via CameraManager
    private var deviceMinFocusDistance: Float = 10f // Safe default — most phones have ~10 diopters
    private var deviceHasVendorHdr: Boolean = false
    private var deviceMaxSupportedResolution: Size? = null
    private var capabilitiesDetected: Boolean = false
    private var focusAssistEnabled: Boolean = false
    private var focusAssistOverlayCallback: ((Bitmap?) -> Unit)? = null
    private var lastFocusAssistFrameAtMs: Long = 0L
    private var lastFocusResultLogAtMs: Long = 0L
    private var lastAppliedSessionSignature: SessionSignature? = null
    private val focusAssistExecutor = Executors.newSingleThreadExecutor()

    // Direct Camera2 session access — needed because CameraX overrides LENS_FOCUS_DISTANCE
    private var capturedSession: CameraCaptureSession? = null
    private var lastDirectFocusDistance: Float = -1f

    private val sessionCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            super.onCaptureCompleted(session, request, result)
            // Capture the session reference so we can submit our own repeating requests
            if (capturedSession == null) {
                capturedSession = session
                Log.d(TAG, "Captured Camera2 session reference")
            }

            // Apply our focus distance directly if manual focus is active
            val requestedFocus = manualFocusNormalized(latestState)
            if (requestedFocus != null && deviceMinFocusDistance > 0f) {
                val desiredDistance = focusDistanceFor(requestedFocus)
                val actualDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                // If the actual focus distance doesn't match our desired, force it via
                // a direct repeating request on the Camera2 session
                if (kotlin.math.abs(actualDistance - desiredDistance) > 0.05f) {
                    applyDirectFocusDistance(session, request, desiredDistance)
                }
            }

            // Periodic logging
            val now = SystemClock.elapsedRealtime()
            if (now - lastFocusResultLogAtMs < 300L) {
                return
            }
            lastFocusResultLogAtMs = now
            val actualFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val lensState = result.get(CaptureResult.LENS_STATE)
            val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
            val runningPhysicalId = runningPhysicalCameraId(result)
            val vendorLensPos = samsungFocusLensPosition(result)
            Log.d(
                TAG,
                "CaptureResult focusDistance=$actualFocusDistance afMode=$afMode lensState=$lensState physical=$runningPhysicalId lensPos=$vendorLensPos requested=$requestedFocus",
            )
        }
    }

    fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        initialState: CameraState,
        onReady: (Boolean) -> Unit,
        onFocusAssistOverlay: (Bitmap?) -> Unit = {},
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        latestState = initialState
        focusAssistEnabled = isFocusAssistEnabled(initialState)
        focusAssistOverlayCallback = onFocusAssistOverlay

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
        imageAnalysis = null
        previewView = null
        lifecycleOwner = null
        boundLensFacing = null
        boundResolution = null
        boundHdrExtension = false
        boundFocusMode = false
        capturedSession = null
        lastDirectFocusDistance = -1f
        focusAssistEnabled = false
        lastAppliedSessionSignature = null
        dispatchFocusAssistOverlay(null)
        focusAssistOverlayCallback = null
    }

    fun applyState(
        cameraState: CameraState,
        waterPressureKpa: Double?,
        atmosphericPressureKpa: Double?,
    ) {
        latestState = cameraState
        latestWaterPressureKpa = waterPressureKpa
        latestAtmosphericPressureKpa = atmosphericPressureKpa
        focusAssistEnabled = isFocusAssistEnabled(cameraState)
        if (!focusAssistEnabled) {
            dispatchFocusAssistOverlay(null)
        }
        if (cameraProvider == null || previewView == null || lifecycleOwner == null) {
            Log.d(TAG, "applyState: early return (provider/preview/lifecycle null)")
            return
        }

        val desiredLensFacing = desiredLensFacing(cameraState)
        val desiredResolution = desiredResolutionValue(cameraState)
        val desiredManualFocus = effectiveManualFocusNormalized(cameraState) != null
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
            CameraCommand.RestartCamera -> bindCamera(force = true)
            else -> Unit
        }
    }

    private fun bindCamera(force: Boolean = false) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val previewSurface = previewView ?: return
        val desiredLensFacing = desiredLensFacing(latestState)
        val desiredResolution = desiredResolutionValue(latestState)
        activeLensProfile = selectedLensProfile(latestState)
        if (desiredLensFacing == CameraSelector.LENS_FACING_BACK) {
            deviceMinFocusDistance = activeLensProfile?.minFocusDistance ?: 0f
        } else {
            deviceMinFocusDistance = 0f
        }

        if (!force && desiredLensFacing == boundLensFacing &&
            desiredResolution == boundResolution) {
            applySessionState(latestState)
            return
        }

        val selectorBuilder = CameraSelector.Builder()
            .requireLensFacing(desiredLensFacing)
        val selectedCameraId = selectedCameraIdForBinding(latestState, desiredLensFacing)
        selectedCameraId?.let {
            selectorBuilder.addCameraFilter { cameraInfos ->
                cameraInfos.filter { cameraInfo ->
                    Camera2CameraInfo.from(cameraInfo).cameraId == it
                }
            }
        }
        val selector = selectorBuilder.build()
        // Note: vendor HDR extension (ExtensionMode.HDR) is intentionally NOT used.
        // It blocks Camera2 interop, preventing manual focus/ISO/shutter from working.
        // HDR is applied via Camera2 SCENE_MODE_HDR in applyCamera2Options instead.

        // Check if manual focus is active — if so, disable AF at bind time via Camera2Interop
        val manualFocus = effectiveManualFocusNormalized(latestState)
        val isManualFocus = manualFocus != null && deviceMinFocusDistance > 0f
        val physicalCameraId = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            desiredLensFacing == CameraSelector.LENS_FACING_BACK &&
            selectedCameraId == backCameraProfile?.logicalCameraId
        ) {
            activeLensProfile?.physicalCameraId
        } else {
            null
        }
        Log.d(
            TAG,
            "bindCamera: lens=${selectedLensValue(latestState)} cameraId=$selectedCameraId physical=$physicalCameraId minFocus=$deviceMinFocusDistance manual=$isManualFocus",
        )

        val previewBuilder = Preview.Builder()
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
        if (physicalCameraId != null) {
            Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalCameraId)
            Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(physicalCameraId)
            Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physicalCameraId)
        }
        if (isManualFocus) {
            // Set AF_MODE_OFF in the repeating request template at bind time.
            // This is the only reliable way to disable Samsung's AF pipeline —
            // runtime Camera2 interop is accepted but Samsung HAL ignores AF_MODE changes.
            val focusDistance = focusDistanceFor(manualFocus!!)
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            Camera2Interop.Extender(imageCaptureBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            Log.d(TAG, "bindCamera: AF_MODE_OFF + focus=$focusDistance set at bind time via Extender")
        }
        Camera2Interop.Extender(previewBuilder)
            .setSessionCaptureCallback(sessionCaptureCallback)

        val preview = previewBuilder
            .build()
            .also { it.setSurfaceProvider(previewSurface.surfaceProvider) }
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
        val analysis = analysisBuilder
            .build()
            .also { useCase ->
                useCase.setAnalyzer(focusAssistExecutor) { image ->
                    analyzeFocusAssistFrame(image)
                }
            }

        provider.unbindAll()
        camera = try {
            provider.bindToLifecycle(owner, selector, preview, capture, analysis)
        } catch (error: IllegalArgumentException) {
            val triedDirectPhysicalCamera = desiredLensFacing == CameraSelector.LENS_FACING_BACK &&
                allowDirectPhysicalCameraBinding &&
                selectedCameraId == activeLensProfile?.physicalCameraId &&
                selectedCameraId != backCameraProfile?.logicalCameraId
            if (!triedDirectPhysicalCamera) {
                throw error
            }
            Log.w(
                TAG,
                "Direct physical camera binding failed for cameraId=$selectedCameraId, falling back to logical multi-camera binding.",
                error,
            )
            allowDirectPhysicalCameraBinding = false
            bindCamera(force = true)
            return
        }
        imageCapture = capture
        imageAnalysis = analysis
        camera?.let { refreshBoundCameraCapabilities(it) }
        boundLensFacing = desiredLensFacing
        boundResolution = desiredResolution
        boundFocusMode = isManualFocus
        capturedSession = null // Reset — new session will be captured in callback
        lastDirectFocusDistance = -1f

        // Detect device capabilities from the bound camera
        applySessionState(latestState, force = true)
    }

    /**
     * Bypass CameraX focus control by submitting a repeating request directly
     * on the Camera2 CameraCaptureSession with our LENS_FOCUS_DISTANCE.
     *
     * CameraX 1.3.x's FocusMeteringControl overrides LENS_FOCUS_DISTANCE in its
     * managed repeating request even when AF_MODE is set to OFF via interop.
     * The CaptureResult confirms: afMode=0 (OFF) but focusDistance stays at 0.1.
     *
     * This method builds a new request from the CameraDevice template, copies
     * the output surfaces from the existing CameraX request, and sets our focus.
     */
    private fun applyDirectFocusDistance(
        session: CameraCaptureSession,
        existingRequest: CaptureRequest,
        focusDistance: Float,
    ) {
        if (kotlin.math.abs(focusDistance - lastDirectFocusDistance) < 0.01f) {
            return // Already applied this distance
        }
        try {
            // Build a new capture request from scratch using the camera device
            val device = session.device
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            // The key settings: disable AF and set our focus distance
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)

            // Copy output surfaces from the existing CameraX request.
            // CaptureRequest internally stores targets — we access them via
            // the package-private getTargets() method.
            try {
                val method = CaptureRequest::class.java.getDeclaredMethod("getTargets")
                method.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val targets = method.invoke(existingRequest) as? Collection<android.view.Surface>
                if (targets != null && targets.isNotEmpty()) {
                    targets.forEach { builder.addTarget(it) }
                } else {
                    Log.w(TAG, "Direct focus: no surfaces found in existing request, cannot proceed")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct focus: cannot access surfaces from CaptureRequest", e)
                return
            }

            session.setRepeatingRequest(builder.build(), sessionCaptureCallback, null)
            lastDirectFocusDistance = focusDistance
            Log.d(TAG, "Direct Camera2 focus applied: distance=$focusDistance diopters")
        } catch (e: Exception) {
            Log.e(TAG, "Direct Camera2 focus FAILED: ${e.message}", e)
        }
    }

    private fun detectDeviceCapabilitiesViaCameraManager() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            frontCameraId = cameraManager.cameraIdList.firstOrNull { cameraId ->
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                backCameraProfile = cameraManager.cameraIdList
                    .mapNotNull { cameraId ->
                        val chars = cameraManager.getCameraCharacteristics(cameraId)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        if (facing != CameraCharacteristics.LENS_FACING_BACK) {
                            return@mapNotNull null
                        }
                        val physicalIds = chars.physicalCameraIds
                        if (physicalIds.isEmpty()) {
                            return@mapNotNull null
                        }
                        val logicalFocalLength = chars.get(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                        )?.firstOrNull() ?: return@mapNotNull null
                        val physicalLenses = physicalIds.mapNotNull physicalLoop@{ physicalId ->
                            val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                            val physicalFocalLength = physicalChars.get(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                            )?.firstOrNull() ?: return@physicalLoop null
                            PhysicalLensProfile(
                                logicalCameraId = cameraId,
                                physicalCameraId = physicalId,
                                facing = physicalChars.get(CameraCharacteristics.LENS_FACING)
                                    ?: CameraCharacteristics.LENS_FACING_BACK,
                                focalLengthMm = physicalFocalLength,
                                minFocusDistance = physicalChars.get(
                                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                                ) ?: 0f,
                            )
                        }.distinctBy { it.physicalCameraId }
                        if (physicalLenses.isEmpty()) {
                            return@mapNotNull null
                        }
                        val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val maxResolution = streamConfigMap
                            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                            ?.maxByOrNull { it.width * it.height }
                        BackCameraProfile(
                            logicalCameraId = cameraId,
                            logicalFocalLengthMm = logicalFocalLength,
                            physicalLenses = physicalLenses,
                            maxSupportedResolution = maxResolution,
                        )
                    }
                    .maxWithOrNull(
                        compareBy<BackCameraProfile> { it.physicalLenses.size }
                            .thenBy { profile ->
                                (profile.physicalLenses.maxOfOrNull { lens -> lens.focalLengthMm }
                                    ?: 0f) - (profile.physicalLenses.minOfOrNull { lens -> lens.focalLengthMm } ?: 0f)
                            }
                            .thenBy { profile -> profile.physicalLenses.count { lens -> lens.supportsManualFocus } },
                    )
            }

            backLensAssignments = backCameraProfile?.let(::assignBackLensLabels).orEmpty()
            backCameraProfile?.let { profile ->
                deviceMaxSupportedResolution = profile.maxSupportedResolution
                deviceMinFocusDistance = (backLensAssignments["1x"] ?: profile.physicalLenses.maxByOrNull { lens ->
                    lens.minFocusDistance
                })?.minFocusDistance ?: 0f
                Log.d(
                    TAG,
                    "Detected back lenses: ${
                        backLensAssignments.entries.joinToString { (label, lens) ->
                            "$label=${lens.physicalCameraId ?: lens.logicalCameraId}@${lens.focalLengthMm}mm minFocus=${lens.minFocusDistance}"
                        }
                    }",
                )
                return
            }

            // Fallback for devices where CameraManager does not expose logical physical lens info.
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

    private fun applySessionState(cameraState: CameraState, force: Boolean = false) {
        val boundCamera = camera ?: return
        val filterValue = currentValue(cameraState, ".filters")
        val signature = SessionSignature(
            flash = currentValue(cameraState, ".flash"),
            exposure = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure"),
            lens = currentValue(cameraState, ".lens"),
            hdrLog = resolvedHdrLogMode(cameraState),
            whiteBalance = currentValue(cameraState, ".white_balance"),
            filter = filterValue,
            manualFocus = currentValue(cameraState, ".manual_focus"),
            iso = currentValue(cameraState, ".iso"),
            shutter = currentValue(cameraState, ".shutter_speed"),
            resolution = desiredResolutionValue(cameraState),
            waterPressureKpa = latestWaterPressureKpa?.takeIf { filterValue == "Auto" },
            atmosphericPressureKpa = latestAtmosphericPressureKpa?.takeIf { filterValue == "Auto" },
        )
        if (!force && signature == lastAppliedSessionSignature) {
            return
        }
        applyFlash(cameraState)
        applyExposure(cameraState, boundCamera)
        applyZoom(cameraState, boundCamera)
        applyCamera2Options(cameraState, boundCamera)
        lastAppliedSessionSignature = signature
    }

    private fun refreshBoundCameraCapabilities(boundCamera: Camera) {
        activeLensProfile?.let { profile ->
            deviceMinFocusDistance = profile.minFocusDistance
            return
        }
        val cameraInfo = Camera2CameraInfo.from(boundCamera.cameraInfo)
        deviceMinFocusDistance = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
        ) ?: 0f
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
        val requestedZoom = requestedZoomRatio(lensValue)
        val zoomState = boundCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio?.toDouble() ?: 1.0
        val maxZoom = zoomState?.maxZoomRatio?.toDouble() ?: 8.0
        val clamped = requestedZoom.coerceIn(minZoom, maxZoom)
        boundCamera.cameraControl.setZoomRatio(clamped.toFloat())
    }

    private fun applyCamera2Options(cameraState: CameraState, boundCamera: Camera) {
        val builder = CaptureRequestOptions.Builder()

        // --- Manual Focus ---
        val minimumFocusDistance = deviceMinFocusDistance
        val manualFocus = effectiveManualFocusNormalized(cameraState)
        val isManualFocus = manualFocus != null && minimumFocusDistance > 0f

        Log.d(TAG, "applyCamera2Options: focus=$manualFocus, minDist=$minimumFocusDistance, manual=$isManualFocus, hdrExt=$boundHdrExtension")

        if (isManualFocus) {
            val focusDistance = focusDistanceFor(manualFocus!!)
            Log.d(TAG, "Manual focus: normalized=$manualFocus, distance=${focusDistance} diopters")

            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistance,
            )
            vendorFocusValueKey()?.let { builder.setCaptureRequestOption(it, manualFocus.toFloat()) }
            vendorFocusLensPositionKey()?.let { key ->
                builder.setCaptureRequestOption(key, (manualFocus * 4095.0).roundToInt().coerceIn(0, 4095))
            }
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                0f,
            )
            vendorFocusValueKey()?.let { builder.setCaptureRequestOption(it, 0f) }
            vendorFocusLensPositionKey()?.let { builder.setCaptureRequestOption(it, 0) }
        }
        vendorFocusMapEnabledKey()?.let { key ->
            builder.setCaptureRequestOption(
                key,
                if (focusAssistEnabled && isManualFocus) 1.toByte() else 0.toByte(),
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

        try {
            val cam2Control = Camera2CameraControl.from(boundCamera.cameraControl)
            val future = cam2Control.setCaptureRequestOptions(builder.build())
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

    private fun assignBackLensLabels(profile: BackCameraProfile): Map<String, PhysicalLensProfile> {
        val sortedLenses = profile.physicalLenses.sortedBy { lens -> lens.focalLengthMm }
        if (sortedLenses.isEmpty()) {
            return emptyMap()
        }
        val mainLens = sortedLenses.minByOrNull { lens ->
            abs(lens.focalLengthMm - profile.logicalFocalLengthMm) + if (lens.supportsManualFocus) 0f else 100f
        } ?: sortedLenses.first()
        val assignments = linkedMapOf("1x" to mainLens)
        sortedLenses.firstOrNull()
            ?.takeIf { lens -> lens != mainLens }
            ?.let { ultraWideLens -> assignments["0.6x"] = ultraWideLens }

        val teleLenses = sortedLenses.filter { lens -> lens.focalLengthMm > mainLens.focalLengthMm + 0.25f }
        when (teleLenses.size) {
            0 -> Unit
            1 -> assignments["3x"] = teleLenses.first()
            else -> {
                assignments["3x"] = teleLenses.first()
                assignments["5x"] = teleLenses.last()
            }
        }
        return assignments
    }

    private fun vendorFocusValueKey(): CaptureRequest.Key<Float>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CaptureRequest.Key("org.quic.camera.focusvalue.FocusValue", Float::class.javaObjectType)
    }

    private fun vendorFocusLensPositionKey(): CaptureRequest.Key<Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CaptureRequest.Key("samsung.android.lens.focusLensPos", Int::class.javaObjectType)
    }

    private fun vendorFocusMapEnabledKey(): CaptureRequest.Key<Byte>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CaptureRequest.Key(
            "org.codeaurora.qcamera3.sessionParameters.EnableAFFocusMap",
            Byte::class.javaObjectType,
        )
    }

    private fun runningPhysicalCameraId(result: TotalCaptureResult): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        val key = CaptureResult.Key("samsung.android.control.runningPhysicalId", ByteArray::class.java)
        return result.get(key)?.decodeAsciiId()
    }

    private fun samsungFocusLensPosition(result: TotalCaptureResult): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        val key = CaptureResult.Key("samsung.android.lens.focusLensPos", Int::class.javaObjectType)
        return result.get(key)
    }

    private fun selectedCameraIdForBinding(cameraState: CameraState, lensFacing: Int): String? {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            return frontCameraId
        }
        val physicalCameraId = selectedLensProfile(cameraState)?.physicalCameraId
        return if (allowDirectPhysicalCameraBinding) {
            physicalCameraId ?: backCameraProfile?.logicalCameraId
        } else {
            backCameraProfile?.logicalCameraId
        }
    }

    private fun selectedLensValue(cameraState: CameraState): String {
        return currentValue(cameraState, ".lens") ?: "1x"
    }

    private fun selectedLensProfile(cameraState: CameraState): PhysicalLensProfile? {
        if (selectedLensValue(cameraState) == "front") {
            return null
        }
        return when (selectedLensValue(cameraState)) {
            "0.6x" -> backLensAssignments["0.6x"] ?: backLensAssignments["1x"]
            "1x", "2x" -> backLensAssignments["1x"] ?: backLensAssignments["3x"] ?: backLensAssignments["0.6x"]
            "3x" -> backLensAssignments["3x"] ?: backLensAssignments["1x"]
            "5x" -> backLensAssignments["5x"] ?: backLensAssignments["3x"] ?: backLensAssignments["1x"]
            else -> backLensAssignments["1x"] ?: backLensAssignments.values.firstOrNull()
        }
    }

    private fun effectiveManualFocusNormalized(cameraState: CameraState): Double? {
        if (selectedLensValue(cameraState) == "front") {
            return null
        }
        val normalizedFocus = manualFocusNormalized(cameraState) ?: return null
        val selectedLens = selectedLensProfile(cameraState)
        if (selectedLens != null && !selectedLens.supportsManualFocus) {
            return null
        }
        return normalizedFocus
    }

    private fun requestedZoomRatio(lensValue: String): Double {
        if (lensValue == "front") {
            return 1.0
        }
        return when (lensValue) {
            "0.6x", "1x", "3x", "5x" -> 1.0
            "2x" -> 2.0
            else -> 1.0
        }
    }

    private fun manualFocusNormalized(cameraState: CameraState): Double? {
        return currentValue(cameraState, ".manual_focus")
            ?.takeUnless { it == "AF" }
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
    }

    private fun focusDistanceFor(normalizedFocus: Double): Float {
        return (deviceMinFocusDistance * normalizedFocus.coerceIn(0.0, 1.0).toFloat())
            .coerceIn(0f, deviceMinFocusDistance)
    }

    private fun isFocusAssistEnabled(cameraState: CameraState): Boolean {
        return currentValue(cameraState, ".focus_peaking") == "On"
    }

    private fun analyzeFocusAssistFrame(image: ImageProxy) {
        try {
            if (!focusAssistEnabled || effectiveManualFocusNormalized(latestState) == null) {
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastFocusAssistFrameAtMs < 120L) {
                return
            }
            lastFocusAssistFrameAtMs = now
            dispatchFocusAssistOverlay(createFocusAssistBitmap(image))
        } finally {
            image.close()
        }
    }

    private fun createFocusAssistBitmap(image: ImageProxy): Bitmap? {
        val lumaPlane = image.planes.firstOrNull() ?: return null
        val buffer = lumaPlane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val width = image.width
        val height = image.height
        val sampleStep = 1
        val outputWidth = (width / sampleStep).coerceAtLeast(1)
        val outputHeight = (height / sampleStep).coerceAtLeast(1)
        val gradients = IntArray(outputWidth * outputHeight)
        val pixels = IntArray(outputWidth * outputHeight)
        val rowStride = lumaPlane.rowStride
        val pixelStride = lumaPlane.pixelStride
        var gradientSum = 0L
        var gradientCount = 0
        var maxGradient = 0

        for (y in 1 until outputHeight - 1) {
            for (x in 1 until outputWidth - 1) {
                val sourceX = x * sampleStep
                val sourceY = y * sampleStep
                val left = lumaAt(bytes, rowStride, pixelStride, sourceX - sampleStep, sourceY)
                val right = lumaAt(bytes, rowStride, pixelStride, sourceX + sampleStep, sourceY)
                val up = lumaAt(bytes, rowStride, pixelStride, sourceX, sourceY - sampleStep)
                val down = lumaAt(bytes, rowStride, pixelStride, sourceX, sourceY + sampleStep)
                val edgeMagnitude = kotlin.math.abs(right - left) + kotlin.math.abs(down - up)
                gradients[y * outputWidth + x] = edgeMagnitude
                gradientSum += edgeMagnitude.toLong()
                gradientCount += 1
                maxGradient = max(maxGradient, edgeMagnitude)
            }
        }

        val adaptiveThreshold = max(
            68,
            ((gradientSum / gradientCount.coerceAtLeast(1).toDouble()) * 3.1).roundToInt(),
        )
        for (y in 1 until outputHeight - 1) {
            for (x in 1 until outputWidth - 1) {
                val index = y * outputWidth + x
                val edgeMagnitude = gradients[index]
                if (edgeMagnitude <= adaptiveThreshold) {
                    continue
                }
                val isLocalPeak =
                    edgeMagnitude >= gradients[index - 1] &&
                    edgeMagnitude >= gradients[index + 1] &&
                    edgeMagnitude >= gradients[index - outputWidth] &&
                    edgeMagnitude >= gradients[index + outputWidth]
                if (!isLocalPeak) {
                    continue
                }
                val normalized = ((edgeMagnitude - adaptiveThreshold).toFloat() /
                        (maxGradient - adaptiveThreshold).coerceAtLeast(1)).coerceIn(0f, 1f)
                val alpha = (42 + normalized * 118f).roundToInt().coerceIn(42, 160)
                val color = Color.argb(alpha, 76, 255, 122)
                pixels[index] = color
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees == 0) {
            return bitmap
        }
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun lumaAt(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        x: Int,
        y: Int,
    ): Int {
        val index = y * rowStride + x * pixelStride
        if (index < 0 || index >= bytes.size) {
            return 0
        }
        return bytes[index].toInt() and 0xFF
    }

    private fun dispatchFocusAssistOverlay(bitmap: Bitmap?) {
        val callback = focusAssistOverlayCallback ?: return
        ContextCompat.getMainExecutor(context).execute {
            callback(bitmap)
        }
    }

    private fun ByteArray.decodeAsciiId(): String {
        val endIndex = indexOf(0).let { if (it >= 0) it else size }
        return copyOfRange(0, endIndex).toString(Charsets.US_ASCII)
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
