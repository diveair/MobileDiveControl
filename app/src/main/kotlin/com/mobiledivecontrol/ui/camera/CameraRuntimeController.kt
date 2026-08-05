package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Rect
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
import android.os.Handler
import android.os.Looper
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
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
import com.mobiledivecontrol.core.FocusCurveMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
        val afModes: Set<Int>,
        val vendorFocusLensRange: IntRange?,
        val vendorFocusCalibration: List<Int>,
    ) {
        val supportsManualFocus: Boolean
            get() = minFocusDistance > 0f
    }

    private enum class ManualFocusTransport {
        Fixed,
        PublicDiopter,
        SamsungLensPosition,
        Hybrid,
    }

    private data class LensFocusCapabilityProfile(
        val lensValue: String,
        val transport: ManualFocusTransport,
        val minFocusDistance: Float,
        val vendorFocusLensRange: IntRange? = null,
    ) {
        val supportsManualFocus: Boolean
            get() = transport != ManualFocusTransport.Fixed

        val usesPublicDiopters: Boolean
            get() = transport == ManualFocusTransport.PublicDiopter || transport == ManualFocusTransport.Hybrid

        val usesVendorLensPosition: Boolean
            get() = transport == ManualFocusTransport.SamsungLensPosition || transport == ManualFocusTransport.Hybrid
    }

    private data class ManualFocusRequest(
        val normalizedFocus: Double,
        val transport: ManualFocusTransport,
        val diopters: Float? = null,
        val vendorLensPosition: Int? = null,
        val vendorFocusValue: Float? = null,
    )

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
    private var boundLensValue: String? = null
    private var boundResolution: String? = null
    private var boundHdrExtension: Boolean = false
    private var boundFocusMode: Boolean = false // true = manual focus, false = AF
    private var latestState: CameraState = CameraState()
    private var latestWaterPressureKpa: Double? = null
    private var latestAtmosphericPressureKpa: Double? = null
    private var frontCameraId: String? = null
    private var frontCameraMinFocusDistance: Float = 0f
    private var backCameraProfile: BackCameraProfile? = null
    private var backLensAssignments: Map<String, PhysicalLensProfile> = emptyMap()
    private var lensFocusCapabilities: Map<String, LensFocusCapabilityProfile> = emptyMap()
    private var activeLensProfile: PhysicalLensProfile? = null
    private val failedDirectPhysicalCameraIds = mutableSetOf<String>()
    private val unsupportedPhysicalRequestTargets = mutableSetOf<String>()

    // Device capabilities detected once at attach time via CameraManager
    private var deviceMinFocusDistance: Float = 10f // Safe default — most phones have ~10 diopters
    private var deviceHasVendorHdr: Boolean = false
    private var deviceMaxSupportedResolution: Size? = null
    private var capabilitiesDetected: Boolean = false
    private var focusAssistEnabled: Boolean = false
    private var lastFocusResultLogAtMs: Long = 0L
    private var lastAppliedSessionSignature: SessionSignature? = null
    private val focusAssistExecutor = Executors.newSingleThreadExecutor()
    // Callback to report detected lenses back to the ViewModel/state
    private var onDetectedLenses: ((List<String>) -> Unit)? = null
    // GPU-accelerated focus peaking via OpenGL shader in the CameraX preview pipeline.
    // Replaces the old CPU bitmap overlay approach which caused jitter and drift.
    private var focusPeakingProcessor: FocusPeakingSurfaceProcessor? = null
    private val cameraRequestHandler = Handler(Looper.getMainLooper())

    // ── Native Camera2 focus control ──────────────────────────────────────
    // When manual focus is active we take FULL ownership of the Camera2
    // CameraCaptureSession repeating request. CameraX is NOT called for any
    // CameraControl operations while in direct mode, so it never fights back.
    // When auto focus is active, CameraX gets full control back.
    @Volatile private var cam2Session: CameraCaptureSession? = null
    @Volatile private var cam2Surfaces: List<android.view.Surface> = emptyList()
    @Volatile private var nativeFocusActive: Boolean = false

    private val sessionCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            super.onCaptureCompleted(session, request, result)
            // Capture session + surfaces on first callback or session change
            if (cam2Session !== session) {
                cam2Session = session
                try {
                    val m = CaptureRequest::class.java.getDeclaredMethod("getTargets")
                    m.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    cam2Surfaces = (m.invoke(request) as? Collection<android.view.Surface>)?.toList() ?: emptyList()
                    Log.d(TAG, "Native: captured session + ${cam2Surfaces.size} surfaces")
                } catch (e: Exception) {
                    Log.w(TAG, "Native: cannot capture surfaces: ${e.message}")
                }
                // Session is now available — re-apply the current state so that
                // manual focus (which requires the native session) takes effect.
                // This fixes the startup race where applySessionState runs before
                // the session exists.
                lastAppliedSessionSignature = null
                applySessionState(latestState, force = true)
            }
            // Periodic diagnostic logging
            val now = SystemClock.elapsedRealtime()
            if (now - lastFocusResultLogAtMs < 500L) return
            lastFocusResultLogAtMs = now
            val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val af = result.get(CaptureResult.CONTROL_AF_MODE)
            val lp = samsungFocusLensPosition(result)
            val physicalId = runningPhysicalCameraId(result)
            Log.d(TAG, "CaptureResult fd=$fd af=$af lp=$lp physical=$physicalId native=$nativeFocusActive")
        }
    }

    fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        initialState: CameraState,
        onReady: (Boolean) -> Unit,
        onDetectedLenses: ((List<String>) -> Unit)? = null,
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        this.onDetectedLenses = onDetectedLenses
        latestState = initialState
        focusAssistEnabled = isFocusAssistEnabled(initialState)

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
        boundLensValue = null
        boundResolution = null
        boundHdrExtension = false
        boundFocusMode = false
        cam2Session = null
        cam2Surfaces = emptyList()
        nativeFocusActive = false
        focusAssistEnabled = false
        lastAppliedSessionSignature = null
        focusPeakingProcessor?.release()
        focusPeakingProcessor = null
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
        // Toggle GPU shader peaking — no camera rebinding needed.
        // Peaking works in both AF and manual focus modes.
        focusPeakingProcessor?.peakingEnabled = focusAssistEnabled
        if (cameraProvider == null || previewView == null || lifecycleOwner == null) {
            Log.d(TAG, "applyState: early return (provider/preview/lifecycle null)")
            return
        }

        val desiredLensFacing = desiredLensFacing(cameraState)
        val desiredResolution = desiredResolutionValue(cameraState)
        val desiredLens = selectedLensValue(cameraState)
        // Rebind when lens facing, physical lens, or resolution changes.
        val needsRebind = desiredLensFacing != boundLensFacing ||
                desiredResolution != boundResolution ||
                desiredLens != boundLensValue
        if (needsRebind) {
            Log.d(TAG, "applyState: rebinding camera")
            bindCamera(force = true)
        } else {
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
        val focusCapability = selectedFocusCapability(latestState)
        val manualFocusRequest = manualFocusRequestFor(latestState)
        if (desiredLensFacing == CameraSelector.LENS_FACING_BACK) {
            deviceMinFocusDistance = focusCapability?.minFocusDistance ?: activeLensProfile?.minFocusDistance ?: 0f
        } else {
            deviceMinFocusDistance = focusCapability?.minFocusDistance ?: frontCameraMinFocusDistance
        }

        if (!force && desiredLensFacing == boundLensFacing &&
            desiredResolution == boundResolution &&
            selectedLensValue(latestState) == boundLensValue) {
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
        val isManualFocus = manualFocusRequest != null
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
            "bindCamera: lens=${selectedLensValue(latestState)} cameraId=$selectedCameraId physical=$physicalCameraId minFocus=$deviceMinFocusDistance manual=$isManualFocus transport=${manualFocusRequest?.transport}",
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
            // This is the only reliable way to disable Samsung's AF pipeline -
            // runtime Camera2 interop is accepted but Samsung HAL ignores AF_MODE changes.
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            Camera2Interop.Extender(imageCaptureBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            manualFocusRequest?.diopters?.let { focusDistance ->
                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                Camera2Interop.Extender(imageCaptureBuilder)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
            manualFocusRequest?.vendorLensPosition?.let { lensPosition ->
                vendorFocusLensPositionKey()?.let { key ->
                    Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(key, lensPosition)
                    Camera2Interop.Extender(imageCaptureBuilder).setCaptureRequestOption(key, lensPosition)
                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(key, lensPosition)
                }
            }
            Log.d(
                TAG,
                "bindCamera: AF_MODE_OFF + diopters=${manualFocusRequest?.diopters} vendorLensPos=${manualFocusRequest?.vendorLensPosition} set at bind time via Extender",
            )
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
        val analysis = analysisBuilder.build()

        // GPU focus peaking effect — sits in the preview pipeline as a CameraEffect.
        // The shader runs on every preview frame; when peaking is off it's a trivial
        // pass-through (one texture fetch per pixel, negligible cost).
        val processor = FocusPeakingSurfaceProcessor(ContextCompat.getMainExecutor(context))
        processor.peakingEnabled = focusAssistEnabled
        focusPeakingProcessor?.release()
        focusPeakingProcessor = processor

        val effect = object : CameraEffect(
            PREVIEW,
            focusAssistExecutor,
            processor,
            { error -> Log.e(TAG, "Focus peaking effect error", error) },
        ) {}

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysis)
            .addEffect(effect)
            .build()

        // Reset native session state before rebinding. A stale nativeFocusActive
        // from a previous lens would trigger cancelFocusAndMetering() on the new
        // camera, causing CameraX to switch physical cameras.
        nativeFocusActive = false
        cam2Session = null
        cam2Surfaces = emptyList()
        lastAppliedSessionSignature = null
        provider.unbindAll()
        camera = try {
            provider.bindToLifecycle(owner, selector, useCaseGroup)
        } catch (error: IllegalArgumentException) {
            val triedDirectPhysicalCamera = desiredLensFacing == CameraSelector.LENS_FACING_BACK &&
                selectedCameraId != null &&
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
            selectedCameraId?.let { failedDirectPhysicalCameraIds += it }
            bindCamera(force = true)
            return
        }
        imageCapture = capture
        imageAnalysis = analysis
        camera?.let { refreshBoundCameraCapabilities(it) }
        boundLensFacing = desiredLensFacing
        boundLensValue = selectedLensValue(latestState)
        boundResolution = desiredResolution

        // Detect device capabilities from the bound camera

        // Apply zoom once at bind time. setZoomRatio on a logical multi-camera
        // can switch the active physical camera, so we must NOT call it from
        // applySessionState (which runs on every settings change).
        camera?.let { applyZoom(latestState, it) }
        applySessionState(latestState, force = true)
    }


    private fun detectDeviceCapabilitiesViaCameraManager() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            frontCameraId = cameraManager.cameraIdList.firstOrNull { cameraId ->
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            frontCameraId?.let { fId ->
                frontCameraMinFocusDistance = cameraManager.getCameraCharacteristics(fId)
                    .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                Log.d(TAG, "Front camera $fId minFocusDistance=$frontCameraMinFocusDistance")
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
                                afModes = physicalChars.get(
                                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                                )?.toSet().orEmpty(),
                                vendorFocusLensRange = vendorFocusLensRange(physicalChars),
                                vendorFocusCalibration = vendorFocusCalibration(physicalChars),
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
            lensFocusCapabilities = buildLensFocusCapabilities(backLensAssignments)
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
                // ── Detailed diagnostics for each physical lens ──
                for ((label, lens) in backLensAssignments) {
                    try {
                        val camId = lens.physicalCameraId ?: lens.logicalCameraId
                        val chars = cameraManager.getCameraCharacteristics(camId)
                        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        val minFd = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                        Log.d(
                            TAG,
                            "DIAG [$label] camId=$camId focal=${focalLengths?.toList()} minFocusDist=$minFd afModes=${afModes?.toList()} hwLevel=$hwLevel vendorRange=${lens.vendorFocusLensRange} transport=${lensFocusCapabilities[label]?.transport}",
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "DIAG [$label] failed: ${e.message}")
                    }
                }
                // Also check the logical camera's AF/focus capabilities
                try {
                    val logicalChars = cameraManager.getCameraCharacteristics(profile.logicalCameraId)
                    val afModes = logicalChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    val minFd = logicalChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    Log.d(TAG, "DIAG [logical] camId=${profile.logicalCameraId} minFocusDist=$minFd afModes=${afModes?.toList()}")
                } catch (_: Exception) {}
                // Front camera diagnostics
                frontCameraId?.let { fId ->
                    try {
                        val fChars = cameraManager.getCameraCharacteristics(fId)
                        val afModes = fChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        val minFd = fChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        val focalLengths = fChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        Log.d(TAG, "DIAG [front] camId=$fId focal=${ focalLengths?.toList() } minFocusDist=$minFd afModes=${afModes?.toList()}")
                    } catch (_: Exception) {}
                }
                // ── Report detected lenses back to the UI state ──
                reportDetectedLenses()
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
            // Report even for fallback devices
            reportDetectedLenses()
        } catch (_: Exception) {
            // Keep safe defaults
        }
    }

    /**
     * Build the dynamic lens list from detected hardware and report it via callback.
     * This list includes "Auto" as the first option, then all detected physical lenses,
     * plus "2x" (digital crop on main) if a main lens exists, and "front" if detected.
     */
    private fun reportDetectedLenses() {
        val lenses = mutableListOf("Auto")
        // Add in optical order: 0.6x, 1x, 2x (digital), 3x, 5x
        if (backLensAssignments.containsKey("0.6x")) lenses.add("0.6x")
        if (backLensAssignments.containsKey("1x")) {
            lenses.add("1x")
            lenses.add("2x") // Digital crop on main lens
        }
        if (backLensAssignments.containsKey("3x")) lenses.add("3x")
        if (backLensAssignments.containsKey("5x")) lenses.add("5x")
        if (frontCameraId != null) lenses.add("front")
        Log.d(TAG, "Detected lenses for UI: $lenses")
        onDetectedLenses?.invoke(lenses)
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

        val manualFocusRequest = manualFocusRequestFor(cameraState)
        val isManualFocus = manualFocusRequest != null

        if (isManualFocus) {
            // ── NATIVE MODE ──────────────────────────────────────────────
            // Take full ownership of the Camera2 session. Do NOT call any
            // CameraX CameraControl methods — that would trigger CameraX to
            // rebuild its repeating request, fighting our native one.
            nativeFocusActive = true
            submitNativeRepeatingRequest(cameraState, boundCamera)
        } else {
            // ── CAMERAX MODE ─────────────────────────────────────────────
            // Give CameraX full control back.
            if (nativeFocusActive) {
                // Our native setRepeatingRequest replaced CameraX's managed one.
                // Force CameraX to re-establish by cancelling focus metering,
                // which triggers an internal request rebuild.
                nativeFocusActive = false
                boundCamera.cameraControl.cancelFocusAndMetering()
                Log.d(TAG, "Returning to CameraX control — forced re-establish")
            }
            applyFlash(cameraState)
            applyExposure(cameraState, boundCamera)
            applyCamera2Options(cameraState, boundCamera)
        }
        lastAppliedSessionSignature = signature
    }

    /**
     * Submit a repeating request directly on the Camera2 CameraCaptureSession.
     * Includes ALL camera settings (focus, AE, AWB, ISO, shutter, HDR).
     * CameraX is completely bypassed — no CameraControl calls while this is active.
     */
    private fun submitNativeRepeatingRequest(cameraState: CameraState, boundCamera: Camera) {
        val session = cam2Session
        val surfaces = cam2Surfaces
        if (session == null || surfaces.isEmpty()) {
            Log.w(TAG, "Native focus: no session yet, applying via Camera2CameraControl")
            // Session not ready — use CameraX's Camera2CameraControl to set
            // AF_MODE_OFF + focus distance. This goes through CameraX's own
            // pipeline so it won't be overridden.
            try {
                val manualFocusRequest = manualFocusRequestFor(cameraState) ?: return
                val builder = CaptureRequestOptions.Builder()
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyNativeZoom(builder, cameraState, boundCamera)
                manualFocusRequest.diopters?.let { focusDiopters ->
                    builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
                }
                manualFocusRequest.vendorLensPosition?.let { lensPosition ->
                    vendorFocusLensPositionKey()?.let { key ->
                        builder.setCaptureRequestOption(key, lensPosition)
                    }
                }
                manualFocusRequest.vendorFocusValue?.let { focusValue ->
                    vendorFocusValueKey()?.let { key ->
                        builder.setCaptureRequestOption(key, focusValue)
                    }
                }
                manualFocusRequest.vendorLensPosition?.let {
                    vendorFocusMapEnabledKey()?.let { key ->
                        builder.setCaptureRequestOption(key, 1.toByte())
                    }
                }
                val cam2Control = Camera2CameraControl.from(boundCamera.cameraControl)
                cam2Control.setCaptureRequestOptions(builder.build())
                Log.d(
                    TAG,
                    "Camera2CameraControl fallback: zoom=${nativeZoomRatio(cameraState, boundCamera)} diopters=${manualFocusRequest.diopters} vendorLensPos=${manualFocusRequest.vendorLensPosition} vendorFocus=${manualFocusRequest.vendorFocusValue}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera2CameraControl fallback failed", e)
            }
            return
        }
        try {
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)

            // ── Focus ──
            val manualFocusRequest = manualFocusRequestFor(cameraState) ?: return
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            applyNativeZoom(builder, cameraState, boundCamera)
            applyNativeFocusRequest(builder, cameraState, manualFocusRequest, session.device.id)

            // ── HDR / LOG ──
            when (resolvedHdrLogMode(cameraState)) {
                "HDR" -> {
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                }
                "LOG" -> {
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    builder.set(CaptureRequest.TONEMAP_CURVE, flatLogCurve())
                }
                else -> builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }

            // ── White Balance ──
            val filterProfile = underwaterFilterProfile(
                value = currentValue(cameraState, ".filters"),
                depthMeters = currentDepthMeters(),
            )
            if (filterProfile != null) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, filterProfile)
            } else {
                val wbValue = currentValue(cameraState, ".white_balance")
                val kelvin = wbValue?.removeSuffix("K")?.toIntOrNull()
                if (wbValue != null && wbValue != "Auto" && kelvin != null) {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                } else {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                }
            }

            // ── AE / ISO / Shutter ──
            var aeOff = false
            val isoValue = currentValue(cameraState, ".iso")?.toIntOrNull()
            if (isoValue != null) {
                val isoRange = Camera2CameraInfo.from(boundCamera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (isoRange != null) {
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue.coerceIn(isoRange.lower, isoRange.upper))
                    aeOff = true
                }
            }
            val shutterNs = currentValue(cameraState, ".shutter_speed")?.let { parseShutterNs(it) }
            if (shutterNs != null) {
                val range = Camera2CameraInfo.from(boundCamera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (range != null) {
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs.coerceIn(range.lower, range.upper))
                    aeOff = true
                }
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                if (aeOff) CameraMetadata.CONTROL_AE_MODE_OFF else CameraMetadata.CONTROL_AE_MODE_ON)

            // ── Exposure Compensation (pass through when AE is ON) ──
            if (!aeOff) {
                val ev = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
                    ?.replace("+", "")?.toDoubleOrNull()
                if (ev != null) {
                    val exposureState = boundCamera.cameraInfo.exposureState
                    val step = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 0.1f
                    val idx = (ev / step).roundToInt().coerceIn(
                        exposureState.exposureCompensationRange.lower,
                        exposureState.exposureCompensationRange.upper,
                    )
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
                }
            }

            // ── Flash ──
            val flashValue = currentValue(cameraState, ".flash")
            if (flashValue == "On" || flashValue == "Torch") {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }

            session.setRepeatingRequest(builder.build(), sessionCaptureCallback, cameraRequestHandler)
            val capability = selectedFocusCapability(cameraState)
            Log.d(
                TAG,
                "Native focus applied: lens=${selectedLensValue(cameraState)} sessionCameraId=${session.device.id} zoom=${nativeZoomRatio(cameraState, boundCamera)} diopters=${manualFocusRequest.diopters} minFocusDist=${capability?.minFocusDistance} transport=${capability?.transport} vendorLensPos=${manualFocusRequest.vendorLensPosition} vendorFocus=${manualFocusRequest.vendorFocusValue} (norm=${manualFocusRequest.normalizedFocus})",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Native focus FAILED: ${e.message}", e)
        }
    }

    private fun refreshBoundCameraCapabilities(boundCamera: Camera) {
        selectedFocusCapability(latestState)?.let { capability ->
            deviceMinFocusDistance = capability.minFocusDistance
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
        // This method is only called in CameraX mode (auto focus).
        // When nativeFocusActive is true, submitNativeRepeatingRequest handles everything.
        val builder = CaptureRequestOptions.Builder()

        // Use AF_MODE_OFF for fixed-focus lenses (e.g. 0.6x ultrawide) that only
        // support mode 0. Setting CONTINUOUS_PICTURE on these can cause CameraX to
        // internally switch physical cameras.
        val isFixedFocus = selectedFocusCapability(cameraState)?.supportsManualFocus == false
        if (isFixedFocus) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)

        // --- Effect mode ---
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // --- HDR / LOG / Off ---
        if (!boundHdrExtension) {
            when (resolvedHdrLogMode(cameraState)) {
                "HDR" -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                }
                "LOG" -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    builder.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, flatLogCurve())
                }
                else -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                }
            }
        }

        // --- White Balance ---
        val filterProfile = underwaterFilterProfile(
            value = currentValue(cameraState, ".filters"),
            depthMeters = currentDepthMeters(),
        )
        if (filterProfile == null) {
            val wbValue = currentValue(cameraState, ".white_balance")
            if (wbValue != null && wbValue != "Auto") {
                val kelvin = wbValue.removeSuffix("K").toIntOrNull()
                if (kelvin != null) {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                } else {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                }
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, filterProfile)
        }

        // --- ISO ---
        val isoValue = currentValue(cameraState, ".iso")?.toIntOrNull()
        if (isoValue != null) {
            try {
                val isoRange = Camera2CameraInfo.from(boundCamera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (isoRange != null) {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoValue.coerceIn(isoRange.lower, isoRange.upper))
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
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
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs.coerceIn(exposureRange.lower, exposureRange.upper))
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                }
            } catch (_: Exception) { }
        }

        try {
            val cam2Control = Camera2CameraControl.from(boundCamera.cameraControl)
            cam2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 options FAILED", e)
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
        val assignments = linkedMapOf<String, PhysicalLensProfile>()
        assignments["1x"] = mainLens
        // 2x is a digital crop on the main lens sensor — same physical camera
        assignments["2x"] = mainLens

        sortedLenses
            .filter { lens -> lens.focalLengthMm < mainLens.focalLengthMm - 0.1f }
            .minByOrNull { lens -> lens.focalLengthMm }
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

    private fun buildLensFocusCapabilities(
        assignments: Map<String, PhysicalLensProfile>,
    ): Map<String, LensFocusCapabilityProfile> {
        return assignments.mapValues { (lensValue, profile) ->
            LensFocusCapabilityProfile(
                lensValue = lensValue,
                transport = resolveManualFocusTransport(lensValue, profile),
                minFocusDistance = profile.minFocusDistance,
                vendorFocusLensRange = profile.vendorFocusLensRange,
            )
        }
    }

    private fun resolveManualFocusTransport(
        lensValue: String,
        profile: PhysicalLensProfile,
    ): ManualFocusTransport {
        val hasVendorRange = profile.vendorFocusLensRange?.let { it.last > it.first } == true
        val hasAutofocusMotor = profile.afModes.any { mode ->
            mode == CameraMetadata.CONTROL_AF_MODE_AUTO ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO ||
                mode == CameraMetadata.CONTROL_AF_MODE_MACRO
        }
        return when {
            // Main lens and its 2x digital crop: always use standard diopter API
            lensValue == "1x" && profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            lensValue == "2x" && isMainLensProfile(profile) && profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            // Telephoto lenses: Samsung HAL requires BOTH diopter + vendor lens position
            // to reliably control the full focus range. PublicDiopter alone is ignored/clamped
            // by the HAL, causing blurry images at close focus distances.
            lensValue in setOf("3x", "5x") && profile.minFocusDistance > 0f && hasVendorRange -> ManualFocusTransport.Hybrid
            lensValue in setOf("3x", "5x") && profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            lensValue in setOf("3x", "5x") && hasVendorRange -> ManualFocusTransport.SamsungLensPosition
            // Other lenses: use diopter when available
            profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            hasVendorRange && hasAutofocusMotor -> ManualFocusTransport.SamsungLensPosition
            else -> ManualFocusTransport.Fixed
        }
    }

    private fun isMainLensProfile(profile: PhysicalLensProfile): Boolean {
        val mainLens = backLensAssignments["1x"] ?: return false
        return (profile.physicalCameraId ?: profile.logicalCameraId) ==
            (mainLens.physicalCameraId ?: mainLens.logicalCameraId)
    }

    private fun selectedFocusCapability(cameraState: CameraState): LensFocusCapabilityProfile? {
        val lensValue = selectedLensValue(cameraState)
        if (lensValue == "front") {
            return LensFocusCapabilityProfile(
                lensValue = lensValue,
                transport = if (frontCameraMinFocusDistance > 0f) {
                    ManualFocusTransport.PublicDiopter
                } else {
                    ManualFocusTransport.Fixed
                },
                minFocusDistance = frontCameraMinFocusDistance,
            )
        }
        if (lensValue == "Auto") {
            // Auto mode uses the logical camera — focus through logical camera's capabilities
            if (backCameraProfile == null) return null
            val logicalMinFocus = backLensAssignments["1x"]?.minFocusDistance ?: deviceMinFocusDistance
            return LensFocusCapabilityProfile(
                lensValue = "Auto",
                transport = if (logicalMinFocus > 0f) ManualFocusTransport.PublicDiopter else ManualFocusTransport.Fixed,
                minFocusDistance = logicalMinFocus,
            )
        }
        return lensFocusCapabilities[lensValue]
            ?: selectedLensProfile(cameraState)?.let { profile ->
                LensFocusCapabilityProfile(
                    lensValue = lensValue,
                    transport = resolveManualFocusTransport(lensValue, profile),
                    minFocusDistance = profile.minFocusDistance,
                    vendorFocusLensRange = profile.vendorFocusLensRange,
                )
            }
    }

    private fun manualFocusRequestFor(cameraState: CameraState): ManualFocusRequest? {
        val normalizedFocus = manualFocusNormalized(cameraState) ?: return null
        val capability = selectedFocusCapability(cameraState) ?: return null
        if (!capability.supportsManualFocus) {
            return null
        }
        val diopters = if (capability.usesPublicDiopters && capability.minFocusDistance > 0f) {
            focusDistanceFor(normalizedFocus, capability.minFocusDistance)
        } else {
            null
        }
        val vendorLensPosition = if (capability.usesVendorLensPosition) {
            capability.vendorFocusLensRange?.let { range ->
                vendorLensPositionFor(normalizedFocus, range)
            }
        } else {
            null
        }
        val vendorFocusValue = if (capability.usesVendorLensPosition) {
            vendorFocusValueFor(normalizedFocus)
        } else {
            null
        }
        if (diopters == null && vendorLensPosition == null) {
            return null
        }
        return ManualFocusRequest(
            normalizedFocus = normalizedFocus,
            transport = capability.transport,
            diopters = diopters,
            vendorLensPosition = vendorLensPosition,
            vendorFocusValue = vendorFocusValue,
        )
    }

    private fun vendorFocusLensInfoKey(): CameraCharacteristics.Key<IntArray>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CameraCharacteristics.Key("samsung.android.lens.info.focusLensInfo", IntArray::class.java)
    }

    private fun vendorFocusCalibrationKey(): CameraCharacteristics.Key<IntArray>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CameraCharacteristics.Key("samsung.android.lens.info.focusCalibration", IntArray::class.java)
    }

    private fun vendorFocusLensRange(characteristics: CameraCharacteristics): IntRange? {
        val info = vendorFocusLensInfoKey()?.let(characteristics::get) ?: return null
        if (info.size < 2) {
            return null
        }
        val start = info[0]
        val end = info[1]
        return if (end > start) start..end else null
    }

    private fun vendorFocusCalibration(characteristics: CameraCharacteristics): List<Int> {
        return vendorFocusCalibrationKey()?.let(characteristics::get)?.toList().orEmpty()
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
        val lensValue = selectedLensValue(cameraState)
        // "Auto" mode uses the logical camera — let Samsung's HAL manage lens switching
        if (lensValue == "Auto") {
            return backCameraProfile?.logicalCameraId
        }
        val physicalCameraId = selectedLensProfile(cameraState)?.physicalCameraId
        return if (physicalCameraId != null && physicalCameraId !in failedDirectPhysicalCameraIds) {
            physicalCameraId
        } else {
            backCameraProfile?.logicalCameraId
        }
    }

    private fun selectedLensValue(cameraState: CameraState): String {
        return currentValue(cameraState, ".lens") ?: "Auto"
    }

    private fun selectedLensProfile(cameraState: CameraState): PhysicalLensProfile? {
        val lensValue = selectedLensValue(cameraState)
        if (lensValue == "front") {
            return null
        }
        // "Auto" mode: use no specific physical lens — let the logical camera manage it
        if (lensValue == "Auto") {
            return null
        }
        return when (lensValue) {
            "0.6x" -> backLensAssignments["0.6x"] ?: backLensAssignments["1x"]
            "1x" -> backLensAssignments["1x"] ?: backLensAssignments["2x"] ?: backLensAssignments["3x"] ?: backLensAssignments["0.6x"]
            "2x" -> backLensAssignments["2x"] ?: backLensAssignments["1x"] ?: backLensAssignments["3x"]
            "3x" -> backLensAssignments["3x"] ?: backLensAssignments["2x"] ?: backLensAssignments["1x"]
            "5x" -> backLensAssignments["5x"] ?: backLensAssignments["3x"] ?: backLensAssignments["2x"] ?: backLensAssignments["1x"]
            else -> backLensAssignments["1x"] ?: backLensAssignments.values.firstOrNull()
        }
    }

    private fun effectiveManualFocusNormalized(cameraState: CameraState): Double? {
        return manualFocusRequestFor(cameraState)?.normalizedFocus
    }

    private fun requestedZoomRatio(lensValue: String): Double {
        if (lensValue == "front") {
            return 1.0
        }
        // When binding to the logical camera with setPhysicalCameraId, Samsung's HAL
        // still needs CONTROL_ZOOM_RATIO to match the physical sensor so that
        // LENS_FOCUS_DISTANCE and AF commands are routed to the correct sensor.
        // Without this, focus commands go to the 1x wide sensor even when viewing 3x.
        return when (lensValue) {
            "0.6x" -> 1.0  // ultrawide: fixed focus, CameraX manages routing via physicalCameraId
            "1x" -> 1.0
            "2x" -> 2.0    // digital crop on main sensor
            "3x" -> 3.0    // telephoto: must match so HAL routes focus to telephoto sensor
            "5x" -> 5.0    // periscope: must match so HAL routes focus to periscope sensor
            "Auto" -> 1.0  // Auto mode: HAL manages lens switching, start at 1x
            else -> 1.0
        }
    }

    private fun nativeZoomRatio(cameraState: CameraState, boundCamera: Camera): Float {
        val requestedZoom = requestedZoomRatio(selectedLensValue(cameraState))
        val zoomState = boundCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio?.toDouble() ?: 1.0
        val maxZoom = zoomState?.maxZoomRatio?.toDouble() ?: 8.0
        return requestedZoom.coerceIn(minZoom, maxZoom).toFloat()
    }

    private fun applyNativeZoom(
        builder: CaptureRequestOptions.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        val zoomRatio = nativeZoomRatio(cameraState, boundCamera)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            return
        }
        cameraActiveArrayRect(boundCamera)?.let { activeArray ->
            builder.setCaptureRequestOption(
                CaptureRequest.SCALER_CROP_REGION,
                cropRegionForZoom(activeArray, zoomRatio),
            )
        }
    }

    private fun applyNativeZoom(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        val zoomRatio = nativeZoomRatio(cameraState, boundCamera)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            return
        }
        cameraActiveArrayRect(boundCamera)?.let { activeArray ->
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                cropRegionForZoom(activeArray, zoomRatio),
            )
        }
    }

    private fun manualFocusNormalized(cameraState: CameraState): Double? {
        return currentValue(cameraState, ".manual_focus")
            ?.takeUnless { it == "AF" }
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
    }

    /**
     * Map normalized focus (0=close/macro, 1=far/infinity) to diopter value.
     * Applies the user-selected focus curve (Linear, SquareRoot, or Logarithmic)
     * for perceptually smooth control.
     */
    private fun focusDistanceFor(
        normalizedFocus: Double,
        minFocusDistance: Float = deviceMinFocusDistance,
        curveMode: FocusCurveMode = currentFocusCurveMode(),
    ): Float {
        val clamped = normalizedFocus.coerceIn(0.0, 1.0)
        // 0 = close (macro, max diopters), 1 = far (infinity, 0 diopters)
        val t = 1.0 - clamped
        val curved = when (curveMode) {
            FocusCurveMode.Linear -> t
            FocusCurveMode.SquareRoot -> sqrt(t)
            FocusCurveMode.Logarithmic -> {
                // ln(1 + t*e) / ln(1+e) maps [0,1] -> [0,1] with more range near macro
                val e = Math.E
                ln(1.0 + t * e) / ln(1.0 + e)
            }
        }
        return (minFocusDistance * curved.toFloat())
            .coerceIn(0f, minFocusDistance)
    }

    /**
     * Read the current focus curve mode from the latest camera state.
     */
    private fun currentFocusCurveMode(): FocusCurveMode {
        val focusCurveSettingId = when (latestState.activeMode) {
            com.mobiledivecontrol.core.CameraModeId.Photo -> "photo.focus_curve"
            com.mobiledivecontrol.core.CameraModeId.Pro -> "pro.focus_curve"
            com.mobiledivecontrol.core.CameraModeId.ExpertRaw -> "expert.focus_curve"
            com.mobiledivecontrol.core.CameraModeId.ProVideo -> "pro_video.focus_curve"
            else -> return FocusCurveMode.SquareRoot
        }
        val curveValue = latestState.settingValues[focusCurveSettingId] ?: "SquareRoot"
        return when (curveValue) {
            "Linear" -> FocusCurveMode.Linear
            "SquareRoot" -> FocusCurveMode.SquareRoot
            "Logarithmic" -> FocusCurveMode.Logarithmic
            else -> FocusCurveMode.SquareRoot
        }
    }

    private fun vendorLensPositionFor(normalizedFocus: Double, lensRange: IntRange): Int {
        val clamped = normalizedFocus.coerceIn(0.0, 1.0)
        val span = lensRange.last - lensRange.first
        val nearPosition = lensRange.last.toDouble()
        return (nearPosition - (span * clamped))
            .roundToInt()
            .coerceIn(lensRange.first, lensRange.last)
    }

    private fun vendorFocusValueFor(normalizedFocus: Double): Float {
        val clamped = normalizedFocus.coerceIn(0.0, 1.0)
        return (1.0 - clamped).toFloat()
    }

    private fun applyNativeFocusRequest(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        request: ManualFocusRequest,
        sessionDeviceId: String?,
    ) {
        val physicalCameraId = physicalFocusCameraId(cameraState)
        var usePhysicalKeys = canUsePhysicalRequestKeys(sessionDeviceId, physicalCameraId)

        request.diopters?.let { focusDiopters ->
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
            if (usePhysicalKeys) {
                usePhysicalKeys = trySetPhysicalCameraKey(
                    builder = builder,
                    key = CaptureRequest.LENS_FOCUS_DISTANCE,
                    value = focusDiopters,
                    sessionDeviceId = sessionDeviceId,
                    physicalCameraId = physicalCameraId!!,
                )
            }
        }
        request.vendorLensPosition?.let { lensPosition ->
            vendorFocusLensPositionKey()?.let { key ->
                builder.set(key, lensPosition)
                if (usePhysicalKeys) {
                    usePhysicalKeys = trySetPhysicalCameraKey(
                        builder = builder,
                        key = key,
                        value = lensPosition,
                        sessionDeviceId = sessionDeviceId,
                        physicalCameraId = physicalCameraId!!,
                    )
                }
            }
        }
        request.vendorFocusValue?.let { focusValue ->
            vendorFocusValueKey()?.let { key ->
                builder.set(key, focusValue)
                if (usePhysicalKeys) {
                    usePhysicalKeys = trySetPhysicalCameraKey(
                        builder = builder,
                        key = key,
                        value = focusValue,
                        sessionDeviceId = sessionDeviceId,
                        physicalCameraId = physicalCameraId!!,
                    )
                }
            }
        }
        request.vendorLensPosition?.let {
            vendorFocusMapEnabledKey()?.let { key ->
                builder.set(key, 1.toByte())
                if (usePhysicalKeys) {
                    usePhysicalKeys = trySetPhysicalCameraKey(
                        builder = builder,
                        key = key,
                        value = 1.toByte(),
                        sessionDeviceId = sessionDeviceId,
                        physicalCameraId = physicalCameraId!!,
                    )
                }
            }
        }
    }

    private fun canUsePhysicalRequestKeys(
        sessionDeviceId: String?,
        physicalCameraId: String?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || sessionDeviceId == null || physicalCameraId == null) {
            return false
        }
        if (sessionDeviceId == physicalCameraId) {
            return false
        }
        return physicalRequestTargetKey(sessionDeviceId, physicalCameraId) !in unsupportedPhysicalRequestTargets
    }

    private fun physicalRequestTargetKey(sessionDeviceId: String, physicalCameraId: String): String {
        return "$sessionDeviceId->$physicalCameraId"
    }

    private fun <T> trySetPhysicalCameraKey(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T,
        sessionDeviceId: String?,
        physicalCameraId: String,
    ): Boolean {
        val deviceId = sessionDeviceId ?: return false
        val targetKey = physicalRequestTargetKey(deviceId, physicalCameraId)
        if (targetKey in unsupportedPhysicalRequestTargets) {
            return false
        }
        return try {
            builder.setPhysicalCameraKey(key, value, physicalCameraId)
            true
        } catch (error: IllegalArgumentException) {
            unsupportedPhysicalRequestTargets += targetKey
            Log.w(
                TAG,
                "Physical request keys disabled for sessionCameraId=$deviceId physicalCameraId=$physicalCameraId: ${error.message}",
            )
            false
        }
    }

    private fun physicalFocusCameraId(cameraState: CameraState): String? {
        if (selectedLensValue(cameraState) == "front") {
            return null
        }
        val profile = selectedLensProfile(cameraState) ?: return null
        if (isMainLensProfile(profile)) {
            return null
        }
        return profile.physicalCameraId
    }

    private fun cameraActiveArrayRect(boundCamera: Camera): Rect? {
        return Camera2CameraInfo.from(boundCamera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    }

    private fun cropRegionForZoom(activeArray: Rect, zoomRatio: Float): Rect {
        if (zoomRatio <= 1f) {
            return Rect(activeArray)
        }
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()
        val cropWidth = (activeArray.width() / zoomRatio).roundToInt().coerceAtLeast(1)
        val cropHeight = (activeArray.height() / zoomRatio).roundToInt().coerceAtLeast(1)
        val left = (centerX - cropWidth / 2).coerceAtLeast(activeArray.left)
        val top = (centerY - cropHeight / 2).coerceAtLeast(activeArray.top)
        val right = (left + cropWidth).coerceAtMost(activeArray.right)
        val bottom = (top + cropHeight).coerceAtMost(activeArray.bottom)
        return Rect(left, top, right, bottom)
    }

    private fun isFocusAssistEnabled(cameraState: CameraState): Boolean {
        return currentValue(cameraState, ".focus_peaking") == "On"
    }

    // Focus peaking is now handled entirely by FocusPeakingSurfaceProcessor
    // (GPU shader in the CameraX preview pipeline). No CPU bitmap overlay needed.

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
